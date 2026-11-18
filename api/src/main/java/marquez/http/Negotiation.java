/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Choosing the media type an answer is written in, and deciding which request entities a method
 * will read.
 *
 * <p>An {@code Accept} that is not a list of media types is refused before anything else happens,
 * with nothing written in the answer: that is the connector's own refusal and it never reaches the
 * application. A header that parses but names nothing the matched method offers is the
 * application's {@code 406}. Several {@code Accept} lines are one list, read in the order they
 * arrived.
 */
public final class Negotiation {
  /** Thrown when the {@code Accept} header is not a list of media types at all. */
  public static final class UnreadableAccept extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UnreadableAccept(String detail) {
      super(detail);
    }
  }

  private Negotiation() {}

  /**
   * Reads {@code Accept} strictly. An element that is not a media type, a parameter without a
   * value, and a quality that is not a number between zero and one with at most three decimals are
   * all refused, because the reader this replaces refused them.
   */
  public static List<MediaType> accepted(List<String> headerValues) {
    final List<MediaType> accepted = new ArrayList<>();
    for (final String headerValue : headerValues) {
      if (headerValue.trim().isEmpty()) {
        continue;
      }
      final String[] elements = headerValue.split(",", -1);
      for (int at = 0; at < elements.length; at++) {
        final String element = elements[at].trim();
        if (element.isEmpty()) {
          // A list may end with a comma; an empty element anywhere else is not a media type.
          if (at == elements.length - 1) {
            continue;
          }
          throw new UnreadableAccept("empty media type in Accept");
        }
        accepted.add(strict(element));
      }
    }
    return accepted;
  }

  /** One media type read strictly, with its quality checked. */
  private static MediaType strict(String element) {
    final String[] pieces = element.split(";", -1);
    final String full = pieces[0].trim();
    final int slash = full.indexOf('/');
    if (slash == 0 || (slash > 0 && slash == full.length() - 1)) {
      throw new UnreadableAccept("not a media type: " + full);
    }
    if (full.indexOf(' ') >= 0 || full.indexOf('\t') >= 0) {
      throw new UnreadableAccept("not a media type: " + full);
    }
    final Map<String, String> parameters = new LinkedHashMap<>();
    for (int at = 1; at < pieces.length; at++) {
      final String piece = pieces[at].trim();
      if (piece.isEmpty()) {
        continue;
      }
      final int equals = piece.indexOf('=');
      if (equals <= 0 || equals == piece.length() - 1) {
        throw new UnreadableAccept("not a media type parameter: " + piece);
      }
      final String name = piece.substring(0, equals).trim().toLowerCase(Locale.ROOT);
      String value = piece.substring(equals + 1).trim();
      if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1);
      }
      if ("q".equals(name)) {
        checkQuality(value);
      }
      if ("charset".equals(name)) {
        value = value.toLowerCase(Locale.ROOT);
      }
      parameters.put(name, value);
    }
    if (slash < 0) {
      // A bare asterisk asks for everything; any other bare word names nothing that can be served.
      return "*".equals(full)
          ? MediaType.of("*", "*", parameters)
          : MediaType.of(full.toLowerCase(Locale.ROOT), "", parameters);
    }
    final String type = full.substring(0, slash).toLowerCase(Locale.ROOT);
    final String subtype = full.substring(slash + 1).toLowerCase(Locale.ROOT);
    return MediaType.of(type, subtype, parameters);
  }

  private static void checkQuality(String value) {
    final double quality;
    try {
      quality = Double.parseDouble(value);
    } catch (NumberFormatException notANumber) {
      throw new UnreadableAccept("quality is not a number: " + value);
    }
    if (quality < 0 || quality > 1) {
      throw new UnreadableAccept("quality out of range: " + value);
    }
    final int point = value.indexOf('.');
    if (point >= 0 && value.length() - point - 1 > 3) {
      throw new UnreadableAccept("quality too precise: " + value);
    }
  }

  /**
   * The type an answer is written in: the first acceptable type, most wanted first, that the method
   * offers. A wildcard is answered with the type the method declares; a type written out is
   * answered with the parameters it was written with, less its quality. {@code null} when nothing
   * the method offers was asked for.
   */
  public static MediaType chosen(List<MediaType> accepted, List<MediaType> offered) {
    final List<MediaType> offers = offered.isEmpty() ? List.of(MediaType.JSON) : offered;
    if (accepted.isEmpty()) {
      return offers.get(0);
    }
    final List<MediaType> wanted = new ArrayList<>(accepted);
    wanted.sort(
        Comparator.comparingDouble((MediaType type) -> -type.quality())
            .thenComparingInt(Negotiation::vagueness));
    for (final MediaType want : wanted) {
      for (final MediaType offer : offers) {
        if (want.covers(offer)) {
          return answerType(want, offer);
        }
      }
    }
    return null;
  }

  /** Written out first, then a wildcard subtype, then a wildcard type. */
  private static int vagueness(MediaType type) {
    if ("*".equals(type.type())) {
      return 2;
    }
    return "*".equals(type.subtype()) ? 1 : 0;
  }

  /**
   * The type the answer is written as: a type asked for by name is answered with the parameters it
   * was asked for, less its quality; a wildcard is answered with the type the method declares and
   * nothing else.
   */
  private static MediaType answerType(MediaType want, MediaType offer) {
    final Map<String, String> parameters = new LinkedHashMap<>();
    if (!want.isWildcard()) {
      for (final Map.Entry<String, String> parameter : want.parameters().entrySet()) {
        if (!"q".equals(parameter.getKey())) {
          parameters.put(parameter.getKey(), parameter.getValue());
        }
      }
    }
    parameters.putAll(offer.parameters());
    final String type = "*".equals(want.type()) ? offer.type() : want.type();
    final String subtype = "*".equals(want.subtype()) ? offer.subtype() : want.subtype();
    return MediaType.of(type, subtype, parameters);
  }

  /**
   * The type the request says its entity is. A type that is not one at all is the connector's own
   * refusal, the same one an unreadable {@code Accept} gets.
   */
  public static MediaType requestType(List<String> contentTypes) {
    if (contentTypes.isEmpty()) {
      return null;
    }
    if (contentTypes.size() > 1) {
      throw new UnreadableAccept("the request says its entity is of more than one type");
    }
    final String written = contentTypes.get(0).trim();
    if (written.isEmpty() || written.indexOf('/') < 0) {
      throw new UnreadableAccept("not a media type: " + written);
    }
    return strict(written);
  }

  /**
   * Whether a method that declares the types it reads will read this request's entity. A request
   * that carries no type and no entity is not held against the method; one that carries an entity
   * without saying what it is is read as bytes, which no method of this service reads.
   */
  public static boolean reads(List<MediaType> consumes, MediaType requestType) {
    if (consumes.isEmpty() || requestType == null) {
      return true;
    }
    for (final MediaType allowed : consumes) {
      if (allowed.covers(requestType)) {
        return true;
      }
    }
    return false;
  }
}
