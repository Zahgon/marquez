/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * The files the service ships with and serves as they are: the page that talks to the graphql
 * endpoint. The bundle that used to serve them belonged to the stack that is gone; the files
 * themselves, and the path they are served under, are unchanged.
 */
public final class StaticAssets {
  private final String prefix;
  private final String root;
  private final String index;

  public StaticAssets(String prefix, String root, String index) {
    this.prefix = prefix;
    this.root = root;
    this.index = index;
  }

  /** The path this serves everything under. */
  public String prefix() {
    return prefix;
  }

  /**
   * Answers one request for a file.
   *
   * <p>This path is served by something that only reads: it answers a {@code GET} and a {@code
   * HEAD}, it answers an {@code OPTIONS} itself and names in it what it serves, it refuses every
   * other method the protocol names, and a method the protocol does not name is not implemented.
   * The bytes of a file are written as they are read, so no length is announced for them - except
   * to a {@code HEAD}, which is answered with the length and nothing else.
   */
  public Answer answer(String method, String path) {
    return answer(method, path, null);
  }

  /** Answers one request for a file, or for the part of one a {@code Range} asks for. */
  public Answer answer(String method, String path, @Nullable String range) {
    return answer(method, path, range, null);
  }

  /**
   * Answers one request for a file, which is answered with nothing at all when the caller already
   * read it: these files are packed with the service and never change while it runs.
   */
  public Answer answer(
      String method, String path, @Nullable String range, @Nullable String ifModifiedSince) {
    if ("OPTIONS".equals(method)) {
      return new Answer(200).header("Allow", READ_ONLY).withholdLength();
    }
    if ("PATCH".equals(method)) {
      return new Answer(501);
    }
    final boolean head = "HEAD".equals(method);
    if (!"GET".equals(method) && !head) {
      return new Answer(405);
    }
    String wanted = path.substring(prefix.length());
    while (wanted.startsWith("/")) {
      wanted = wanted.substring(1);
    }
    if (wanted.isEmpty()) {
      wanted = index;
    }
    if (wanted.contains("..")) {
      return new Answer(404);
    }
    final URL url = StaticAssets.class.getResource(root + "/" + wanted);
    if (url == null) {
      return new Answer(404);
    }
    if (notModifiedSince(url, ifModifiedSince)) {
      // Nothing is written for an answer that says only that the file is the one the caller has.
      return new Answer(304).withholdLength();
    }
    try (InputStream file = url.openStream()) {
      final byte[] bytes = file.readAllBytes();
      final int[] asked = partOf(range, bytes.length);
      if (asked != null) {
        // Part of a file is answered with that part, and with the length of it: only a whole
        // file is written as it is read, without a length announced in front of it.
        return new Answer(206)
            .header("Content-Type", typeOf(wanted))
            .header("Content-Range", "bytes " + asked[0] + "-" + asked[1] + "/" + bytes.length)
            .body(head ? new byte[0] : Arrays.copyOfRange(bytes, asked[0], asked[1] + 1));
      }
      final Answer answer = new Answer(200).header("Content-Type", typeOf(wanted)).body(bytes);
      return head ? answer : answer.withholdLength();
    } catch (IOException cannotRead) {
      return Failures.unforeseen(cannotRead);
    }
  }

  /** Whether the file is no newer than the moment the caller says it last read it. */
  private static boolean notModifiedSince(URL url, @Nullable String ifModifiedSince) {
    if (ifModifiedSince == null) {
      return false;
    }
    try {
      final long packed = url.openConnection().getLastModified();
      final long read =
          ZonedDateTime.parse(ifModifiedSince, DateTimeFormatter.RFC_1123_DATE_TIME)
              .toInstant()
              .toEpochMilli();
      return packed > 0 && packed / 1000L <= read / 1000L;
    } catch (IOException | RuntimeException cannotTell) {
      return false;
    }
  }

  /** The part of a file of {@code length} bytes a {@code Range} asks for, if it asks for one. */
  @Nullable
  private static int[] partOf(@Nullable String range, int length) {
    if (range == null || !range.startsWith("bytes=") || range.indexOf(',') >= 0) {
      return null;
    }
    final String asked = range.substring("bytes=".length()).trim();
    final int dash = asked.indexOf('-');
    if (dash <= 0 || dash == asked.length() - 1) {
      return null;
    }
    try {
      final int from = Integer.parseInt(asked.substring(0, dash).trim());
      final int to = Integer.parseInt(asked.substring(dash + 1).trim());
      if (from < 0 || to < from || from >= length) {
        return null;
      }
      return new int[] {from, Math.min(to, length - 1)};
    } catch (NumberFormatException notARange) {
      return null;
    }
  }

  /** The methods this path answers, as the thing that used to serve it named them. */
  private static final String READ_ONLY = "GET, HEAD, TRACE, OPTIONS";

  /** The media type a file is offered as, with the character set the text types carry. */
  private static String typeOf(String name) {
    final String lowered = name.toLowerCase(Locale.ROOT);
    if (lowered.endsWith(".htm") || lowered.endsWith(".html")) {
      return "text/html;charset=utf-8";
    }
    if (lowered.endsWith(".css")) {
      return "text/css;charset=utf-8";
    }
    if (lowered.endsWith(".js")) {
      return "application/javascript";
    }
    return "application/octet-stream";
  }
}
