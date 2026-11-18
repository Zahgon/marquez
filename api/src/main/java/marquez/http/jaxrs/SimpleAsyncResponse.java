/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http.jaxrs;

import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.CompletionCallback;
import javax.ws.rs.container.ConnectionCallback;
import javax.ws.rs.container.TimeoutHandler;

/**
 * The handle a resource method keeps when it answers later.
 *
 * <p>One method of this service takes work off the request thread and answers when that work is
 * done. This is what it resumes: the dispatcher waits on it and writes whatever it is given,
 * whether that is an answer or a failure.
 */
public final class SimpleAsyncResponse implements AsyncResponse {
  private final CompletableFuture<Object> answered = new CompletableFuture<>();

  /** Completes when the resource method has answered. */
  public CompletableFuture<Object> answered() {
    return answered;
  }

  @Override
  public boolean resume(Object response) {
    return answered.complete(response);
  }

  @Override
  public boolean resume(Throwable response) {
    return answered.completeExceptionally(response);
  }

  @Override
  public boolean cancel() {
    return answered.cancel(true);
  }

  @Override
  public boolean cancel(int retryAfter) {
    return cancel();
  }

  @Override
  public boolean cancel(Date retryAfter) {
    return cancel();
  }

  @Override
  public boolean isSuspended() {
    return !answered.isDone();
  }

  @Override
  public boolean isCancelled() {
    return answered.isCancelled();
  }

  @Override
  public boolean isDone() {
    return answered.isDone();
  }

  @Override
  public boolean setTimeout(long time, TimeUnit unit) {
    return false;
  }

  @Override
  public void setTimeoutHandler(TimeoutHandler handler) {
    // The dispatcher waits for the answer; there is no timeout of its own.
  }

  @Override
  public Collection<Class<?>> register(Class<?> callback) {
    throw new UnsupportedOperationException("this service registers no callbacks here");
  }

  @Override
  public Map<Class<?>, Collection<Class<?>>> register(Class<?> callback, Class<?>... callbacks) {
    throw new UnsupportedOperationException("this service registers no callbacks here");
  }

  @Override
  public Collection<Class<?>> register(Object callback) {
    throw new UnsupportedOperationException("this service registers no callbacks here");
  }

  @Override
  public Map<Class<?>, Collection<Class<?>>> register(Object callback, Object... callbacks) {
    throw new UnsupportedOperationException("this service registers no callbacks here");
  }

  /** Kept so that the standard interface is complete; this service never uses either. */
  @SuppressWarnings("unused")
  private void unusedCallbacks(CompletionCallback completion, ConnectionCallback connection) {}
}
