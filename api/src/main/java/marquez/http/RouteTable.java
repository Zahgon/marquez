/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.ws.rs.Consumes;
import javax.ws.rs.CookieParam;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;

/**
 * The table of routes the service serves, read once from the resources themselves.
 *
 * <p>The resources keep their own declarations: this reads the path, the request method, the media
 * types each method offers and reads, and where each of its parameters takes its value from, and
 * builds the table the dispatcher matches against. A method that only declares a path and hands the
 * rest of it to another object is followed, so the methods of that object are in the table too.
 *
 * <p>Candidates are ordered the way the runtime this replaces ordered them - the most literal path
 * first - so that a literal segment is preferred over a parameter that would also match it.
 */
public final class RouteTable {
  private final List<Route> routes;

  private RouteTable(List<Route> routes) {
    this.routes = routes;
  }

  /** Reads the table from the resource instances the application registers. */
  public static RouteTable of(Iterable<Object> resources) {
    final List<Route> routes = new ArrayList<>();
    for (final Object resource : resources) {
      final Class<?> type = resource.getClass();
      final Path classPath = type.getAnnotation(Path.class);
      if (classPath == null) {
        continue;
      }
      read(routes, resource, type, classPath.value(), null, null, "");
    }
    routes.sort(
        Comparator.comparingInt((Route route) -> route.template().literalCharacters())
            .reversed()
            .thenComparing(Comparator.comparingInt((Route route) -> route.template().parameters()))
            .thenComparing(route -> route.template().declared())
            .thenComparing(Route::httpMethod));
    return new RouteTable(routes);
  }

  /**
   * Reads the request methods of one class under a prefix. When {@code handOver} is given, the
   * methods belong to an object the root resource builds for the request.
   */
  private static void read(
      List<Route> routes,
      Object resource,
      Class<?> type,
      String prefix,
      Method handOver,
      List<ParamBinding> handOverBindings,
      String handOverPath) {
    final List<MediaType> classProduces = mediaTypes(type.getAnnotation(Produces.class));
    final List<MediaType> classConsumes = mediaTypes(type.getAnnotation(Consumes.class));
    for (final Method method : type.getMethods()) {
      final Path methodPath = method.getAnnotation(Path.class);
      final String httpMethod = requestMethodOf(method);
      if (httpMethod == null) {
        if (handOver == null && methodPath != null && handsOver(method)) {
          read(
              routes,
              resource,
              method.getReturnType(),
              prefix,
              method,
              bindingsOf(method),
              methodPath.value());
        }
        continue;
      }
      final List<MediaType> produces = mediaTypes(method.getAnnotation(Produces.class));
      final List<MediaType> consumes = mediaTypes(method.getAnnotation(Consumes.class));
      final String tail = join(handOverPath, methodPath == null ? "" : methodPath.value());
      routes.add(
          new Route(
              httpMethod,
              PathTemplate.of(prefix, tail),
              resource,
              method,
              handOver,
              handOverBindings,
              produces.isEmpty() ? classProduces : produces,
              consumes.isEmpty() ? classConsumes : consumes,
              bindingsOf(method)));
    }
  }

  private static String join(String first, String second) {
    if (first == null || first.isEmpty()) {
      return second;
    }
    if (second == null || second.isEmpty()) {
      return first;
    }
    return first + (second.startsWith("/") ? "" : "/") + second;
  }

  /** Whether a method with a path and no request method hands that path to another object. */
  private static boolean handsOver(Method method) {
    final Class<?> returned = method.getReturnType();
    if (returned == void.class
        || returned.isPrimitive()
        || returned.getName().startsWith("java.")) {
      return false;
    }
    for (final Method candidate : returned.getMethods()) {
      if (requestMethodOf(candidate) != null) {
        return true;
      }
    }
    return false;
  }

  private static String requestMethodOf(Method method) {
    if (method.isAnnotationPresent(GET.class)) {
      return "GET";
    }
    if (method.isAnnotationPresent(POST.class)) {
      return "POST";
    }
    if (method.isAnnotationPresent(PUT.class)) {
      return "PUT";
    }
    if (method.isAnnotationPresent(DELETE.class)) {
      return "DELETE";
    }
    if (method.isAnnotationPresent(HEAD.class)) {
      return "HEAD";
    }
    if (method.isAnnotationPresent(OPTIONS.class)) {
      return "OPTIONS";
    }
    return null;
  }

  private static List<MediaType> mediaTypes(Annotation annotation) {
    final List<MediaType> types = new ArrayList<>();
    if (annotation instanceof Produces) {
      for (final String value : ((Produces) annotation).value()) {
        addParsed(types, value);
      }
    } else if (annotation instanceof Consumes) {
      for (final String value : ((Consumes) annotation).value()) {
        addParsed(types, value);
      }
    }
    return types;
  }

  private static void addParsed(List<MediaType> types, String value) {
    final MediaType parsed = MediaType.parse(value);
    if (parsed != null) {
      types.add(parsed);
    }
  }

  private static List<ParamBinding> bindingsOf(Method method) {
    final List<ParamBinding> bindings = new ArrayList<>();
    for (final Parameter parameter : method.getParameters()) {
      final DefaultValue defaultValue = parameter.getAnnotation(DefaultValue.class);
      final String fallback = defaultValue == null ? null : defaultValue.value();
      final Annotation[] annotations = parameter.getAnnotations();
      ParamBinding.Kind kind = ParamBinding.Kind.ENTITY;
      String name = null;
      if (parameter.isAnnotationPresent(PathParam.class)) {
        kind = ParamBinding.Kind.PATH;
        name = parameter.getAnnotation(PathParam.class).value();
      } else if (parameter.isAnnotationPresent(QueryParam.class)) {
        kind = ParamBinding.Kind.QUERY;
        name = parameter.getAnnotation(QueryParam.class).value();
      } else if (parameter.isAnnotationPresent(HeaderParam.class)) {
        kind = ParamBinding.Kind.HEADER;
        name = parameter.getAnnotation(HeaderParam.class).value();
      } else if (parameter.isAnnotationPresent(CookieParam.class)) {
        kind = ParamBinding.Kind.COOKIE;
        name = parameter.getAnnotation(CookieParam.class).value();
      } else if (parameter.isAnnotationPresent(Context.class)) {
        kind = ParamBinding.Kind.CONTEXT;
      } else if (parameter.isAnnotationPresent(Suspended.class)) {
        kind = ParamBinding.Kind.SUSPENDED;
      }
      bindings.add(
          new ParamBinding(
              kind,
              name,
              fallback,
              parameter.getType(),
              parameter.getParameterizedType(),
              annotations));
    }
    return bindings;
  }

  public List<Route> routes() {
    return routes;
  }

  /** Every route whose path matches the target, in the order candidates are preferred. */
  public List<Route> matching(RequestTarget target) {
    final List<Route> matches = new ArrayList<>();
    for (final Route route : routes) {
      if (route.template().match(target.rawSegments()) != null) {
        matches.add(route);
      }
    }
    return matches;
  }

  /**
   * The request methods the matched path serves, with {@code OPTIONS}, which the server answers by
   * itself. {@code HEAD} is answered wherever {@code GET} is, but it is named only where the answer
   * lists what the path can do: the answer for a method the path does not serve names the declared
   * methods alone, exactly as it did before.
   *
   * <p>The order of the names is the set's own, and it is the order those answers carried.
   */
  public static Set<String> allowed(List<Route> matches, boolean namingHead) {
    final Set<String> methods = new HashSet<>();
    for (final Route route : matches) {
      methods.add(route.httpMethod());
      if (namingHead && "GET".equals(route.httpMethod())) {
        methods.add("HEAD");
      }
    }
    methods.add("OPTIONS");
    return methods;
  }
}
