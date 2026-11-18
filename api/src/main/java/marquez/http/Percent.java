/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Percent-decoding of one path segment or one query string value. */
public final class Percent {
  private Percent() {}

  /** Percent-encodes everything in one path segment that is not allowed to stand for itself. */
  public static String encodeSegment(String value) {
    final StringBuilder out = new StringBuilder(value.length());
    for (final byte raw : value.getBytes(StandardCharsets.UTF_8)) {
      final int b = raw & 0xff;
      if ((b >= 'a' && b <= 'z')
          || (b >= 'A' && b <= 'Z')
          || (b >= '0' && b <= '9')
          || "-._~!$&'()*+,;=:@".indexOf(b) >= 0) {
        out.append((char) b);
      } else {
        out.append('%').append(String.format("%02X", b));
      }
    }
    return out.toString();
  }

  /**
   * Decodes percent escapes, and, when {@code plusIsSpace}, a {@code +} as a space, which is what a
   * query string means by it and what a path segment does not. An escape that is not two
   * hexadecimal digits is left as it was written.
   *
   * <p>The work is done on BYTES and the result is read as UTF-8, because that is what a target
   * carries: a character the connector handed over as one byte of a longer sequence must be put
   * back as that byte before the sequence can be read.
   */
  public static String decode(String value, boolean plusIsSpace) {
    if (value.indexOf('%') < 0 && !(plusIsSpace && value.indexOf('+') >= 0) && isAscii(value)) {
      return value;
    }
    final ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
    for (int at = 0; at < value.length(); at++) {
      final char c = value.charAt(at);
      if (c == '%') {
        final int high = at + 1 < value.length() ? Character.digit(value.charAt(at + 1), 16) : -1;
        final int low = at + 2 < value.length() ? Character.digit(value.charAt(at + 2), 16) : -1;
        if (high >= 0 && low >= 0) {
          out.write((high << 4) + low);
          at += 2;
          continue;
        }
        out.write(c);
      } else if (c == '+' && plusIsSpace) {
        out.write(' ');
      } else if (c < 0x100) {
        out.write(c);
      } else {
        final byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
        out.write(encoded, 0, encoded.length);
      }
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  private static boolean isAscii(String value) {
    for (int at = 0; at < value.length(); at++) {
      if (value.charAt(at) > 0x7f) {
        return false;
      }
    }
    return true;
  }
}
