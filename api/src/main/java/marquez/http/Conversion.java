/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turning the text of a parameter into the type a resource method asks for.
 *
 * <p>Two kinds of failure are told apart, because the serving layer this replaces told them apart.
 * A value that is not one of an enumeration's constants is a BAD REQUEST carrying the message that
 * names the constants; a value of any other type that cannot be read is NOT FOUND, with the
 * server's own error entity and nothing about the parameter in it.
 *
 * <p>Matching an enumeration's constant is deliberately forgiving: it ignores case, and it ignores
 * the spaces, dashes and underscores on either side, so {@code update-at}, {@code update_at} and
 * {@code UPDATE_AT} all name the same constant.
 */
public final class Conversion {
  private Conversion() {}

  /** Thrown when a value cannot be read as the type the method asks for. */
  public static final class CannotConvert extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final boolean namesTheParameter;
    private final String detail;

    CannotConvert(String detail, boolean namesTheParameter) {
      super(detail);
      this.detail = detail;
      this.namesTheParameter = namesTheParameter;
    }

    /** Whether the answer tells the caller which parameter and which values were expected. */
    public boolean namesTheParameter() {
      return namesTheParameter;
    }

    public String detail() {
      return detail;
    }
  }

  /** Converts the values of one parameter, honouring its default and its collection type. */
  public static Object convert(ParamBinding binding, List<String> values) {
    final Class<?> type = binding.type();
    if (isCollection(type)) {
      return collection(binding, values);
    }
    if (values.isEmpty()) {
      return fallback(binding, type);
    }
    return fromText(binding, type, values.get(0));
  }

  /**
   * One value read as the type the method asks for.
   *
   * <p>A parameter that arrived with NOTHING in it is read as one that never arrived at all: it
   * takes the default it declares, or nothing - unless the method asks for the text itself, which
   * is handed the empty value as it came. Beyond that, only a parameter of a PRIMITIVE type is
   * forgiving: one whose value is nothing but the characters a trim takes off - every character up
   * to and including a space, so a null byte and a vertical tab as well - is read the same way. No
   * other type is: an enumeration, a timestamp or anything else built from text is handed a value
   * with something in it exactly as it arrived, and what it makes of it - a constant, nothing at
   * all, or a failure the caller is told about - is the answer.
   */
  private static Object fromText(ParamBinding binding, Class<?> type, String value) {
    if (binding.kind() != ParamBinding.Kind.PATH
        && type != String.class
        && (value.isEmpty() || (type.isPrimitive() && value.trim().isEmpty()))) {
      return fallback(binding, type);
    }
    return one(binding, type, value);
  }

  /** What a parameter takes when nothing usable arrived: its declared default, or nothing. */
  private static Object fallback(ParamBinding binding, Class<?> type) {
    if (binding.defaultValue() != null) {
      return one(binding, type, binding.defaultValue());
    }
    return absent(type);
  }

  private static boolean isCollection(Class<?> type) {
    return List.class.isAssignableFrom(type) || Set.class.isAssignableFrom(type);
  }

  private static Object collection(ParamBinding binding, List<String> values) {
    final Class<?> element = elementType(binding.genericType());
    List<String> text = values;
    if (text.isEmpty() && binding.defaultValue() != null) {
      text = List.of(binding.defaultValue());
    }
    final List<Object> converted = new ArrayList<>();
    for (final String value : text) {
      converted.add(
          element.isPrimitive() && value.trim().isEmpty()
              ? absent(element)
              : one(binding, element, value));
    }
    if (Set.class.isAssignableFrom(binding.type())) {
      return new LinkedHashSet<>(converted);
    }
    return converted;
  }

  private static Class<?> elementType(Type generic) {
    if (generic instanceof ParameterizedType) {
      final Type[] arguments = ((ParameterizedType) generic).getActualTypeArguments();
      if (arguments.length == 1 && arguments[0] instanceof Class) {
        return (Class<?>) arguments[0];
      }
    }
    return String.class;
  }

  /** The value a parameter takes when nothing arrived and it has no default. */
  private static Object absent(Class<?> type) {
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == double.class) {
      return 0.0d;
    }
    if (type == float.class) {
      return 0.0f;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == char.class) {
      return (char) 0;
    }
    if (type == Optional.class) {
      return Optional.empty();
    }
    return null;
  }

  private static Object one(ParamBinding binding, Class<?> type, String value) {
    if (type == String.class) {
      return value;
    }
    if (type.isEnum()) {
      return enumConstant(binding, type, value);
    }
    try {
      if (type == int.class || type == Integer.class) {
        return Integer.valueOf(value);
      }
      if (type == long.class || type == Long.class) {
        return Long.valueOf(value);
      }
      if (type == boolean.class || type == Boolean.class) {
        return Boolean.valueOf(value);
      }
      if (type == double.class || type == Double.class) {
        return Double.valueOf(value);
      }
      if (type == float.class || type == Float.class) {
        return Float.valueOf(value);
      }
      if (type == short.class || type == Short.class) {
        return Short.valueOf(value);
      }
      if (type == byte.class || type == Byte.class) {
        return Byte.valueOf(value);
      }
      if (type == UUID.class) {
        return strictUuid(value);
      }
    } catch (RuntimeException cannotRead) {
      throw new CannotConvert(cannotRead.getMessage(), false);
    }
    return Constructed.from(type, value);
  }

  /**
   * A value named by an enumeration's constant, matched without regard to case, with the spaces,
   * the line feeds, the carriage returns and the tabs taken out of it and a dash or a dot read as
   * an underscore - and nothing else. No other character comes out: a form feed or a vertical tab
   * stays in the value, and a value that merely resembles a constant does not name it. A value with
   * nothing in it at all names NOTHING, and that is not a failure - the parameter is simply read as
   * carrying no constant.
   */
  private static Object enumConstant(ParamBinding binding, Class<?> type, String value) {
    final Object[] constants = type.getEnumConstants();
    if (value.isEmpty()) {
      return null;
    }
    final String wanted =
        value
            .replace(" ", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", "")
            .replace('-', '_')
            .replace('.', '_');
    for (final Object constant : constants) {
      if (((Enum<?>) constant).name().equalsIgnoreCase(wanted)) {
        return constant;
      }
    }
    for (final Object constant : constants) {
      if (constant.toString().equalsIgnoreCase(value)) {
        return constant;
      }
    }
    final StringBuilder names = new StringBuilder();
    for (final Object constant : constants) {
      if (names.length() > 0) {
        names.append(", ");
      }
      names.append(((Enum<?>) constant).name());
    }
    throw new CannotConvert(binding.describe() + " must be one of [" + names + "]", true);
  }

  /** A UUID read strictly, so that a value shorter than the form is refused. */
  private static UUID strictUuid(String value) {
    final UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equalsIgnoreCase(value)) {
      throw new IllegalArgumentException("Invalid UUID string: " + value);
    }
    return parsed;
  }
}
