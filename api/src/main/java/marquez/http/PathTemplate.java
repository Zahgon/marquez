/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One resource path, as written on a resource class and on its methods: a sequence of segments,
 * each either a literal or a named parameter, optionally with its own regular expression.
 *
 * <p>Matching compares segment by segment against the target as it arrived, so a target carrying an
 * empty, {@code .} or {@code ..} segment matches nothing and is answered by the server itself. A
 * single trailing slash is accepted, as it was by the runtime this replaces.
 */
public final class PathTemplate {
  /** A segment that is a parameter, with the expression its value has to match. */
  private static final class Part {
    private final String literal;
    private final String name;
    private final Pattern pattern;

    private Part(String literal, String name, Pattern pattern) {
      this.literal = literal;
      this.name = name;
      this.pattern = pattern;
    }
  }

  private final String declared;
  private final List<Part> parts;
  private final int literalSegments;
  private final int parameters;
  private final int literalCharacters;

  private PathTemplate(String declared, List<Part> parts) {
    this.declared = declared;
    this.parts = parts;
    int literals = 0;
    int params = 0;
    int characters = 0;
    for (final Part part : parts) {
      if (part.literal != null) {
        literals++;
        characters += part.literal.length();
      } else {
        params++;
      }
    }
    this.literalSegments = literals;
    this.parameters = params;
    this.literalCharacters = characters;
  }

  /** Builds a template from the paths declared on a resource class and on one of its methods. */
  public static PathTemplate of(String classPath, String methodPath) {
    final String joined = join(classPath, methodPath);
    final List<Part> parts = new ArrayList<>();
    for (final String segment : joined.split("/", -1)) {
      if (segment.isEmpty()) {
        continue;
      }
      if (segment.startsWith("{") && segment.endsWith("}")) {
        final String inside = segment.substring(1, segment.length() - 1);
        final int colon = inside.indexOf(':');
        if (colon < 0) {
          parts.add(new Part(null, inside.trim(), null));
        } else {
          parts.add(
              new Part(
                  null,
                  inside.substring(0, colon).trim(),
                  Pattern.compile(inside.substring(colon + 1).trim())));
        }
      } else {
        parts.add(new Part(segment, null, null));
      }
    }
    return new PathTemplate(joined, parts);
  }

  private static String join(String classPath, String methodPath) {
    final StringBuilder joined = new StringBuilder();
    for (final String piece : new String[] {classPath, methodPath}) {
      if (piece == null || piece.isEmpty()) {
        continue;
      }
      if (joined.length() > 0 && !piece.startsWith("/")) {
        joined.append('/');
      }
      joined.append(piece);
    }
    String out = joined.toString();
    if (!out.startsWith("/")) {
      out = "/" + out;
    }
    while (out.length() > 1 && out.endsWith("/")) {
      out = out.substring(0, out.length() - 1);
    }
    return out.replace("//", "/");
  }

  /**
   * Matches the target's segments; answers the parameter values, decoded, or {@code null} when the
   * template does not match.
   */
  public Map<String, String> match(List<String> segments) {
    if (segments.size() != parts.size()) {
      return null;
    }
    final Map<String, String> values = new LinkedHashMap<>();
    for (int at = 0; at < parts.size(); at++) {
      final Part part = parts.get(at);
      final String segment = segments.get(at);
      if (part.literal != null) {
        if (!part.literal.equals(segment)) {
          return null;
        }
        continue;
      }
      // A parameter never matches an empty segment, and its expression, when it has one, is
      // applied to the segment as it was written.
      if (segment.isEmpty()) {
        return null;
      }
      if (part.pattern != null && !part.pattern.matcher(segment).matches()) {
        return null;
      }
      values.put(part.name, Percent.decode(segment, false));
    }
    return values;
  }

  public String declared() {
    return declared;
  }

  public List<String> parameterNames() {
    final List<String> names = new ArrayList<>();
    for (final Part part : parts) {
      if (part.name != null) {
        names.add(part.name);
      }
    }
    return Collections.unmodifiableList(names);
  }

  public int literalSegments() {
    return literalSegments;
  }

  public int parameters() {
    return parameters;
  }

  public int literalCharacters() {
    return literalCharacters;
  }

  public int segments() {
    return parts.size();
  }
}
