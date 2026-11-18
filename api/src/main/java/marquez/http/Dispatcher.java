/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.ws.rs.core.Response;
import marquez.http.jaxrs.SimpleAsyncResponse;
import marquez.http.jaxrs.SimpleUriInfo;
import marquez.http.metrics.ResourceMetrics;

/**
 * The one path a request takes through the serving layer.
 *
 * <p>In order: the filters that run for every request this layer takes in are run, the target is
 * matched against the route table, the request method decides which of the matched routes can
 * answer, the media types the request names are read, the filters that need the matched route run,
 * the parameters are converted, the constraints declared on them are checked, the resource method
 * is called, and whatever comes back - an answer or a failure - is turned into bytes. The order is
 * the one the runtime this replaces used, and it is visible from outside: a value that cannot be
 * converted is answered before a constraint is ever looked at.
 *
 * <p>Every request that gets this far leaves through the same door: there is one place where the
 * answer is handed back, and every answer passes the response filters there, whether a resource
 * method produced it or this layer decided it on its own - no route for the target, no route for
 * the method, nothing that can be read or written, a header that is not what it says it is, or a
 * failure nobody foresaw.
 */
public final class Dispatcher {
  private final RouteTable table;
  private final Failures failures;
  private final ResourceMetrics metrics;
  private final List<RequestFilters.WhenTakenIn> whenTakenIn;
  private final List<RequestFilters.BeforeResource> beforeResource;
  private final List<RequestFilters.AfterResource> afterResource;
  private final List<RequestFilters.WhenComplete> whenComplete;

  public Dispatcher(
      RouteTable table,
      Failures failures,
      ResourceMetrics metrics,
      List<RequestFilters.WhenTakenIn> whenTakenIn,
      List<RequestFilters.BeforeResource> beforeResource,
      List<RequestFilters.AfterResource> afterResource,
      List<RequestFilters.WhenComplete> whenComplete) {
    this.table = table;
    this.failures = failures;
    this.metrics = metrics;
    this.whenTakenIn = whenTakenIn;
    this.beforeResource = beforeResource;
    this.afterResource = afterResource;
    this.whenComplete = whenComplete;
  }

  public RouteTable table() {
    return table;
  }

  /**
   * Answers one request: the filters that stand around the whole of it, the path it takes, and the
   * response filters every answer leaves through.
   */
  public Answer answer(RequestContext request) {
    // From here until its answer has been written this request is one this layer holds state for,
    // which is what answered() below is called to take down.
    request.takenIn(true);
    Answer answer;
    try {
      for (final RequestFilters.WhenTakenIn filter : whenTakenIn) {
        filter.taken(request);
      }
      answer = dispatch(request);
    } catch (Throwable thrown) {
      request.failed(thrown);
      answer = failures.of(thrown);
    }
    for (final RequestFilters.AfterResource filter : afterResource) {
      filter.after(request, answer);
    }
    return answer;
  }

  /**
   * Takes down what was held for one request, once its answer is on the wire and not before - a
   * request is logged under the context it was handled with right up to the line that reports the
   * answer, and the context is cleared however the request ended, including when the resource
   * method threw.
   */
  public void answered(RequestContext request) {
    if (!request.takenIn()) {
      return;
    }
    for (final RequestFilters.WhenComplete listener : whenComplete) {
      listener.complete(request.failure());
    }
  }

  private Answer dispatch(RequestContext request) {
    final List<Route> matches = table.matching(request.target());
    if (matches.isEmpty()) {
      return Failures.ofStatus(404);
    }
    final List<MediaType> accepted;
    final MediaType requestType;
    try {
      accepted = Negotiation.accepted(request.header("accept"));
      requestType = Negotiation.requestType(request.header("content-type"));
    } catch (Negotiation.UnreadableAccept refused) {
      // The connector refuses a request whose headers are not what they say they are, and writes
      // nothing in that answer; no resource method ever sees it. It is still an answer this layer
      // gives, so it leaves through the response filters with every other.
      return new Answer(400);
    }
    final String method = request.method();
    if ("OPTIONS".equals(method) && !declares(matches, "OPTIONS")) {
      return options(matches, accepted);
    }
    final String effective = "HEAD".equals(method) ? "GET" : method;
    final List<Route> byMethod = new ArrayList<>();
    for (final Route route : matches) {
      if (route.httpMethod().equals(effective)) {
        byMethod.add(route);
      }
    }
    if (byMethod.isEmpty()) {
      final Answer answer = Failures.ofStatus(405);
      answer.header("Allow", String.join(",", RouteTable.allowed(matches, false)));
      return answer;
    }
    final List<Route> readable = new ArrayList<>();
    for (final Route route : byMethod) {
      if (Negotiation.reads(route.consumes(), typeOfEntity(route, request, requestType))) {
        readable.add(route);
      }
    }
    if (readable.isEmpty()) {
      return Failures.ofStatus(415);
    }
    Route route = null;
    MediaType answerType = null;
    for (final Route candidate : readable) {
      final MediaType chosen = Negotiation.chosen(accepted, candidate.produces());
      if (chosen != null) {
        route = candidate;
        answerType = chosen;
        break;
      }
    }
    if (route == null) {
      return Failures.ofStatus(406);
    }
    return called(request, route, answerType);
  }

  /**
   * The type the request says its entity is. A request that carries an entity without saying what
   * it is is read as bytes, which no method of this service reads; one that carries nothing is not
   * held against the method it was sent to.
   */
  private MediaType typeOfEntity(Route route, RequestContext request, MediaType declared) {
    if (declared != null) {
      return declared;
    }
    if (route.readsEntity() && request.entity() != null && request.entity().length > 0) {
      return MediaType.OCTET_STREAM;
    }
    return null;
  }

  private boolean declares(List<Route> matches, String method) {
    for (final Route route : matches) {
      if (route.httpMethod().equals(method)) {
        return true;
      }
    }
    return false;
  }

  /**
   * What the server answers {@code OPTIONS} with on its own account: the methods the path serves,
   * written out when the caller will take them as text, and nothing but the header when it asked
   * for something else.
   */
  private Answer options(List<Route> matches, List<MediaType> accepted) {
    final Set<String> allowed = RouteTable.allowed(matches, true);
    final Answer answer = new Answer(200);
    answer.header("Allow", String.join(",", allowed));
    if (Negotiation.chosen(accepted, List.of(MediaType.TEXT_PLAIN)) != null) {
      answer.header("Content-Type", "text/plain");
      answer.text(String.join(", ", allowed));
      return answer;
    }
    answer.header("Content-Type", accepted.get(0).withoutParameters());
    return answer;
  }

  /** Runs the filters, the conversion, the constraints and the resource method. */
  private Answer called(RequestContext request, Route route, MediaType answerType) {
    request.route(route);
    final Map<String, String> pathParameters =
        route.template().match(request.target().rawSegments());
    request.pathParameters(pathParameters);
    request.uriInfo(
        new SimpleUriInfo(
            request.baseUri(),
            request.baseUri() + request.target().rawPath(),
            request.target().rawPath(),
            pathParameters,
            request.target().queryParameters(),
            route.resource(),
            route.template().declared()));
    final long started = System.nanoTime();
    Answer answer;
    Throwable failed = null;
    try {
      answer = withFilters(request, route, answerType);
    } catch (Throwable thrown) {
      failed = thrown;
      answer = failures.of(thrown);
    }
    metrics.record(route, answer.status(), failed, System.nanoTime() - started);
    return answer;
  }

  private Answer withFilters(RequestContext request, Route route, MediaType answerType)
      throws Exception {
    for (final RequestFilters.BeforeResource filter : beforeResource) {
      filter.before(request);
      if (request.abortedWith() != null) {
        return request.abortedWith();
      }
    }
    return written(invoke(request, route), answerType, route);
  }

  /** Calls the resource method, waiting for it when it answers later. */
  private Object invoke(RequestContext request, Route route) throws Exception {
    final Object instance;
    if (route.handOver() == null) {
      instance = route.resource();
    } else {
      final Object[] handOverArguments =
          Arguments.of(route.handOverBindings(), request, request.pathParameters(), null);
      final List<Violations.Broken> broken =
          Violations.of(
              route.resource(), route.handOver(), route.handOverBindings(), handOverArguments);
      if (!broken.isEmpty()) {
        return answerFor(broken);
      }
      instance = route.handOver().invoke(route.resource(), handOverArguments);
      metrics.registerLate(route);
    }
    final SimpleAsyncResponse async = new SimpleAsyncResponse();
    final Object[] arguments =
        Arguments.of(route.bindings(), request, request.pathParameters(), async);
    final List<Violations.Broken> broken =
        Violations.of(instance, route.method(), route.bindings(), arguments);
    if (!broken.isEmpty()) {
      return answerFor(broken);
    }
    final Object returned = route.method().invoke(instance, arguments);
    if (route.method().getReturnType() == void.class) {
      final CompletableFuture<Object> answered = async.answered();
      return answered.get();
    }
    return returned;
  }

  private Answer answerFor(List<Violations.Broken> broken) {
    final List<String> messages = new ArrayList<>();
    boolean onTheEntity = false;
    for (final Violations.Broken one : broken) {
      messages.add(one.message());
      onTheEntity |= one.onTheEntity();
    }
    return Failures.violations(messages, onTheEntity);
  }

  /** Turns what a resource method gave back into bytes. */
  private Answer written(Object returned, MediaType answerType, Route route) {
    if (returned instanceof Answer) {
      return (Answer) returned;
    }
    final Answer answer;
    final Object entity;
    if (returned instanceof Response) {
      final Response response = (Response) returned;
      answer = new Answer(response.getStatus());
      entity = response.getEntity();
      for (final Map.Entry<String, List<Object>> header : response.getHeaders().entrySet()) {
        for (final Object value : header.getValue()) {
          answer.addHeader(header.getKey(), String.valueOf(value));
        }
      }
    } else {
      answer = new Answer(returned == null ? 204 : 200);
      entity = returned;
    }
    if (entity == null) {
      // An answer with nothing in it says nothing about a length it does not have, which is how
      // the layer this replaces wrote a method that answered with no entity at all.
      return answer.withholdLength();
    }
    answer.body(
        entity instanceof String
            ? ((String) entity).getBytes(StandardCharsets.UTF_8)
            : entity instanceof byte[] ? (byte[]) entity : Entities.write(entity));
    if (!answer.hasHeader("Content-Type")) {
      answer.header("Content-Type", answerType.toString());
    }
    return answer;
  }

  /** Reads the entity of a request, inflating it when the request says it is compressed. */
  public static byte[] entityOf(byte[] raw, List<String> contentEncodings) throws IOException {
    if (raw == null || raw.length == 0 || !Gzip.declaresGzip(contentEncodings)) {
      return raw == null ? new byte[0] : raw;
    }
    return Gzip.inflate(raw);
  }
}
