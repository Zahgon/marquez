/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.ws.rs.core.UriInfo;

/**
 * Everything known about one request while it is being handled: what arrived, what it was matched
 * to, and the answer a filter decided on before the resource ever ran.
 *
 * <p>This is what the filters are handed, in place of the runtime's own request context.
 */
public final class RequestContext {
  private final String method;
  private final RequestTarget target;
  private final Map<String, List<String>> headers;
  private final String baseUri;
  private byte[] entity = new byte[0];
  private Route route;
  private Map<String, String> pathParameters = new LinkedHashMap<>();
  private UriInfo uriInfo;
  private Answer abortedWith;
  private boolean takenIn;
  private Throwable failure;

  public RequestContext(
      String method, RequestTarget target, Map<String, List<String>> headers, String baseUri) {
    this.method = method;
    this.target = target;
    this.headers = headers;
    this.baseUri = baseUri;
  }

  public String method() {
    return method;
  }

  public RequestTarget target() {
    return target;
  }

  public String baseUri() {
    return baseUri;
  }

  /** The values of one header, in the order they arrived; empty when it did not arrive. */
  public List<String> header(String name) {
    final List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
    return values == null ? new ArrayList<>() : values;
  }

  /** The first value of one header, as a parameter declared against a header takes it. */
  public String firstHeader(String name) {
    final List<String> values = header(name);
    return values.isEmpty() ? null : values.get(0);
  }

  public Map<String, List<String>> headers() {
    return headers;
  }

  public byte[] entity() {
    return entity;
  }

  public void entity(byte[] entity) {
    this.entity = entity;
  }

  public Route route() {
    return route;
  }

  public void route(Route route) {
    this.route = route;
  }

  public Map<String, String> pathParameters() {
    return pathParameters;
  }

  public void pathParameters(Map<String, String> pathParameters) {
    this.pathParameters = pathParameters;
  }

  public UriInfo uriInfo() {
    return uriInfo;
  }

  public void uriInfo(UriInfo uriInfo) {
    this.uriInfo = uriInfo;
  }

  /** The answer a filter decided on, which stops the chain before the resource method runs. */
  public Answer abortedWith() {
    return abortedWith;
  }

  public void abortWith(Answer answer) {
    this.abortedWith = answer;
  }

  /**
   * Whether the API layer took this request in. What that layer sets up for a request it took in
   * has to be taken down once the answer has been written, and the connector serves more than that
   * layer alone.
   */
  public boolean takenIn() {
    return takenIn;
  }

  public void takenIn(boolean takenIn) {
    this.takenIn = takenIn;
  }

  /** What the request failed with, for the filters that are told how it ended. */
  public Throwable failure() {
    return failure;
  }

  public void failed(Throwable failure) {
    this.failure = failure;
  }
}
