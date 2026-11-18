/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.validation.ConstraintViolation;
import javax.validation.ElementKind;
import javax.validation.Path;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.executable.ExecutableValidator;

/**
 * The constraints a resource method declares on its parameters and on the entity it reads, checked
 * after the values have been converted and before the method is called - the order the serving
 * layer this replaces used, which is why a value that cannot be converted at all is answered before
 * any constraint is looked at.
 *
 * <p>A message names where the value came from: a parameter is named the way the old layer named it
 * ({@code query param limit must be greater than or equal to 0}), while a property of the entity is
 * named by the property alone ({@code run must not be null}). Which of the two it is also decides
 * the status of the answer.
 */
public final class Violations {
  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();
  private static final ExecutableValidator EXECUTABLES = VALIDATOR.forExecutables();

  /** One thing wrong with a call: what to say about it, and whether it is about the entity. */
  public static final class Broken {
    private final String message;
    private final boolean onTheEntity;

    Broken(String message, boolean onTheEntity) {
      this.message = message;
      this.onTheEntity = onTheEntity;
    }

    public String message() {
      return message;
    }

    public boolean onTheEntity() {
      return onTheEntity;
    }
  }

  private Violations() {}

  /** Everything wrong with the call, or an empty list when nothing is. */
  public static List<Broken> of(
      Object instance, Method method, List<ParamBinding> bindings, Object[] arguments) {
    final Set<ConstraintViolation<Object>> found;
    try {
      found = EXECUTABLES.validateParameters(instance, method, arguments);
    } catch (RuntimeException cannotValidate) {
      return List.of();
    }
    final List<Broken> broken = new ArrayList<>();
    for (final ConstraintViolation<Object> violation : found) {
      broken.add(describe(bindings, violation));
    }
    return broken;
  }

  private static Broken describe(
      List<ParamBinding> bindings, ConstraintViolation<Object> violation) {
    final List<Path.Node> nodes = new ArrayList<>();
    for (final Iterator<Path.Node> it = violation.getPropertyPath().iterator(); it.hasNext(); ) {
      nodes.add(it.next());
    }
    int argument = -1;
    final StringBuilder inside = new StringBuilder();
    for (final Path.Node node : nodes) {
      if (node.getKind() == ElementKind.PARAMETER) {
        argument = node.as(Path.ParameterNode.class).getParameterIndex();
        continue;
      }
      if (node.getKind() == ElementKind.PROPERTY
          || node.getKind() == ElementKind.CONTAINER_ELEMENT) {
        if (inside.length() > 0) {
          inside.append('.');
        }
        inside.append(node.getName());
      }
    }
    final String message = violation.getMessage();
    final ParamBinding binding =
        argument >= 0 && argument < bindings.size() ? bindings.get(argument) : null;
    final boolean onTheEntity = binding != null && binding.kind() == ParamBinding.Kind.ENTITY;
    if (inside.length() > 0) {
      // A property of the entity, named by the property alone.
      return new Broken(inside + " " + message, onTheEntity);
    }
    if (binding != null) {
      if (onTheEntity) {
        return new Broken("The request body " + message, true);
      }
      return new Broken(binding.describe() + " " + message, false);
    }
    return new Broken(message, false);
  }
}
