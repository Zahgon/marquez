/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.util.HashMap;
import java.util.Map;

/**
 * The reason phrases the server writes on the status line, and the message it puts in the entity of
 * an error it renders itself, which reads {@code HTTP <code> <reason>}.
 */
public final class Reasons {
  private static final Map<Integer, String> PHRASES = new HashMap<>();

  static {
    PHRASES.put(200, "OK");
    PHRASES.put(201, "Created");
    PHRASES.put(204, "No Content");
    PHRASES.put(206, "Partial Content");
    PHRASES.put(301, "Moved Permanently");
    PHRASES.put(304, "Not Modified");
    PHRASES.put(400, "Bad Request");
    PHRASES.put(404, "Not Found");
    PHRASES.put(405, "Method Not Allowed");
    PHRASES.put(406, "Not Acceptable");
    PHRASES.put(409, "Conflict");
    PHRASES.put(415, "Unsupported Media Type");
    // The connector this replaces wrote no phrase for this one, and neither does this.
    PHRASES.put(422, "");
    PHRASES.put(500, "Internal Server Error");
    PHRASES.put(501, "Not Implemented");
    PHRASES.put(503, "Service Unavailable");
  }

  private Reasons() {}

  public static String of(int status) {
    final String phrase = PHRASES.get(status);
    return phrase == null ? "Unknown" : phrase;
  }

  /** The entity message for an error the server renders itself. */
  public static String message(int status) {
    final String phrase = PHRASES.get(status);
    return phrase == null || phrase.isEmpty() ? "HTTP " + status : "HTTP " + status + " " + phrase;
  }
}
