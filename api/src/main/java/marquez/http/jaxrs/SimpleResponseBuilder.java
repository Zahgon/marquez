/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http.jaxrs;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Variant;

/** Builds the answer a resource method returns. */
public final class SimpleResponseBuilder extends Response.ResponseBuilder {
  private int status = 200;
  private Object entity;
  private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

  @Override
  public Response build() {
    final MultivaluedMap<String, Object> copy = new MultivaluedHashMap<>();
    copy.putAll(headers);
    return new SimpleResponse(status, entity, copy);
  }

  @Override
  public Response.ResponseBuilder clone() {
    final SimpleResponseBuilder copy = new SimpleResponseBuilder();
    copy.status = status;
    copy.entity = entity;
    copy.headers.putAll(headers);
    return copy;
  }

  @Override
  public Response.ResponseBuilder status(int code) {
    this.status = code;
    return this;
  }

  @Override
  public Response.ResponseBuilder status(int code, String reasonPhrase) {
    this.status = code;
    return this;
  }

  @Override
  public Response.ResponseBuilder entity(Object value) {
    this.entity = value;
    return this;
  }

  @Override
  public Response.ResponseBuilder entity(Object value, Annotation[] annotations) {
    this.entity = value;
    return this;
  }

  @Override
  public Response.ResponseBuilder allow(String... methods) {
    headers.remove("Allow");
    if (methods != null) {
      for (final String method : methods) {
        headers.add("Allow", method);
      }
    }
    return this;
  }

  @Override
  public Response.ResponseBuilder allow(Set<String> methods) {
    return allow(methods == null ? null : methods.toArray(new String[0]));
  }

  @Override
  public Response.ResponseBuilder cacheControl(CacheControl cacheControl) {
    return replace("Cache-Control", cacheControl);
  }

  @Override
  public Response.ResponseBuilder encoding(String encoding) {
    return replace("Content-Encoding", encoding);
  }

  @Override
  public Response.ResponseBuilder header(String name, Object value) {
    if (value == null) {
      headers.remove(name);
    } else {
      headers.add(name, value);
    }
    return this;
  }

  @Override
  public Response.ResponseBuilder replaceAll(MultivaluedMap<String, Object> newHeaders) {
    headers.clear();
    if (newHeaders != null) {
      headers.putAll(newHeaders);
    }
    return this;
  }

  @Override
  public Response.ResponseBuilder language(String language) {
    return replace("Content-Language", language);
  }

  @Override
  public Response.ResponseBuilder language(Locale language) {
    return replace("Content-Language", language);
  }

  @Override
  public Response.ResponseBuilder type(MediaType type) {
    return replace("Content-Type", type);
  }

  @Override
  public Response.ResponseBuilder type(String type) {
    return replace("Content-Type", type);
  }

  @Override
  public Response.ResponseBuilder variant(Variant variant) {
    if (variant == null) {
      return replace("Content-Type", null);
    }
    return type(variant.getMediaType());
  }

  @Override
  public Response.ResponseBuilder contentLocation(URI location) {
    return replace("Content-Location", location);
  }

  @Override
  public Response.ResponseBuilder cookie(NewCookie... cookies) {
    if (cookies == null) {
      headers.remove("Set-Cookie");
      return this;
    }
    for (final NewCookie cookie : cookies) {
      headers.add("Set-Cookie", cookie);
    }
    return this;
  }

  @Override
  public Response.ResponseBuilder expires(Date expires) {
    return replace("Expires", expires);
  }

  @Override
  public Response.ResponseBuilder lastModified(Date lastModified) {
    return replace("Last-Modified", lastModified);
  }

  @Override
  public Response.ResponseBuilder location(URI location) {
    return replace("Location", location);
  }

  @Override
  public Response.ResponseBuilder tag(EntityTag tag) {
    return replace("ETag", tag);
  }

  @Override
  public Response.ResponseBuilder tag(String tag) {
    return replace("ETag", tag == null ? null : new EntityTag(tag));
  }

  @Override
  public Response.ResponseBuilder variants(Variant... variants) {
    return this;
  }

  @Override
  public Response.ResponseBuilder variants(java.util.List<Variant> variants) {
    return this;
  }

  @Override
  public Response.ResponseBuilder links(Link... links) {
    headers.remove("Link");
    if (links != null) {
      for (final Link link : links) {
        headers.add("Link", link);
      }
    }
    return this;
  }

  @Override
  public Response.ResponseBuilder link(URI uri, String relation) {
    headers.add("Link", Link.fromUri(uri).rel(relation).build());
    return this;
  }

  @Override
  public Response.ResponseBuilder link(String uri, String relation) {
    return link(URI.create(uri), relation);
  }

  private Response.ResponseBuilder replace(String name, Object value) {
    if (value == null) {
      headers.remove(name);
    } else {
      headers.putSingle(name, value);
    }
    return this;
  }
}
