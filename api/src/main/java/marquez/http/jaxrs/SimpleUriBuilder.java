/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http.jaxrs;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.ws.rs.core.UriBuilder;
import marquez.http.Percent;

/**
 * Builds the locations the service answers with.
 *
 * <p>The service asks for one thing only: the address of a run, built from the paths its resource
 * classes declare. Everything the standard interface offers beyond that is refused rather than half
 * implemented, so that nothing silently builds a wrong address.
 */
public final class SimpleUriBuilder extends UriBuilder {
  private String scheme = "http";
  private String host = "localhost";
  private int port = -1;
  private String path = "";

  public SimpleUriBuilder() {}

  private SimpleUriBuilder(String scheme, String host, int port, String path) {
    this.scheme = scheme;
    this.host = host;
    this.port = port;
    this.path = path;
  }

  @Override
  public UriBuilder clone() {
    return new SimpleUriBuilder(scheme, host, port, path);
  }

  @Override
  public UriBuilder uri(URI uri) {
    if (uri.getScheme() != null) {
      scheme = uri.getScheme();
    }
    if (uri.getHost() != null) {
      host = uri.getHost();
    }
    if (uri.getPort() != -1) {
      port = uri.getPort();
    }
    if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
      final String raw = trimTrailing(uri.getRawPath());
      path = "/".equals(raw) ? "" : raw;
    }
    return this;
  }

  @Override
  public UriBuilder uri(String uriTemplate) {
    return uri(URI.create(uriTemplate));
  }

  @Override
  public UriBuilder scheme(String scheme) {
    this.scheme = scheme;
    return this;
  }

  @Override
  public UriBuilder schemeSpecificPart(String ssp) {
    throw unsupported();
  }

  @Override
  public UriBuilder userInfo(String ui) {
    throw unsupported();
  }

  @Override
  public UriBuilder host(String host) {
    this.host = host;
    return this;
  }

  @Override
  public UriBuilder port(int port) {
    this.port = port;
    return this;
  }

  @Override
  public UriBuilder replacePath(String path) {
    this.path = path == null ? "" : trimTrailing(path);
    return this;
  }

  @Override
  public UriBuilder path(String piece) {
    if (piece == null || piece.isEmpty()) {
      return this;
    }
    path = path + (piece.startsWith("/") || path.endsWith("/") ? "" : "/") + piece;
    return this;
  }

  @Override
  public UriBuilder path(Class resource) {
    final javax.ws.rs.Path declared =
        (javax.ws.rs.Path) resource.getAnnotation(javax.ws.rs.Path.class);
    if (declared == null) {
      throw new IllegalArgumentException(resource.getName() + " declares no path");
    }
    return path(declared.value());
  }

  @Override
  public UriBuilder path(Class resource, String methodName) {
    for (final Method method : resource.getMethods()) {
      if (method.getName().equals(methodName)) {
        final javax.ws.rs.Path declared = method.getAnnotation(javax.ws.rs.Path.class);
        return declared == null ? this : path(declared.value());
      }
    }
    throw new IllegalArgumentException(resource.getName() + " has no method " + methodName);
  }

  @Override
  public UriBuilder path(Method method) {
    final javax.ws.rs.Path declared = method.getAnnotation(javax.ws.rs.Path.class);
    return declared == null ? this : path(declared.value());
  }

  @Override
  public UriBuilder segment(String... segments) {
    for (final String segment : segments) {
      path(segment);
    }
    return this;
  }

  @Override
  public UriBuilder replaceMatrix(String matrix) {
    throw unsupported();
  }

  @Override
  public UriBuilder matrixParam(String name, Object... values) {
    throw unsupported();
  }

  @Override
  public UriBuilder replaceMatrixParam(String name, Object... values) {
    throw unsupported();
  }

  @Override
  public UriBuilder replaceQuery(String query) {
    throw unsupported();
  }

  @Override
  public UriBuilder queryParam(String name, Object... values) {
    throw unsupported();
  }

  @Override
  public UriBuilder replaceQueryParam(String name, Object... values) {
    throw unsupported();
  }

  @Override
  public UriBuilder fragment(String fragment) {
    throw unsupported();
  }

  @Override
  public UriBuilder resolveTemplate(String name, Object value) {
    throw unsupported();
  }

  @Override
  public UriBuilder resolveTemplate(String name, Object value, boolean encodeSlashInPath) {
    throw unsupported();
  }

  @Override
  public UriBuilder resolveTemplateFromEncoded(String name, Object value) {
    throw unsupported();
  }

  @Override
  public UriBuilder resolveTemplates(Map<String, Object> templateValues) {
    throw unsupported();
  }

  @Override
  public UriBuilder resolveTemplates(Map<String, Object> templateValues, boolean encode) {
    throw unsupported();
  }

  @Override
  public UriBuilder resolveTemplatesFromEncoded(Map<String, Object> templateValues) {
    throw unsupported();
  }

  @Override
  public URI buildFromMap(Map<String, ?> values) {
    return build(filled(values, true));
  }

  @Override
  public URI buildFromMap(Map<String, ?> values, boolean encodeSlashInPath) {
    return build(filled(values, true));
  }

  @Override
  public URI buildFromEncodedMap(Map<String, ?> values) {
    return build(filled(values, false));
  }

  @Override
  public URI build(Object... values) {
    return build(values, true);
  }

  @Override
  public URI build(Object[] values, boolean encodeSlashInPath) {
    final StringBuilder out = new StringBuilder();
    int next = 0;
    for (int at = 0; at < path.length(); at++) {
      final char c = path.charAt(at);
      if (c != '{') {
        out.append(c);
        continue;
      }
      final int close = path.indexOf('}', at);
      if (close < 0) {
        out.append(c);
        continue;
      }
      final Object value = next < values.length ? values[next++] : "";
      out.append(
          encodeSlashInPath ? Percent.encodeSegment(String.valueOf(value)) : String.valueOf(value));
      at = close;
    }
    final String authority = port < 0 ? host : host + ":" + port;
    return URI.create(scheme + "://" + authority + out);
  }

  @Override
  public URI buildFromEncoded(Object... values) {
    return build(values, false);
  }

  @Override
  public String toTemplate() {
    return path;
  }

  /** The values named by the templates in the path, in the order they appear. */
  private Object[] filled(Map<String, ?> values, boolean encode) {
    final List<Object> ordered = new ArrayList<>();
    for (int at = 0; at < path.length(); at++) {
      if (path.charAt(at) != '{') {
        continue;
      }
      final int close = path.indexOf('}', at);
      if (close < 0) {
        break;
      }
      String name = path.substring(at + 1, close);
      final int colon = name.indexOf(':');
      if (colon >= 0) {
        name = name.substring(0, colon);
      }
      final Object value = values.get(name.trim());
      ordered.add(value == null ? "" : value);
      at = close;
    }
    return ordered.toArray();
  }

  private static String trimTrailing(String value) {
    String out = value;
    while (out.length() > 1 && out.endsWith("/")) {
      out = out.substring(0, out.length() - 1);
    }
    return out;
  }

  private static UnsupportedOperationException unsupported() {
    return new UnsupportedOperationException("this service builds no such address");
  }
}
