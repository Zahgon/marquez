/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http.jaxrs;

import java.lang.annotation.Annotation;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;

/**
 * The answer a resource method builds: a status, an entity and the headers set on it.
 *
 * <p>The resources build their answers with the standard interface, and this is what that interface
 * now hands them: a value object the serving layer reads, instead of a runtime that also writes it.
 * Nothing here reads or writes the network.
 */
public final class SimpleResponse extends Response {
  private final int status;
  private final Object entity;
  private final MultivaluedMap<String, Object> headers;

  SimpleResponse(int status, Object entity, MultivaluedMap<String, Object> headers) {
    this.status = status;
    this.entity = entity;
    this.headers = headers;
  }

  @Override
  public int getStatus() {
    return status;
  }

  @Override
  public StatusType getStatusInfo() {
    final Status known = Status.fromStatusCode(status);
    if (known != null) {
      return known;
    }
    return new StatusType() {
      @Override
      public int getStatusCode() {
        return status;
      }

      @Override
      public Status.Family getFamily() {
        return Status.Family.familyOf(status);
      }

      @Override
      public String getReasonPhrase() {
        return marquez.http.Reasons.of(status);
      }
    };
  }

  @Override
  public Object getEntity() {
    return entity;
  }

  @Override
  public <T> T readEntity(Class<T> type) {
    return type.cast(entity);
  }

  @Override
  public <T> T readEntity(GenericType<T> type) {
    throw new UnsupportedOperationException("the serving layer reads the entity itself");
  }

  @Override
  public <T> T readEntity(Class<T> type, Annotation[] annotations) {
    return type.cast(entity);
  }

  @Override
  public <T> T readEntity(GenericType<T> type, Annotation[] annotations) {
    throw new UnsupportedOperationException("the serving layer reads the entity itself");
  }

  @Override
  public boolean hasEntity() {
    return entity != null;
  }

  @Override
  public boolean bufferEntity() {
    return false;
  }

  @Override
  public void close() {
    // Nothing is held open: the entity is already in memory.
  }

  @Override
  public MediaType getMediaType() {
    final Object type = headers.getFirst("Content-Type");
    if (type instanceof MediaType) {
      return (MediaType) type;
    }
    return type == null ? null : MediaType.valueOf(type.toString());
  }

  @Override
  public Locale getLanguage() {
    return null;
  }

  @Override
  public int getLength() {
    return -1;
  }

  @Override
  public Set<String> getAllowedMethods() {
    return new HashSet<>();
  }

  @Override
  public Map<String, NewCookie> getCookies() {
    return Map.of();
  }

  @Override
  public EntityTag getEntityTag() {
    return null;
  }

  @Override
  public Date getDate() {
    return null;
  }

  @Override
  public Date getLastModified() {
    return null;
  }

  @Override
  public java.net.URI getLocation() {
    final Object location = headers.getFirst("Location");
    return location == null ? null : java.net.URI.create(location.toString());
  }

  @Override
  public Set<Link> getLinks() {
    return new HashSet<>();
  }

  @Override
  public boolean hasLink(String relation) {
    return false;
  }

  @Override
  public Link getLink(String relation) {
    return null;
  }

  @Override
  public Link.Builder getLinkBuilder(String relation) {
    return null;
  }

  @Override
  public MultivaluedMap<String, Object> getMetadata() {
    return headers;
  }

  @Override
  public MultivaluedMap<String, String> getStringHeaders() {
    final MultivaluedMap<String, String> asText = new MultivaluedHashMap<>();
    headers.forEach(
        (name, values) -> values.forEach(value -> asText.add(name, String.valueOf(value))));
    return asText;
  }

  @Override
  public String getHeaderString(String name) {
    final java.util.List<String> values = getStringHeaders().get(name);
    return values == null ? null : String.join(",", values);
  }
}
