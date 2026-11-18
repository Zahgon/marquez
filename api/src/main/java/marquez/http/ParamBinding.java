/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/** Where one parameter of a resource method takes its value from, and how it is converted. */
public final class ParamBinding {
  /** The source of a parameter's value. */
  public enum Kind {
    PATH,
    QUERY,
    HEADER,
    COOKIE,
    CONTEXT,
    ENTITY,
    SUSPENDED
  }

  private final Kind kind;
  private final String name;
  private final String defaultValue;
  private final Class<?> type;
  private final Type genericType;
  private final Annotation[] annotations;

  ParamBinding(
      Kind kind,
      String name,
      String defaultValue,
      Class<?> type,
      Type genericType,
      Annotation[] annotations) {
    this.kind = kind;
    this.name = name;
    this.defaultValue = defaultValue;
    this.type = type;
    this.genericType = genericType;
    this.annotations = annotations;
  }

  public Kind kind() {
    return kind;
  }

  public String name() {
    return name;
  }

  public String defaultValue() {
    return defaultValue;
  }

  public Class<?> type() {
    return type;
  }

  public Type genericType() {
    return genericType;
  }

  public Annotation[] annotations() {
    return annotations;
  }

  /** The words the error entities use for where a value came from, as the old stack wrote them. */
  public String describe() {
    switch (kind) {
      case PATH:
        return "path param " + name;
      case QUERY:
        return "query param " + name;
      case HEADER:
        return "header " + name;
      case COOKIE:
        return "cookie " + name;
      default:
        return name == null ? "request entity" : name;
    }
  }
}
