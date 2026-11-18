/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

/**
 * The filters the application wraps a request with, in the order it registers them.
 *
 * <p>The runtime that used to run them is gone, so the contracts they are written against live here
 * - one for each place the old wiring had a filter:
 *
 * <ul>
 *   <li>{@link AroundRequest}, where the servlet filter mapped to every path sat: it sees every
 *       request the connector takes, whatever answers it;
 *   <li>{@link WhenTakenIn}, where the API layer takes a request in, before it has been matched to
 *       anything, so that what a filter sets up is in place for every answer the layer gives;
 *   <li>{@link BeforeResource}, once the request has been matched, which is where a filter that
 *       needs the matched path may answer the request itself;
 *   <li>{@link AfterResource}, once the answer is known, whichever stage of the path decided it;
 *   <li>{@link WhenComplete}, once that answer has been written, which is where the state held for
 *       the request is taken down.
 * </ul>
 */
public final class RequestFilters {
  private RequestFilters() {}

  /**
   * Wraps every request a connector takes, matched or not and whether or not it is the API that
   * answers it, the way a servlet filter mapped to {@code /*} wrapped the whole chain: opened
   * before anything looks at the request, closed once its answer has been written, however it
   * ended.
   */
  public interface AroundRequest {
    void opened(RequestContext request);

    void closed(RequestContext request, Throwable failure);
  }

  /**
   * Runs for every request the API layer takes in, before the route table is consulted. A request
   * that is answered before it is ever matched - because no route has its target, no route has its
   * method, or its media types cannot be met - has been through these filters all the same.
   */
  public interface WhenTakenIn {
    void taken(RequestContext request);
  }

  /**
   * Runs once the request has been matched to a resource method, before that method is called; may
   * answer the request itself and stop the chain.
   */
  public interface BeforeResource {
    void before(RequestContext request);
  }

  /** Runs once the answer is known, whichever stage of the path decided it, and may change it. */
  public interface AfterResource {
    void after(RequestContext request, Answer answer);
  }

  /** Runs once the answer has been written, whether the request was answered or threw. */
  public interface WhenComplete {
    void complete(Throwable failure);
  }
}
