/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http.admin;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import marquez.http.Answer;
import marquez.http.Percent;

/**
 * The two Prometheus endpoints the service exposes on the API connector, one for each registry it
 * fills: the default one, which carries the service's own measurements and the runtime's, and the
 * one its database layer keeps.
 *
 * <p>The caller may ask for a subset by name, and may ask for the newer text format by saying so in
 * {@code Accept}; both are what the exporter this replaces did, and both are kept.
 */
public final class PrometheusEndpoint {
  private final CollectorRegistry registry;

  public PrometheusEndpoint(CollectorRegistry registry) {
    this.registry = registry;
  }

  /** The methods these endpoints answer, as the exporter that used to serve them named them. */
  private static final String ALLOW = "GET, HEAD, POST, TRACE, OPTIONS";

  /**
   * Answers one request for the measurements.
   *
   * <p>What serves these reads only, and it answered a {@code POST} with the measurements as well
   * as a {@code GET}. It answers an {@code OPTIONS} itself, it refuses every other method the
   * protocol names, and a method the protocol does not name is not implemented.
   */
  public Answer answer(String method, String query, String accept) {
    final boolean head = "HEAD".equals(method);
    if ("OPTIONS".equals(method)) {
      return new Answer(200).header("Allow", ALLOW).withholdLength();
    }
    if ("PATCH".equals(method)) {
      return new Answer(501);
    }
    if (!"GET".equals(method) && !head && !"POST".equals(method)) {
      return new Answer(405);
    }
    final String contentType = TextFormat.chooseContentType(accept);
    final Set<String> wanted = wanted(query);
    final StringWriter written = new StringWriter();
    try {
      TextFormat.writeFormat(
          contentType,
          written,
          wanted == null
              ? registry.metricFamilySamples()
              : registry.filteredMetricFamilySamples(wanted));
    } catch (IOException cannotWrite) {
      throw new IllegalStateException("could not write the measurements", cannotWrite);
    }
    // The measurements are written out as they are read, so their length is not known in
    // advance - except to a HEAD, which is answered with the length and nothing else.
    final Answer answer =
        new Answer(200)
            .header("Content-Type", contentType)
            .body(written.toString().getBytes(StandardCharsets.UTF_8));
    return head ? answer : answer.withholdLength();
  }

  /** The names the caller asked for, or all of them when it asked for none. */
  private static Set<String> wanted(String query) {
    final Set<String> names = new HashSet<>();
    if (query == null || query.isEmpty()) {
      return null;
    }
    for (final String pair : query.split("&")) {
      final int equals = pair.indexOf('=');
      if (equals > 0 && "name[]".equals(Percent.decode(pair.substring(0, equals), true))) {
        names.add(Percent.decode(pair.substring(equals + 1), true));
      }
    }
    return names.isEmpty() ? null : names;
  }
}
