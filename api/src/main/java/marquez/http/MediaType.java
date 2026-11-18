/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One media type, with its parameters, as written in an {@code Accept} or a {@code Content-Type}.
 */
public final class MediaType {
  public static final MediaType WILDCARD = new MediaType("*", "*", new LinkedHashMap<>());
  public static final MediaType JSON = new MediaType("application", "json", new LinkedHashMap<>());
  public static final MediaType OCTET_STREAM =
      new MediaType("application", "octet-stream", new LinkedHashMap<>());
  public static final MediaType TEXT_PLAIN = new MediaType("text", "plain", new LinkedHashMap<>());

  private final String type;
  private final String subtype;
  private final Map<String, String> parameters;

  private MediaType(String type, String subtype, Map<String, String> parameters) {
    this.type = type;
    this.subtype = subtype;
    this.parameters = parameters;
  }

  /** One media type built from its parts. */
  public static MediaType of(String type, String subtype, Map<String, String> parameters) {
    return new MediaType(type, subtype, new LinkedHashMap<>(parameters));
  }

  /** Parses one media type; answers {@code null} when it is not one. */
  public static MediaType parse(String value) {
    if (value == null) {
      return null;
    }
    final String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    final String[] pieces = trimmed.split(";");
    final String full = pieces[0].trim();
    final int slash = full.indexOf('/');
    if (slash <= 0 || slash == full.length() - 1) {
      return null;
    }
    final String type = full.substring(0, slash).trim().toLowerCase(Locale.ROOT);
    final String subtype = full.substring(slash + 1).trim().toLowerCase(Locale.ROOT);
    if (type.isEmpty() || subtype.isEmpty() || type.contains(" ") || subtype.contains(" ")) {
      return null;
    }
    final Map<String, String> parameters = new LinkedHashMap<>();
    for (int at = 1; at < pieces.length; at++) {
      final String piece = pieces[at].trim();
      if (piece.isEmpty()) {
        continue;
      }
      final int equals = piece.indexOf('=');
      if (equals < 0) {
        parameters.put(piece.toLowerCase(Locale.ROOT), "");
      } else {
        parameters.put(
            piece.substring(0, equals).trim().toLowerCase(Locale.ROOT),
            unquote(piece.substring(equals + 1).trim()));
      }
    }
    return new MediaType(type, subtype, parameters);
  }

  private static String unquote(String value) {
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  /**
   * Parses an {@code Accept} header. Several header lines of the same name are one list, joined in
   * the order they arrived, which is what the runtime this replaces did with a repeated {@code
   * Accept}; a value that is not a media type makes the whole header unusable.
   */
  public static List<MediaType> parseAccept(List<String> headerValues) {
    final List<MediaType> accepted = new ArrayList<>();
    for (final String headerValue : headerValues) {
      for (final String piece : headerValue.split(",")) {
        if (piece.trim().isEmpty()) {
          continue;
        }
        final MediaType parsed = parse(piece);
        if (parsed == null) {
          return null;
        }
        accepted.add(parsed);
      }
    }
    return accepted;
  }

  /** The {@code q} parameter, 1 when it is absent and 0 when it cannot be read as a number. */
  public double quality() {
    final String q = parameters.get("q");
    if (q == null) {
      return 1.0d;
    }
    try {
      final double value = Double.parseDouble(q);
      return value < 0 || value > 1 ? 0.0d : value;
    } catch (NumberFormatException notANumber) {
      return 0.0d;
    }
  }

  /** Whether this type, which may carry wildcards, covers {@code other}. */
  public boolean covers(MediaType other) {
    final boolean typeOk = "*".equals(type) || type.equals(other.type);
    final boolean subtypeOk = "*".equals(subtype) || subtype.equals(other.subtype);
    return typeOk && subtypeOk;
  }

  public boolean isWildcard() {
    return "*".equals(type) || "*".equals(subtype);
  }

  public String type() {
    return type;
  }

  public String subtype() {
    return subtype;
  }

  public Map<String, String> parameters() {
    return parameters;
  }

  /** The type without its parameters, which is what a response's own content type carries. */
  public String withoutParameters() {
    return type + "/" + subtype;
  }

  @Override
  public String toString() {
    final StringBuilder out = new StringBuilder(withoutParameters());
    for (final Map.Entry<String, String> parameter : parameters.entrySet()) {
      out.append(';').append(parameter.getKey());
      if (!parameter.getValue().isEmpty()) {
        out.append('=').append(parameter.getValue());
      }
    }
    return out.toString();
  }
}
