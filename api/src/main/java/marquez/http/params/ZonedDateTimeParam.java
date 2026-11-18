/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http.params;

import java.time.ZonedDateTime;
import marquez.http.ErrorMessage;
import marquez.http.HttpFailure;

/**
 * A parameter that is a date and time with an offset.
 *
 * <p>A value that cannot be read is answered where it was read, not where it was used: the caller
 * is told the parameter is invalid and why, in the shape every such answer has. That is what the
 * parameter type this replaces did, and resources that declare it keep their behaviour.
 */
public final class ZonedDateTimeParam {
  private final ZonedDateTime value;

  public ZonedDateTimeParam(final String value) {
    try {
      this.value = ZonedDateTime.parse(value);
    } catch (RuntimeException cannotRead) {
      throw new HttpFailure(
          400, new ErrorMessage(400, "Parameter is invalid: " + cannotRead.getMessage()));
    }
  }

  /** The instant the parameter named. */
  public ZonedDateTime get() {
    return value;
  }

  @Override
  public String toString() {
    return String.valueOf(value);
  }
}
