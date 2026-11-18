/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.dropwizard.jackson.Jackson;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import marquez.common.Utils;

/**
 * Reading an entity from a request and writing one into an answer.
 *
 * <p>What goes out keeps the settings the service's entities have always been written with:
 * properties in the order they are declared, a property that is null written as null rather than
 * left out, and an instant written as a date and time rather than as a number.
 */
public final class Entities {
  private static final ObjectMapper MAPPER = newEntityMapper();

  /**
   * The mapper the entities of this service are read and written with: the project's own JSON
   * settings, with dates written as dates rather than as numbers and with the service's own reading
   * of a date and time that carries no zone. A property whose value is null is WRITTEN, which is
   * what the entities of this service have always carried; that is the one way this differs from
   * the mapper the service uses for its own stored values.
   */
  private static ObjectMapper newEntityMapper() {
    final ObjectMapper mapper = Jackson.newObjectMapper();
    mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    Utils.addZonedDateTimeMixin(mapper);
    return mapper;
  }

  private Entities() {}

  /**
   * Reads the entity a method asks for, from the bytes that arrived: what they say they are is the
   * mapper's business, and bytes that are not what they claim are its failure to report.
   */
  public static Object read(byte[] body, Type wanted) throws IOException {
    final JavaType type = MAPPER.getTypeFactory().constructType(wanted);
    return MAPPER.readValue(body, type);
  }

  /** Writes an entity the way the service writes every entity. */
  public static byte[] write(Object entity) {
    return write(entity, StandardCharsets.UTF_8);
  }

  /** Writes an entity in the character set the answer is written in. */
  public static byte[] write(Object entity, java.nio.charset.Charset charset) {
    try {
      final String written = MAPPER.writeValueAsString(entity);
      return written.getBytes(charset);
    } catch (JsonProcessingException cannotWrite) {
      throw new IllegalStateException("could not write the answer", cannotWrite);
    }
  }
}
