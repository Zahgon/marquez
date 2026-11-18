/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Random;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import org.jdbi.v3.core.JdbiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turning everything that can go wrong into the answer the caller sees.
 *
 * <p>There are three shapes, and which one is used is the difference a caller can see between the
 * classes of failure:
 *
 * <ul>
 *   <li>a status the server decides on by itself, and a failure a resource throws, answer with a
 *       code and a message;
 *   <li>a value that breaks a constraint answers with the messages alone, under {@code errors}, and
 *       with {@code 422} when the constraint was declared on the entity rather than on a parameter;
 *   <li>anything unforeseen answers with a code, a sentence and the identifier it was logged under,
 *       so that the answer carries nothing about the service's insides.
 * </ul>
 */
public final class Failures {
  private static final Logger log = LoggerFactory.getLogger(Failures.class);
  private static final Random IDS = new SecureRandom();

  private final Object jsonProcessingMapper;
  private final Object jdbiMapper;

  public Failures(Object jsonProcessingMapper, Object jdbiMapper) {
    this.jsonProcessingMapper = jsonProcessingMapper;
    this.jdbiMapper = jdbiMapper;
  }

  /** The answer for a status the server decides on by itself. */
  public static Answer ofStatus(int status) {
    return Answer.json(status, new ErrorMessage(status, Reasons.message(status)));
  }

  /** The answer for values that broke the constraints declared on them. */
  public static Answer violations(List<String> messages, boolean onTheEntity) {
    return Answer.json(onTheEntity ? 422 : 400, new ValidationErrors(messages));
  }

  /** The answer for anything the service did not foresee. */
  public static Answer unforeseen(Throwable failure) {
    final long id = IDS.nextLong();
    log.error("Error handling a request: {}", String.format("%016x", id), failure);
    return Answer.json(
        500,
        new ErrorMessage(
            500,
            String.format(
                "There was an error processing your request. It has been logged (ID %016x).", id)));
  }

  /** The answer for whatever a resource method, or the reading of its entity, threw. */
  public Answer of(Throwable thrown) {
    Throwable failure = thrown;
    while (failure instanceof java.lang.reflect.InvocationTargetException
        || failure instanceof java.util.concurrent.CompletionException
        || failure instanceof java.util.concurrent.ExecutionException) {
      if (failure.getCause() == null) {
        break;
      }
      failure = failure.getCause();
    }
    if (failure instanceof HttpFailure) {
      final HttpFailure decided = (HttpFailure) failure;
      return Answer.json(decided.status(), decided.entity());
    }
    if (failure instanceof Conversion.CannotConvert) {
      final Conversion.CannotConvert cannot = (Conversion.CannotConvert) failure;
      return cannot.namesTheParameter()
          ? Answer.json(400, new ErrorMessage(400, cannot.detail()))
          : ofStatus(404);
    }
    if (failure instanceof WebApplicationException) {
      return fromDeclared((WebApplicationException) failure);
    }
    if (failure instanceof JsonProcessingException && jsonProcessingMapper != null) {
      return fromMapped(
          ((javax.ws.rs.ext.ExceptionMapper<JsonProcessingException>) jsonProcessingMapper)
              .toResponse((JsonProcessingException) failure));
    }
    if (failure instanceof JdbiException && jdbiMapper != null) {
      return fromMapped(
          ((javax.ws.rs.ext.ExceptionMapper<JdbiException>) jdbiMapper)
              .toResponse((JdbiException) failure));
    }
    return unforeseen(failure);
  }

  /** A failure a resource throws carries the status it was built with and its own message. */
  private Answer fromDeclared(WebApplicationException declared) {
    final Response response = declared.getResponse();
    final int status = response == null ? 500 : response.getStatus();
    if (response != null && response.getEntity() != null) {
      return fromMapped(response);
    }
    if (status >= 500) {
      return unforeseen(declared);
    }
    return Answer.json(status, new ErrorMessage(status, declared.getMessage()));
  }

  /** The answer an exception mapper built, written the way every entity is written. */
  private Answer fromMapped(Response response) {
    final Answer answer = new Answer(response.getStatus());
    final Object entity = response.getEntity();
    answer.body(entity == null ? new byte[0] : Entities.write(entity));
    answer.header("Content-Type", "application/json");
    return answer;
  }
}
