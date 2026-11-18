/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.logging;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import marquez.http.Answer;
import marquez.http.RequestContext;
import marquez.http.RequestFilters;
import org.slf4j.MDC;

/**
 * Puts request ID, method, and path to the MDC context so that this information is available from
 * logs throughout the request context. Request ID is randomly generated UUID, which can be used to
 * group by to see the logs for a particular request.
 *
 * <p>The context is put up for every request the API layer takes in, before anything is known about
 * what will answer it, so that a request answered without reaching a resource method is logged
 * under a context of its own too; the one thing that cannot be known that early, the template the
 * request was matched to, is added once it has been. It is taken down when the request is over and
 * its answer written - in {@link #complete(Throwable)}, which the layer calls however the request
 * ended, so nothing is left behind on the thread when a resource method throws.
 */
@Slf4j
public class LoggingMdcFilter
    implements RequestFilters.WhenTakenIn,
        RequestFilters.BeforeResource,
        RequestFilters.AfterResource,
        RequestFilters.WhenComplete {

  private static final String REQUEST_ID = "requestID";
  private static final String METHOD = "method";
  private static final String PATH = "path";
  private static final String PATH_WITH_PARAMETERS = "pathWithParams";

  @Override
  public void complete(Throwable throwable) {
    MDC.remove(REQUEST_ID);
    MDC.remove(METHOD);
    MDC.remove(PATH);
    MDC.remove(PATH_WITH_PARAMETERS);
  }

  @Override
  public void taken(RequestContext request) {
    MDC.put(REQUEST_ID, UUID.randomUUID().toString());
    MDC.put(METHOD, request.method());
    MDC.put(PATH, request.target().rawPath());
    // Nothing has been matched yet; a request that is answered before it ever is keeps this empty.
    MDC.put(PATH_WITH_PARAMETERS, "");
  }

  @Override
  public void before(RequestContext request) {
    MDC.put(
        PATH_WITH_PARAMETERS, request.route() == null ? "" : request.route().template().declared());
  }

  @Override
  public void after(RequestContext request, Answer answer) {
    if (answer.status() >= 500) {
      log.error("status: {}", answer.status());
    } else if (answer.status() >= 400) {
      log.warn("status: {}", answer.status());
    } else {
      log.info("status: {}", answer.status());
    }
  }
}
