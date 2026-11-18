/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.tracing;

import io.sentry.ITransaction;
import io.sentry.Sentry;
import marquez.http.RequestContext;
import marquez.http.RequestFilters;

/**
 * Reports every request as a transaction, which is what the servlet filter this replaces did from
 * its place in front of the whole chain: the transaction is started before anything looks at the
 * request and finished once its answer has been written, whatever answered it and however it ended.
 */
public class TracingRequestFilter implements RequestFilters.AroundRequest {
  private static final ThreadLocal<ITransaction> CURRENT = new ThreadLocal<>();

  @Override
  public void opened(RequestContext request) {
    final ITransaction transaction = transaction(request);
    CURRENT.set(transaction);
    Sentry.configureScope(scope -> scope.setTransaction(transaction));
  }

  @Override
  public void closed(RequestContext request, Throwable failure) {
    final ITransaction transaction = CURRENT.get();
    if (transaction != null) {
      transaction.finish();
      CURRENT.remove();
    }
  }

  private ITransaction transaction(RequestContext request) {
    final String protocol = "HTTP/1.1";
    final String transactionName = protocol + " " + request.method();
    final String taskName = request.method() + " " + request.target().rawPath();
    final String query = request.target().query();
    final String description =
        (query == null ? "" : query + "\n") + "Input size: " + request.entity().length;
    return Sentry.startTransaction(transactionName, taskName, description);
  }
}
