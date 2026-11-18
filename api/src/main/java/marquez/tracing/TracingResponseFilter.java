/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.tracing;

import io.sentry.Sentry;
import marquez.http.Answer;
import marquez.http.RequestContext;
import marquez.http.RequestFilters;

/**
 * Names the transaction a request was reported under after the template it was matched to, which is
 * what the response filter this replaces did. It runs for every answer the API layer gives,
 * including the ones no resource method produced, and names those after the method alone.
 */
public class TracingResponseFilter implements RequestFilters.AfterResource {
  @Override
  public void after(RequestContext request, Answer answer) {
    final String path = request.route() == null ? "" : request.route().template().declared();
    Sentry.setTransaction(request.method() + " " + path);
  }
}
