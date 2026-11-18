/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The entity the service answers an error with: a status code and a message, and details only when
 * there are some. This is the shape the serving layer this replaces wrote for every error it
 * rendered itself, and the resources' own exception mappers build it too.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ErrorMessage {
  private final int code;
  private final String message;
  private final String details;

  public ErrorMessage(int code, String message) {
    this(code, message, null);
  }

  public ErrorMessage(int code, String message, String details) {
    this.code = code;
    this.message = message;
    this.details = details;
  }

  @JsonProperty("code")
  public int getCode() {
    return code;
  }

  @JsonProperty("message")
  public String getMessage() {
    return message;
  }

  @JsonProperty("details")
  public String getDetails() {
    return details;
  }
}
