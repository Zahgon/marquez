/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import io.helidon.common.http.DataChunk;
import io.helidon.common.http.Http;
import io.helidon.common.reactive.Multi;
import io.helidon.common.reactive.Single;
import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.WebServer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import marquez.http.admin.AdminEndpoints;
import marquez.http.admin.PrometheusEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two connectors the service listens on, and the only place that touches the network.
 *
 * <p>Helidon SE's web server is given one handler per connector; everything above it - matching,
 * conversion, the entities and the error answers - is this package's own work. The request target
 * is taken as it arrived, without being collapsed or rewritten, because that is what the connector
 * this replaces was configured to do and what the service's answers depend on.
 */
public final class MarquezServer {
  private static final Logger log = LoggerFactory.getLogger(MarquezServer.class);

  private final WebServer application;
  private final WebServer admin;
  private final ExecutorService work;

  private MarquezServer(WebServer application, WebServer admin, ExecutorService work) {
    this.application = application;
    this.admin = admin;
    this.work = work;
  }

  /**
   * Builds and starts both connectors.
   *
   * <p>{@code aroundRequest} is given the requests of the API connector - all of them, whatever
   * answers them - because that is the place the filter the old wiring mapped to every path held.
   * The administrative connector was never in that mapping and is not now.
   */
  public static MarquezServer start(
      int applicationPort,
      int adminPort,
      Dispatcher dispatcher,
      List<RequestFilters.AroundRequest> aroundRequest,
      Map<String, PrometheusEndpoint> measurements,
      Map<String, BiFunction<RequestContext, byte[], Answer>> servedBesideTheApi,
      StaticAssets assets,
      AdminEndpoints adminEndpoints) {
    final ExecutorService work =
        Executors.newCachedThreadPool(
            runnable -> {
              final Thread thread = new Thread(runnable, "marquez-request");
              thread.setDaemon(true);
              return thread;
            });
    final WebServer application =
        WebServer.builder(
                Routing.builder()
                    .any(
                        (request, response) ->
                            onRequest(
                                work,
                                request,
                                response,
                                aroundRequest,
                                (context, body) ->
                                    api(
                                        dispatcher,
                                        measurements,
                                        servedBesideTheApi,
                                        assets,
                                        context,
                                        body),
                                dispatcher::answered))
                    .build())
            .port(applicationPort)
            .build();
    final WebServer admin =
        WebServer.builder(
                Routing.builder()
                    .any(
                        (request, response) ->
                            onRequest(
                                work,
                                request,
                                response,
                                List.of(),
                                (context, body) ->
                                    adminEndpoints.answer(
                                        context.method(),
                                        context.target().rawPath(),
                                        context.target().query()),
                                context -> {}))
                    .build())
            .port(adminPort)
            .build();
    application.start().await(5, TimeUnit.MINUTES);
    admin.start().await(5, TimeUnit.MINUTES);
    log.info(
        "Listening on {} for the API and on {} for the administrative endpoints.",
        application.port(),
        admin.port());
    return new MarquezServer(application, admin, work);
  }

  /** Stops both connectors. */
  public void stop() {
    application.shutdown().await(1, TimeUnit.MINUTES);
    admin.shutdown().await(1, TimeUnit.MINUTES);
    work.shutdownNow();
  }

  /** Waits until the connectors are shut down. */
  public void awaitShutdown() {
    application.whenShutdown().await();
  }

  /** What the API connector serves: the measurements, what is bolted beside it, and the API. */
  private static Answer api(
      Dispatcher dispatcher,
      Map<String, PrometheusEndpoint> measurements,
      Map<String, BiFunction<RequestContext, byte[], Answer>> besideTheApi,
      StaticAssets assets,
      RequestContext request,
      byte[] body) {
    // What is bolted beside the API was mapped by path, and the mapping was made against the
    // path with its matrix parameters taken off and its dot segments resolved - unlike the
    // resources of the API, which are matched against the target exactly as it arrived.
    final String path = mappedPath(request.target().rawPath());
    final PrometheusEndpoint endpoint = measurements.get(path);
    if (endpoint != null) {
      return endpoint.answer(
          request.method(), request.target().query(), request.firstHeader("accept"));
    }
    final BiFunction<RequestContext, byte[], Answer> beside = besideTheApi.get(path);
    if (beside != null) {
      return beside.apply(request, body);
    }
    if (assets != null && under(assets.prefix(), path)) {
      return assets.answer(
          request.method(),
          path,
          request.firstHeader("range"),
          request.firstHeader("if-modified-since"));
    }
    return dispatcher.answer(request);
  }

  /** Whether {@code path} is the path {@code prefix} names, or one below it. */
  private static boolean under(String prefix, String path) {
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }

  /**
   * A path as the mapping of everything beside the API saw it: the matrix parameters off every
   * segment, the empty segments and the dot segments resolved away - and the trailing slash kept,
   * because a mapping made against one path is not the mapping of the path with a slash after it.
   */
  static String mappedPath(String rawPath) {
    final StringBuilder out = new StringBuilder();
    final List<String> segments = new ArrayList<>();
    boolean trailing = rawPath.endsWith("/");
    for (final String raw : rawPath.split("/", -1)) {
      final int matrix = raw.indexOf(';');
      final String segment = matrix < 0 ? raw : raw.substring(0, matrix);
      if (segment.isEmpty()) {
        continue;
      }
      if (".".equals(segment)) {
        trailing = true;
        continue;
      }
      if ("..".equals(segment)) {
        if (!segments.isEmpty()) {
          segments.remove(segments.size() - 1);
        }
        trailing = true;
        continue;
      }
      segments.add(segment);
    }
    for (final String segment : segments) {
      out.append('/').append(segment);
    }
    if (trailing) {
      out.append('/');
    }
    if (out.length() == 0) {
      return "/";
    }
    return out.toString();
  }

  /**
   * Reads the request, hands it to the layer above on a thread of its own - the connector's own
   * threads must not be held while the service works - writes whatever comes back, and only then
   * takes down what was held for the request.
   */
  private static void onRequest(
      ExecutorService work,
      ServerRequest request,
      ServerResponse response,
      List<RequestFilters.AroundRequest> around,
      BiFunction<RequestContext, byte[], Answer> answering,
      Consumer<RequestContext> whenWritten) {
    bodyOf(request)
        .thenAccept(
            body ->
                work.execute(
                    () -> {
                      final String method = request.method().name();
                      RequestContext context = null;
                      try {
                        Answer answer;
                        try {
                          context = contextOf(request, body);
                          for (final RequestFilters.AroundRequest filter : around) {
                            filter.opened(context);
                          }
                          answer = answering.apply(context, context.entity());
                        } catch (Throwable thrown) {
                          if (context != null) {
                            context.failed(thrown);
                          }
                          answer = Failures.unforeseen(thrown);
                        }
                        Compression.apply(
                            method,
                            context == null ? new ArrayList<>() : context.header("accept-encoding"),
                            answer);
                        write(response, method, answer);
                      } finally {
                        // However this request ended - answered, or thrown out of anywhere above -
                        // what was held for it is taken down, on the thread that held it. Nothing
                        // can be left behind on a thread that is about to serve another request.
                        finished(around, whenWritten, context);
                      }
                    }))
        .exceptionally(
            thrown -> {
              write(response, request.method().name(), Failures.unforeseen(thrown));
              return null;
            });
  }

  /**
   * The end of one request: the layer that answered it takes down what it set up, and then the
   * filters that stand around every request are closed, the outermost last, the way the chain they
   * used to sit in unwound. Both run on the thread that handled the request and after its answer
   * has been written, which is what the per-request logging context is cleared by.
   */
  private static void finished(
      List<RequestFilters.AroundRequest> around,
      Consumer<RequestContext> whenWritten,
      RequestContext context) {
    if (context == null) {
      return;
    }
    try {
      whenWritten.accept(context);
    } catch (RuntimeException cannotFinish) {
      log.warn("Could not finish a request", cannotFinish);
    }
    for (int filter = around.size() - 1; filter >= 0; filter--) {
      try {
        around.get(filter).closed(context, context.failure());
      } catch (RuntimeException cannotFinish) {
        log.warn("Could not finish a request", cannotFinish);
      }
    }
  }

  /**
   * The bytes of the request entity, read as they arrived.
   *
   * <p>The chunks are collected by hand rather than through the connector's own readers: what the
   * request says its entity is may be nonsense, and deciding what to do about that belongs to the
   * layer above, not to the connector.
   */
  private static Single<byte[]> bodyOf(ServerRequest request) {
    return Multi.create(request.content())
        .map(
            chunk -> {
              final byte[] bytes = chunk.bytes();
              chunk.release();
              return bytes;
            })
        .collectList()
        .map(
            pieces -> {
              int length = 0;
              for (final byte[] piece : pieces) {
                length += piece.length;
              }
              final byte[] body = new byte[length];
              int at = 0;
              for (final byte[] piece : pieces) {
                System.arraycopy(piece, 0, body, at, piece.length);
                at += piece.length;
              }
              return body;
            });
  }

  /** Everything the layer above is told about the request, read off the connector once. */
  private static RequestContext contextOf(ServerRequest request, byte[] body) throws Exception {
    final Map<String, List<String>> headers = new LinkedHashMap<>();
    request
        .headers()
        .toMap()
        .forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values));
    final String query = request.query();
    final String rawPath = request.uri().getRawPath();
    final RequestTarget target =
        RequestTarget.of(rawPath + (query == null || query.isEmpty() ? "" : "?" + query));
    final String host =
        headers.containsKey("host") && !headers.get("host").isEmpty()
            ? headers.get("host").get(0)
            : "localhost:" + request.localPort();
    final RequestContext context =
        new RequestContext(request.method().name(), target, headers, "http://" + host + "/");
    context.entity(
        Dispatcher.entityOf(body, headers.getOrDefault("content-encoding", new ArrayList<>())));
    return context;
  }

  /** One body, handed over in more than one piece so that its length is not announced. */
  private static Multi<DataChunk> pieces(byte[] body) {
    final int half = body.length / 2;
    final byte[] first = Arrays.copyOfRange(body, 0, half);
    final byte[] second = Arrays.copyOfRange(body, half, body.length);
    return Multi.just(DataChunk.create(first), DataChunk.create(second));
  }

  /**
   * Writes one answer, with the entity a {@code HEAD} asks about but does not take, and waits for
   * it to leave.
   */
  private static void write(ServerResponse response, String method, Answer answer) {
    try {
      response.status(Http.ResponseStatus.create(answer.status(), answer.reason()));
      for (final Map.Entry<String, List<String>> header : answer.headers().entrySet()) {
        for (final String value : header.getValue()) {
          response.headers().add(header.getKey(), value);
        }
      }
      final byte[] body = answer.body();
      final Single<ServerResponse> sent;
      if ("HEAD".equals(method)) {
        // A HEAD is answered with the length of the entity it does not carry - unless the answer
        // has no entity at all, in which case it says nothing about a length either.
        if (answer.lengthWithheld()) {
          sent = response.send(pieces(new byte[0]));
        } else {
          response.headers().contentLength(body.length);
          sent = response.send();
        }
      } else if (answer.lengthWithheld()) {
        // Written out as it is read, in pieces, so that the answer carries no length - which is
        // how the endpoints that write a live reading of the service have always answered.
        sent = response.send(pieces(body));
      } else {
        sent = response.send(body);
      }
      // Waited for on the request's own thread, never the connector's: nothing held for a request
      // may be taken down before its answer has actually gone out.
      sent.await(1, TimeUnit.MINUTES);
    } catch (RuntimeException cannotWrite) {
      log.error("Could not write the answer", cannotWrite);
    }
  }
}
