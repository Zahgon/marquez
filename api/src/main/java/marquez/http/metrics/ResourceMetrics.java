/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http.metrics;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import marquez.http.Route;

/**
 * The measurements the resources ask for, taken by the serving layer.
 *
 * <p>Each request method says what it wants measured: how long it takes, how its answers divide
 * between the classes of status, and how often it throws. The runtime that used to read those
 * annotations is gone, so this reads them, registers the same measurements under the same names,
 * and updates them around every call - which is what the service's own metric endpoints report.
 */
public final class ResourceMetrics {
  private final MetricRegistry registry;
  private final Set<String> registered = ConcurrentHashMap.newKeySet();

  public ResourceMetrics(MetricRegistry registry) {
    this.registry = registry;
  }

  /** Registers what a request method asks for, before the first request arrives. */
  public void register(Route route) {
    if (route.handOver() != null || !registered.add(route.metricName())) {
      return;
    }
    final String name = route.metricName();
    if (route.method().isAnnotationPresent(Timed.class)) {
      registry.timer(name);
      registry.timer(name + ".total");
      registry.timer(name + ".request.filtering");
      registry.timer(name + ".response.filtering");
    }
    if (route.method().isAnnotationPresent(ResponseMetered.class)) {
      for (int family = 1; family <= 5; family++) {
        registry.meter(name + "." + family + "xx-responses");
      }
    }
    if (route.method().isAnnotationPresent(ExceptionMetered.class)) {
      registry.meter(name + ".exceptions");
    }
  }

  /**
   * Registers what a request method of a handed-over resource asks for. Those objects are built for
   * the request they answer, so what they ask for is known only once one of them has run.
   */
  public void registerLate(Route route) {
    if (route.handOver() == null || !registered.add(route.metricName())) {
      return;
    }
    final String name = route.metricName();
    if (route.method().isAnnotationPresent(Timed.class)) {
      registry.timer(name);
      registry.timer(name + ".total");
      registry.timer(name + ".request.filtering");
      registry.timer(name + ".response.filtering");
    }
    if (route.method().isAnnotationPresent(ResponseMetered.class)) {
      for (int family = 1; family <= 5; family++) {
        registry.meter(name + "." + family + "xx-responses");
      }
    }
    if (route.method().isAnnotationPresent(ExceptionMetered.class)) {
      registry.meter(name + ".exceptions");
    }
  }

  /** Records one call. */
  public void record(Route route, int status, Throwable failure, long nanos) {
    final String name = route.metricName();
    if (route.method().isAnnotationPresent(Timed.class)) {
      registry.timer(name).update(nanos, TimeUnit.NANOSECONDS);
      registry.timer(name + ".total").update(nanos, TimeUnit.NANOSECONDS);
    }
    if (route.method().isAnnotationPresent(ResponseMetered.class)) {
      final int family = status / 100;
      if (family >= 1 && family <= 5) {
        registry.meter(name + "." + family + "xx-responses").mark();
      }
    }
    if (failure != null && route.method().isAnnotationPresent(ExceptionMetered.class)) {
      registry.meter(name + ".exceptions").mark();
    }
  }
}
