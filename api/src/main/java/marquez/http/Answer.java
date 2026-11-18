/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One answer, as the serving layer decides it and before the connector writes it: a status with the
 * reason phrase that belongs to it, the headers set on it and the bytes of its entity.
 *
 * <p>Nothing here touches the network. The dispatcher builds one of these for every request,
 * whatever happened while the request was handled, and the connector writes it.
 */
public final class Answer {
  private int status;
  private String reason;
  private final Map<String, List<String>> headers = new LinkedHashMap<>();
  private byte[] body = new byte[0];
  private boolean lengthWithheld;

  public Answer(int status) {
    this(status, Reasons.of(status));
  }

  public Answer(int status, String reason) {
    this.status = status;
    this.reason = reason;
  }

  /** An answer carrying an entity written as the service writes every entity. */
  public static Answer json(int status, Object entity) {
    final Answer answer = new Answer(status);
    answer.body(Entities.write(entity));
    answer.header("Content-Type", "application/json");
    return answer;
  }

  public int status() {
    return status;
  }

  public Answer status(int status) {
    this.status = status;
    this.reason = Reasons.of(status);
    return this;
  }

  public String reason() {
    return reason;
  }

  public byte[] body() {
    return body;
  }

  public Answer body(byte[] body) {
    this.body = body == null ? new byte[0] : body;
    return this;
  }

  public Answer text(String body) {
    return body(body.getBytes(StandardCharsets.UTF_8));
  }

  public Map<String, List<String>> headers() {
    return headers;
  }

  /** Sets a header, replacing whatever was there. */
  public Answer header(String name, String value) {
    final List<String> values = new ArrayList<>();
    values.add(value);
    headers.put(name, values);
    return this;
  }

  /** Adds a header beside the ones already there. */
  public Answer addHeader(String name, String value) {
    headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
    return this;
  }

  public String firstHeader(String name) {
    for (final Map.Entry<String, List<String>> header : headers.entrySet()) {
      if (header.getKey().equalsIgnoreCase(name) && !header.getValue().isEmpty()) {
        return header.getValue().get(0);
      }
    }
    return null;
  }

  public boolean hasHeader(String name) {
    return firstHeader(name) != null;
  }

  public void removeHeader(String name) {
    headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
  }

  /** Whether the length of the entity is deliberately left off, as it is for a live dump. */
  public boolean lengthWithheld() {
    return lengthWithheld;
  }

  public Answer withholdLength() {
    this.lengthWithheld = true;
    return this;
  }
}
