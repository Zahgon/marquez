/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.UriInfo;

/**
 * The values a resource method is called with, taken from the request.
 *
 * <p>Every parameter is filled from where it says it comes from, converted to the type the method
 * declares and, when it declares one, falling back to its default. The entity is read last, with
 * the mapper the rest of the service uses.
 */
public final class Arguments {
  private Arguments() {}

  /** Builds the values for one list of parameters. */
  public static Object[] of(
      List<ParamBinding> bindings,
      RequestContext request,
      Map<String, String> pathParameters,
      AsyncResponse asyncResponse)
      throws IOException {
    final Object[] arguments = new Object[bindings.size()];
    for (int at = 0; at < bindings.size(); at++) {
      arguments[at] = one(bindings.get(at), request, pathParameters, asyncResponse);
    }
    return arguments;
  }

  private static Object one(
      ParamBinding binding,
      RequestContext request,
      Map<String, String> pathParameters,
      AsyncResponse asyncResponse)
      throws IOException {
    switch (binding.kind()) {
      case PATH:
        return Conversion.convert(binding, values(pathParameters.get(binding.name())));
      case QUERY:
        return Conversion.convert(
            binding, request.target().queryParameters().getOrDefault(binding.name(), List.of()));
      case HEADER:
        return Conversion.convert(binding, request.header(binding.name()));
      case COOKIE:
        return Conversion.convert(binding, values(cookie(request, binding.name())));
      case CONTEXT:
        return context(binding, request);
      case SUSPENDED:
        return asyncResponse;
      case ENTITY:
      default:
        return entity(binding, request);
    }
  }

  private static List<String> values(String value) {
    return value == null ? List.of() : List.of(value);
  }

  private static String cookie(RequestContext request, String name) {
    for (final String header : request.header("cookie")) {
      for (final String pair : header.split(";")) {
        final int equals = pair.indexOf('=');
        if (equals > 0 && pair.substring(0, equals).trim().equals(name)) {
          return pair.substring(equals + 1).trim();
        }
      }
    }
    return null;
  }

  /** The few things the service asks the serving layer itself for. */
  private static Object context(ParamBinding binding, RequestContext request) {
    if (UriInfo.class.isAssignableFrom(binding.type())) {
      return request.uriInfo();
    }
    return null;
  }

  private static Object entity(ParamBinding binding, RequestContext request) throws IOException {
    final byte[] body = request.entity();
    if (body == null || isBlank(body)) {
      // Nothing but space is no entity at all, and the method is called without one.
      return null;
    }
    return Entities.read(body, binding.genericType());
  }

  private static boolean isBlank(byte[] body) {
    for (final byte b : body) {
      if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
        return false;
      }
    }
    return true;
  }

  /** The names of the parameters a list of bindings takes from the path. */
  public static List<String> pathNames(List<ParamBinding> bindings) {
    final List<String> names = new ArrayList<>();
    for (final ParamBinding binding : bindings) {
      if (binding.kind() == ParamBinding.Kind.PATH) {
        names.add(binding.name());
      }
    }
    return names;
  }
}
