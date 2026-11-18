/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The request target exactly as it arrived, split for matching.
 *
 * <p>The target is NOT normalised: an empty segment, a {@code .} segment and a {@code ..} segment
 * are kept and offered to matching as they came, because the application connector is configured
 * for the legacy URI compliance mode, under which the server passes such a target through instead
 * of collapsing or refusing it. Matrix parameters are removed from each segment, and a segment is
 * percent-decoded only AFTER it has been matched, so an encoded separator stays inside the segment
 * it was written in rather than splitting it.
 */
public final class RequestTarget {
  private final String rawPath;
  private final String query;
  private final List<String> rawSegments;
  private final boolean trailingSlash;

  private RequestTarget(
      String rawPath, String query, List<String> rawSegments, boolean trailingSlash) {
    this.rawPath = rawPath;
    this.query = query;
    this.rawSegments = rawSegments;
    this.trailingSlash = trailingSlash;
  }

  /** Splits a raw request target into its path segments and its query string. */
  public static RequestTarget of(String target) {
    String path = target;
    String query = null;
    final int hash = path.indexOf('#');
    if (hash >= 0) {
      path = path.substring(0, hash);
    }
    final int mark = path.indexOf('?');
    if (mark >= 0) {
      query = path.substring(mark + 1);
      path = path.substring(0, mark);
    }
    final List<String> segments = new ArrayList<>();
    final boolean trailing = path.length() > 1 && path.endsWith("/");
    for (final String segment : path.split("/", -1)) {
      segments.add(stripMatrix(segment));
    }
    // The leading empty segment produced by the leading slash is not a segment of its own.
    if (!segments.isEmpty() && segments.get(0).isEmpty()) {
      segments.remove(0);
    }
    if (trailing && !segments.isEmpty()) {
      segments.remove(segments.size() - 1);
    }
    return new RequestTarget(path, query, segments, trailing);
  }

  private static String stripMatrix(String segment) {
    final int semi = segment.indexOf(';');
    return semi < 0 ? segment : segment.substring(0, semi);
  }

  public String rawPath() {
    return rawPath;
  }

  public String query() {
    return query;
  }

  public List<String> rawSegments() {
    return rawSegments;
  }

  public boolean trailingSlash() {
    return trailingSlash;
  }

  /**
   * The query string parsed into the values of each name, in the order they were written. A name
   * written without {@code =} has no value and does not appear.
   */
  public Map<String, List<String>> queryParameters() {
    final Map<String, List<String>> values = new LinkedHashMap<>();
    if (query == null || query.isEmpty()) {
      return values;
    }
    for (final String pair : query.split("&", -1)) {
      if (pair.isEmpty()) {
        continue;
      }
      final int equals = pair.indexOf('=');
      if (equals < 0) {
        continue;
      }
      final String name = Percent.decode(pair.substring(0, equals), true);
      final String value = Percent.decode(pair.substring(equals + 1), true);
      values.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
    }
    return values;
  }
}
