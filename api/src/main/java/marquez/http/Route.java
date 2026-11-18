/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * One request method of one resource, with everything needed to call it.
 *
 * <p>A resource may hand part of its path to another object instead of answering itself. Such a
 * route remembers the method that hands it over, and the instance it answers with is built for the
 * request, from the part of the path that method owns.
 */
public final class Route {
  private final String httpMethod;
  private final PathTemplate template;
  private final Object resource;
  private final Method method;
  private final Method handOver;
  private final List<ParamBinding> handOverBindings;
  private final List<MediaType> produces;
  private final List<MediaType> consumes;
  private final List<ParamBinding> bindings;

  Route(
      String httpMethod,
      PathTemplate template,
      Object resource,
      Method method,
      Method handOver,
      List<ParamBinding> handOverBindings,
      List<MediaType> produces,
      List<MediaType> consumes,
      List<ParamBinding> bindings) {
    this.httpMethod = httpMethod;
    this.template = template;
    this.resource = resource;
    this.method = method;
    this.handOver = handOver;
    this.handOverBindings = handOverBindings;
    this.produces = produces;
    this.consumes = consumes;
    this.bindings = bindings;
  }

  public String httpMethod() {
    return httpMethod;
  }

  public PathTemplate template() {
    return template;
  }

  /** The object the request method is declared on, which is the root resource for a hand-over. */
  public Object resource() {
    return resource;
  }

  public Method method() {
    return method;
  }

  /** The method that hands part of the path to another object, or {@code null}. */
  public Method handOver() {
    return handOver;
  }

  public List<ParamBinding> handOverBindings() {
    return handOverBindings;
  }

  public List<MediaType> produces() {
    return produces;
  }

  public List<MediaType> consumes() {
    return consumes;
  }

  public List<ParamBinding> bindings() {
    return bindings;
  }

  /** The name the metric registry knows this request method by. */
  public String metricName() {
    return method.getDeclaringClass().getName() + "." + method.getName();
  }

  /** Whether this method reads an entity from the request. */
  public boolean readsEntity() {
    for (final ParamBinding binding : bindings) {
      if (binding.kind() == ParamBinding.Kind.ENTITY) {
        return true;
      }
    }
    return false;
  }

  /** The object the request method runs on: the resource itself, or the one handed the path. */
  public Object instanceFor(Object[] handOverArguments)
      throws IllegalAccessException, InvocationTargetException {
    if (handOver == null) {
      return resource;
    }
    return handOver.invoke(resource, handOverArguments);
  }

  @Override
  public String toString() {
    return httpMethod + " " + template.declared() + " -> " + method.getName();
  }
}
