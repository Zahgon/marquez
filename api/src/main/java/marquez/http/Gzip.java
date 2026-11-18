/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/**
 * The compressed entities the serving layer this replaces handled for the service.
 *
 * <p>A request entity declared {@code gzip} is read as a stream of members, one after another, by
 * the small state machine below. It is deliberately the same shape as the one it replaces: it reads
 * the two bytes that name a member before it decides whether it has one, so a single byte after the
 * last member is the end of the stream while two are a member that is not one; and a stream that
 * simply stops is the end of what there was, while a check digit or a length that disagrees is a
 * failure the service answers as it answers any failure inside a request.
 *
 * <p>An answer is compressed only when the caller asked for exactly this encoding, the request was
 * a read that succeeded, and there is enough of it to be worth compressing.
 */
public final class Gzip {
  /** The smallest answer worth compressing, as the handler this replaces was configured. */
  private static final int SMALLEST = 256;

  private Gzip() {}

  /**
   * Whether a declared request encoding asks for the entity to be inflated.
   *
   * <p>Only the LAST coding named decides, because the codings are written in the order they were
   * applied: an entity declared {@code gzip, identity} was gzipped and then left alone, and is not
   * this reader's to inflate.
   */
  public static boolean declaresGzip(List<String> contentEncodings) {
    String last = null;
    for (final String value : contentEncodings) {
      final String[] codings = value.split(",", -1);
      last = codings[codings.length - 1];
    }
    return last != null && "gzip".equals(last.trim().toLowerCase(Locale.ROOT));
  }

  /** What the state machine is reading at each point of a member. */
  private enum Part {
    ID,
    METHOD,
    FLAGS,
    MTIME,
    EXTRA_FLAGS,
    OPERATING_SYSTEM,
    EXTRA_LENGTH,
    EXTRA,
    NAME,
    COMMENT,
    HEADER_CRC,
    DATA,
    CRC,
    SIZE
  }

  /** Inflates a request entity; a stream that names a member it then contradicts throws. */
  public static byte[] inflate(byte[] body) throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, body.length * 4));
    Inflater inflater = null;
    Part part = Part.ID;
    int read = 0;
    long value = 0;
    int flags = 0;
    int at = 0;
    try {
      while (at < body.length) {
        final int b = body[at] & 0xff;
        switch (part) {
          case ID:
            value += (long) b << (8 * read);
            at++;
            if (++read == 2) {
              if (value != 0x8b1fL) {
                throw new ZipException("not a gzip member");
              }
              part = Part.METHOD;
              read = 0;
              value = 0;
            }
            break;
          case METHOD:
            at++;
            if (b != 8) {
              throw new ZipException("unknown gzip compression method");
            }
            part = Part.FLAGS;
            break;
          case FLAGS:
            at++;
            flags = b;
            part = Part.MTIME;
            break;
          case MTIME:
            at++;
            if (++read == 4) {
              part = Part.EXTRA_FLAGS;
              read = 0;
            }
            break;
          case EXTRA_FLAGS:
            at++;
            part = Part.OPERATING_SYSTEM;
            break;
          case OPERATING_SYSTEM:
            at++;
            part = (flags & 0x04) != 0 ? Part.EXTRA_LENGTH : afterExtra(flags);
            break;
          case EXTRA_LENGTH:
            value += (long) b << (8 * read);
            at++;
            if (++read == 2) {
              part = Part.EXTRA;
              read = 0;
            }
            break;
          case EXTRA:
            at++;
            if (--value <= 0) {
              part = afterExtra(flags);
              value = 0;
            }
            break;
          case NAME:
            at++;
            if (b == 0) {
              part = (flags & 0x10) != 0 ? Part.COMMENT : afterComment(flags);
            }
            break;
          case COMMENT:
            at++;
            if (b == 0) {
              part = afterComment(flags);
            }
            break;
          case HEADER_CRC:
            at++;
            if (++read == 2) {
              part = Part.DATA;
              read = 0;
            }
            break;
          case DATA:
            if (inflater == null) {
              inflater = new Inflater(true);
            }
            inflater.setInput(body, at, body.length - at);
            final int taken = inflate(inflater, out);
            at += taken;
            if (inflater.finished()) {
              part = Part.CRC;
            } else if (inflater.needsInput() || taken == 0) {
              // Nothing more can be made of what is left; what was inflated is what there is.
              at = body.length;
            }
            break;
          case CRC:
            // The check digit is read and, as it was before, not compared: what is held against
            // the stream is the length it declares, below.
            at++;
            if (++read == 4) {
              part = Part.SIZE;
              read = 0;
              value = 0;
            }
            break;
          case SIZE:
          default:
            value += (long) b << (8 * read);
            at++;
            if (++read == 4) {
              if (inflater != null && value != inflater.getBytesWritten()) {
                throw new ZipException("the gzip length does not match");
              }
              inflater.end();
              inflater = null;
              part = Part.ID;
              read = 0;
              value = 0;
            }
            break;
        }
      }
    } finally {
      if (inflater != null) {
        inflater.end();
      }
    }
    return out.toByteArray();
  }

  private static Part afterExtra(int flags) {
    if ((flags & 0x08) != 0) {
      return Part.NAME;
    }
    return (flags & 0x10) != 0 ? Part.COMMENT : afterComment(flags);
  }

  private static Part afterComment(int flags) {
    return (flags & 0x02) != 0 ? Part.HEADER_CRC : Part.DATA;
  }

  /** Runs the inflater over what it was given; answers how many of those bytes it took. */
  private static int inflate(Inflater inflater, ByteArrayOutputStream out) throws IOException {
    final int offered = inflater.getRemaining();
    final byte[] buffer = new byte[8192];
    while (!inflater.finished() && !inflater.needsInput()) {
      final int written;
      try {
        written = inflater.inflate(buffer);
      } catch (DataFormatException malformed) {
        // What was inflated before the stream went wrong is what there is: the reader this
        // replaces handed that on rather than refusing the request.
        break;
      }
      if (written == 0) {
        break;
      }
      out.write(buffer, 0, written);
    }
    return offered - inflater.getRemaining();
  }

  /** Deflates an answer. */
  public static byte[] deflate(byte[] body) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream(body.length);
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(body);
    } catch (IOException cannotCompress) {
      throw new IllegalStateException("could not compress the answer", cannotCompress);
    }
    return out.toByteArray();
  }

  /**
   * Whether the caller asked for this encoding. Only {@code gzip} written out counts: a wildcard
   * does not, and neither does any other name. The last thing said about {@code gzip} is what
   * holds, so a quality of zero followed by a plain {@code gzip} still asks for it.
   */
  public static boolean accepted(List<String> acceptEncodings) {
    boolean wanted = false;
    for (final String value : acceptEncodings) {
      for (final String piece : value.split(",")) {
        final String trimmed = piece.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        final String[] parts = trimmed.split(";");
        if (!"gzip".equals(parts[0].trim().toLowerCase(Locale.ROOT))) {
          continue;
        }
        double quality = 1.0d;
        for (int at = 1; at < parts.length; at++) {
          final String parameter = parts[at].trim();
          if (parameter.toLowerCase(Locale.ROOT).startsWith("q=")) {
            try {
              quality = Double.parseDouble(parameter.substring(2).trim());
            } catch (NumberFormatException notANumber) {
              quality = 0.0d;
            }
          }
        }
        wanted = quality > 0;
      }
    }
    return wanted;
  }

  /** Whether an answer of this size is one the handler would have compressed. */
  public static boolean worthCompressing(byte[] body) {
    return body.length >= SMALLEST;
  }

  /** Whether the handler would have told caches that the answer varies by encoding. */
  public static boolean announcesVary(int status, String method) {
    return "GET".equals(method) && (status == 200 || status == 206);
  }
}
