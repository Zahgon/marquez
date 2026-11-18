/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http.admin;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.codahale.metrics.json.HealthCheckModule;
import com.codahale.metrics.json.MetricsModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import marquez.http.Answer;

/**
 * The connector the service keeps for whoever runs it: a liveness answer, the health checks, a
 * thread dump, the metric registry as it stands and the handful of tasks that can be asked for.
 *
 * <p>It answers on a port of its own, as it always did, and it is deliberately separate from the
 * API: nothing here goes through the route table, and nothing of the API is reachable here.
 */
public final class AdminEndpoints {
  private static final String NO_CACHING = "must-revalidate,no-cache,no-store";
  private static final String MENU =
      "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\"\n"
          + "        \"http://www.w3.org/TR/html4/loose.dtd\">\n"
          + "<html>\n"
          + "<head>\n"
          + "  <title>Metrics</title>\n"
          + "</head>\n"
          + "<body>\n"
          + "  <h1>Operational Menu</h1>\n"
          + "  <ul>\n"
          + "    <li><a href=\"/metrics?pretty=true\">Metrics</a></li>\n"
          + "    <li><a href=\"/ping\">Ping</a></li>\n"
          + "    <li><a href=\"/threads\">Threads</a></li>\n"
          + "    <li><a href=\"/healthcheck?pretty=true\">Healthcheck</a></li>\n"
          + "    <li><a href=\"/pprof\">CPU Profile</a></li>\n"
          + "    <li><a href=\"/pprof?state=blocked\">CPU Contention</a></li>\n"
          + "  </ul>\n"
          + "</body>\n"
          + "</html>\n";
  private static final String READ_ONLY = "GET, HEAD, TRACE, OPTIONS";
  private static final String ALSO_WRITTEN_TO = "GET, HEAD, POST, TRACE, OPTIONS";

  private final MetricRegistry metrics;
  private final HealthCheckRegistry health;
  private final ObjectMapper healthMapper =
      new ObjectMapper().registerModule(new HealthCheckModule());
  private final ObjectMapper metricsMapper =
      new ObjectMapper()
          .registerModule(new MetricsModule(TimeUnit.SECONDS, TimeUnit.SECONDS, false));

  public AdminEndpoints(MetricRegistry metrics, HealthCheckRegistry health) {
    this.metrics = metrics;
    this.health = health;
  }

  /** Answers one request to the administrative connector. */
  public Answer answer(String method, String path, String query) {
    final boolean task = "/tasks".equals(path) || path.startsWith("/tasks/");
    if ("OPTIONS".equals(method)) {
      if (!known(path) && !task) {
        return new Answer(404);
      }
      // The answer to OPTIONS carries nothing, and says nothing about a length it does not have.
      return new Answer(200).header("Allow", task ? ALSO_WRITTEN_TO : READ_ONLY).withholdLength();
    }
    if (task) {
      return task(method, path);
    }
    if (!known(path)) {
      return new Answer(404);
    }
    // A method the servlet specification does not name is not implemented, where one it names
    // but this endpoint does not serve is refused.
    if ("PATCH".equals(method)) {
      return new Answer(501);
    }
    if (!"GET".equals(method) && !"HEAD".equals(method)) {
      return new Answer(405);
    }
    final boolean head = "HEAD".equals(method);
    switch (path) {
      case "/ping":
        return noCaching(text(head ? "text/plain" : "text/plain;charset=iso-8859-1", "pong\n"));
      case "/healthcheck":
        return noCaching(healthcheck(pretty(query)));
      case "/threads":
        return noCaching(threads(head));
      case "/metrics":
        return noCaching(registry(pretty(query), head));
      case "/":
      default:
        return noCaching(text(head ? "text/html" : "text/html;charset=utf-8", MENU));
    }
  }

  private boolean known(String path) {
    return "/".equals(path)
        || "/ping".equals(path)
        || "/healthcheck".equals(path)
        || "/threads".equals(path)
        || "/metrics".equals(path);
  }

  private static boolean pretty(String query) {
    return query != null && query.toLowerCase(Locale.ROOT).contains("pretty=true");
  }

  /** The tasks this service can be asked to run, and what asking for one answers. */
  private Answer task(String method, String path) {
    if ("/tasks".equals(path)) {
      if ("GET".equals(method)) {
        return text("text/plain;charset=iso-8859-1", "gc\nlog-level\n");
      }
      if ("HEAD".equals(method)) {
        return text("text/plain;charset=utf-8", "gc\nlog-level\n");
      }
      return new Answer(405);
    }
    final String name = path.substring("/tasks/".length());
    if (!isTask(name)) {
      return new Answer(404);
    }
    if (!"POST".equals(method)) {
      return new Answer(405);
    }
    // What a task writes it writes as it runs, so the answer carries no length.
    if ("gc".equals(name)) {
      System.gc();
      return text("text/plain;charset=utf-8", "Running GC...\nDone!\n").withholdLength();
    }
    return text("text/plain;charset=utf-8", "").withholdLength();
  }

  private static boolean isTask(String name) {
    return "gc".equals(name) || "log-level".equals(name);
  }

  private Answer healthcheck(boolean pretty) {
    final SortedMap<String, HealthCheck.Result> results = new TreeMap<>(health.runHealthChecks());
    if (results.isEmpty()) {
      return new Answer(501).header("Content-Type", "application/json");
    }
    boolean healthy = true;
    for (final HealthCheck.Result result : results.values()) {
      healthy &= result.isHealthy();
    }
    final Answer answer = new Answer(healthy ? 200 : 500);
    answer.header("Content-Type", "application/json");
    answer.body(written(healthMapper, results, pretty));
    return answer;
  }

  private Answer registry(boolean pretty, boolean head) {
    final Answer answer = new Answer(200);
    answer.header("Content-Type", "application/json");
    answer.body(written(metricsMapper, metrics, pretty));
    if (!head) {
      answer.withholdLength();
    }
    return answer;
  }

  private byte[] written(ObjectMapper mapper, Object value, boolean pretty) {
    try {
      return (pretty ? mapper.writerWithDefaultPrettyPrinter() : mapper.writer())
          .writeValueAsBytes(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException cannotWrite) {
      throw new IllegalStateException("could not write the administrative answer", cannotWrite);
    }
  }

  private Answer threads(boolean head) {
    final StringBuilder dump = new StringBuilder();
    for (final ThreadInfo thread : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
      dump.append(thread.toString());
    }
    final Answer answer = text("text/plain", dump.toString());
    if (!head) {
      answer.withholdLength();
    }
    return answer;
  }

  private static Answer text(String contentType, String body) {
    final Answer answer = new Answer(200);
    answer.header("Content-Type", contentType);
    answer.body(body.getBytes(StandardCharsets.UTF_8));
    return answer;
  }

  private static Answer noCaching(Answer answer) {
    answer.header("Cache-Control", NO_CACHING);
    return answer;
  }
}
