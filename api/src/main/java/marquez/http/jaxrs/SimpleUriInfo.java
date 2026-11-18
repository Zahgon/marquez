/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http.jaxrs;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

/**
 * What a resource is told about the request it is answering.
 *
 * <p>This service asks for the address its own answers point back to, the path that was matched and
 * the values taken out of it; anything else the standard interface offers is refused rather than
 * answered with something made up.
 */
public final class SimpleUriInfo implements UriInfo {
  private final String baseUri;
  private final String requestUri;
  private final String path;
  private final MultivaluedMap<String, String> pathParameters = new MultivaluedHashMap<>();
  private final MultivaluedMap<String, String> queryParameters = new MultivaluedHashMap<>();
  private final List<Object> matchedResources = new ArrayList<>();
  private final List<String> matchedTemplates = new ArrayList<>();

  public SimpleUriInfo(
      String baseUri,
      String requestUri,
      String path,
      Map<String, String> pathParameters,
      Map<String, List<String>> queryParameters,
      Object resource,
      String template) {
    this.baseUri = baseUri;
    this.requestUri = requestUri;
    this.path = path;
    pathParameters.forEach(this.pathParameters::putSingle);
    queryParameters.forEach(this.queryParameters::put);
    if (resource != null) {
      this.matchedResources.add(resource);
    }
    if (template != null) {
      this.matchedTemplates.add(template);
    }
  }

  /** The path template the request was matched to, which the logging context records. */
  public String matchedTemplate() {
    return matchedTemplates.isEmpty() ? "" : matchedTemplates.get(0);
  }

  @Override
  public String getPath() {
    return path;
  }

  @Override
  public String getPath(boolean decode) {
    return path;
  }

  @Override
  public List<PathSegment> getPathSegments() {
    throw unsupported();
  }

  @Override
  public List<PathSegment> getPathSegments(boolean decode) {
    throw unsupported();
  }

  @Override
  public URI getRequestUri() {
    return URI.create(requestUri);
  }

  @Override
  public UriBuilder getRequestUriBuilder() {
    return new SimpleUriBuilder().uri(URI.create(requestUri));
  }

  @Override
  public URI getAbsolutePath() {
    return URI.create(requestUri);
  }

  @Override
  public UriBuilder getAbsolutePathBuilder() {
    return getRequestUriBuilder();
  }

  @Override
  public URI getBaseUri() {
    return URI.create(baseUri);
  }

  @Override
  public UriBuilder getBaseUriBuilder() {
    return new SimpleUriBuilder().uri(URI.create(baseUri));
  }

  @Override
  public MultivaluedMap<String, String> getPathParameters() {
    return pathParameters;
  }

  @Override
  public MultivaluedMap<String, String> getPathParameters(boolean decode) {
    return pathParameters;
  }

  @Override
  public MultivaluedMap<String, String> getQueryParameters() {
    return queryParameters;
  }

  @Override
  public MultivaluedMap<String, String> getQueryParameters(boolean decode) {
    return queryParameters;
  }

  @Override
  public List<String> getMatchedURIs() {
    return matchedTemplates;
  }

  @Override
  public List<String> getMatchedURIs(boolean decode) {
    return matchedTemplates;
  }

  @Override
  public List<Object> getMatchedResources() {
    return matchedResources;
  }

  @Override
  public URI resolve(URI uri) {
    return getBaseUri().resolve(uri);
  }

  @Override
  public URI relativize(URI uri) {
    return getBaseUri().relativize(uri);
  }

  private static UnsupportedOperationException unsupported() {
    return new UnsupportedOperationException("this service does not ask for that");
  }
}
