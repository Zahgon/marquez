/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

/** An answer the serving layer decides on by itself, carrying the entity it answers with. */
public final class HttpFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final int status;
  private final Object entity;

  public HttpFailure(int status, Object entity) {
    super("HTTP " + status);
    this.status = status;
    this.entity = entity;
  }

  public int status() {
    return status;
  }

  public Object entity() {
    return entity;
  }

  /** The answer for a value a parameter could not be converted from. */
  public static HttpFailure badParameter(String message) {
    return new HttpFailure(400, new ErrorMessage(400, message));
  }
}
