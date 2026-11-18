/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http.jaxrs;

import javax.ws.rs.core.Application;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.Variant;
import javax.ws.rs.ext.RuntimeDelegate;

/**
 * What the standard response interface asks for when a resource builds its answer.
 *
 * <p>The resources keep building their answers the way they always did. The runtime that used to
 * stand behind that interface, and that also matched, converted and wrote, is gone; what is left is
 * this, which only makes the value objects the serving layer then reads. Anything the resources of
 * this service never ask for is refused loudly rather than half implemented.
 */
public final class MarquezRuntimeDelegate extends RuntimeDelegate {
  @Override
  public UriBuilder createUriBuilder() {
    return new SimpleUriBuilder();
  }

  @Override
  public Response.ResponseBuilder createResponseBuilder() {
    return new SimpleResponseBuilder();
  }

  @Override
  public Variant.VariantListBuilder createVariantListBuilder() {
    throw new UnsupportedOperationException("this service does not negotiate variants");
  }

  @Override
  public <T> T createEndpoint(Application application, Class<T> endpointType) {
    throw new UnsupportedOperationException("the serving layer is not built from an Application");
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> HeaderDelegate<T> createHeaderDelegate(Class<T> type) {
    if (MediaType.class.equals(type)) {
      return (HeaderDelegate<T>) new MediaTypeHeaderDelegate();
    }
    return new ToStringHeaderDelegate<>(type);
  }

  @Override
  public Link.Builder createLinkBuilder() {
    throw new UnsupportedOperationException("this service does not answer with links");
  }

  /** Reads and writes a media type header. */
  static final class MediaTypeHeaderDelegate implements HeaderDelegate<MediaType> {
    @Override
    public MediaType fromString(String value) {
      final marquez.http.MediaType parsed = marquez.http.MediaType.parse(value);
      if (parsed == null) {
        throw new IllegalArgumentException("not a media type: " + value);
      }
      return new MediaType(parsed.type(), parsed.subtype(), parsed.parameters());
    }

    @Override
    public String toString(MediaType value) {
      final StringBuilder out = new StringBuilder(value.getType() + "/" + value.getSubtype());
      value
          .getParameters()
          .forEach((name, parameter) -> out.append(';').append(name).append('=').append(parameter));
      return out.toString();
    }
  }

  /** A header this service only ever writes, and writes as the value's own text. */
  static final class ToStringHeaderDelegate<T> implements HeaderDelegate<T> {
    private final Class<T> type;

    ToStringHeaderDelegate(Class<T> type) {
      this.type = type;
    }

    @Override
    public T fromString(String value) {
      throw new UnsupportedOperationException("this service does not read a " + type.getName());
    }

    @Override
    public String toString(T value) {
      return String.valueOf(value);
    }
  }
}
