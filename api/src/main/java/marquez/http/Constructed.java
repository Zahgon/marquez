/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A parameter type the resources bring with them: one that is built from a single piece of text,
 * either by a static {@code fromString} or {@code valueOf}, or by a constructor that takes one
 * string. This is how the serving layer this replaces turned text into the types a resource method
 * declares, and it is what lets a method keep asking for its own type.
 */
final class Constructed {
  private Constructed() {}

  static Object from(Class<?> type, String value) {
    final Method factory = factoryOf(type);
    if (factory != null) {
      try {
        return factory.invoke(null, value);
      } catch (IllegalAccessException cannotCall) {
        throw new Conversion.CannotConvert(cannotCall.getMessage(), false);
      } catch (InvocationTargetException failed) {
        throw rethrow(failed);
      }
    }
    try {
      return type.getConstructor(String.class).newInstance(value);
    } catch (NoSuchMethodException noWay) {
      throw new Conversion.CannotConvert(
          "no way to read a " + type.getSimpleName() + " from text", false);
    } catch (IllegalAccessException | InstantiationException cannotBuild) {
      throw new Conversion.CannotConvert(cannotBuild.getMessage(), false);
    } catch (InvocationTargetException failed) {
      throw rethrow(failed);
    }
  }

  /**
   * A failure inside the type's own code is the type's answer, not a conversion failure: a type
   * that refuses a value by throwing the serving layer's own failure keeps that answer.
   */
  private static RuntimeException rethrow(InvocationTargetException failed) {
    final Throwable cause = failed.getCause();
    if (cause instanceof HttpFailure) {
      return (HttpFailure) cause;
    }
    return new Conversion.CannotConvert(
        cause == null ? failed.getMessage() : cause.getMessage(), false);
  }

  private static Method factoryOf(Class<?> type) {
    for (final String name : new String[] {"fromString", "valueOf"}) {
      try {
        final Method candidate = type.getMethod(name, String.class);
        if (java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) {
          return candidate;
        }
      } catch (NoSuchMethodException keepLooking) {
        continue;
      }
    }
    return null;
  }
}
