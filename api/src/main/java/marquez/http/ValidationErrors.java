/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The entity a request that breaks its constraints is answered with: the messages alone, under
 * {@code errors}, and never a status code beside them. A constraint failure and a value that could
 * not be converted at all are two different things and are answered with two different shapes.
 */
public final class ValidationErrors {
  private final List<String> errors;

  public ValidationErrors(List<String> errors) {
    this.errors = errors;
  }

  @JsonProperty("errors")
  public List<String> getErrors() {
    return errors;
  }
}
