/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.graphql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExceptionWhileDataFetching;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import marquez.common.Utils;
import marquez.http.Answer;
import marquez.http.RequestContext;

/**
 * The graphql endpoint, served by the application itself now that there is no servlet container to
 * hold the one that used to serve it. It stays behind the same configuration switch, it answers on
 * both the paths that one was mapped on, and everything about the answers it gives is kept: the
 * media type they are offered as, where the query is read from, what a request that carries no
 * query is refused with, and which of the errors of an execution the caller is allowed to see.
 */
@Slf4j
public class GraphqlEndpoint {
  private static final ObjectMapper MAPPER = Utils.getMapper();

  /** The media type the answers of this endpoint are offered as. */
  private static final String JSON = "application/json;charset=utf-8";

  /** The methods this endpoint answers, as the servlet that used to serve it named them. */
  private static final String ALLOW = "GET, HEAD, POST, TRACE, OPTIONS";

  /** The media type whose body IS the query, rather than an object carrying one. */
  private static final String GRAPHQL = "application/graphql";

  /** What an execution's own failures are reported as, so that nothing inside leaks out. */
  private static final String MASKED = "Internal Server Error(s) while executing query";

  private final GraphQL graphql;

  public GraphqlEndpoint(final GraphqlSchemaBuilder schemaBuilder) {
    this.graphql = GraphQL.newGraphQL(schemaBuilder.buildSchema()).build();
  }

  /** Answers one graphql request. */
  public Answer answer(RequestContext request, byte[] body) {
    final String method = request.method();
    if ("OPTIONS".equals(method)) {
      return new Answer(200).header("Allow", ALLOW).withholdLength();
    }
    if ("PATCH".equals(method)) {
      return new Answer(501);
    }
    final boolean reading = "GET".equals(method) || "HEAD".equals(method);
    if (!reading && !"POST".equals(method)) {
      return new Answer(405);
    }
    final Asked asked;
    try {
      asked = reading ? fromTarget(request) : fromEntity(request, body);
    } catch (RuntimeException cannotRead) {
      // A request this endpoint cannot make a query out of never reaches the schema: it is
      // refused with nothing written for it, as the servlet that used to serve it refused one.
      return new Answer(400);
    }
    if (asked == null) {
      return new Answer(400);
    }
    final ExecutionResult result;
    try {
      result =
          graphql.execute(
              ExecutionInput.newExecutionInput()
                  .query(asked.query)
                  .operationName(asked.operation)
                  .variables(asked.variables)
                  .build());
    } catch (RuntimeException neverRan) {
      // An execution that could not even be started - a named operation the query does not
      // carry, say - is reported as the error it raised, with no data.
      if (neverRan instanceof GraphQLError) {
        return answerFor(List.of(messageOf(neverRan.getMessage())), null);
      }
      throw neverRan;
    }
    return answerFor(result);
  }

  /** One request's query, the variables it carries and the operation it names. */
  private static final class Asked {
    private final String query;
    @Nullable private final String operation;
    private final Map<String, Object> variables;

    private Asked(String query, @Nullable String operation, Map<String, Object> variables) {
      this.query = query;
      this.operation = operation;
      this.variables = variables;
    }
  }

  /** What a request that carries its query in the target asks. */
  @Nullable
  private static Asked fromTarget(RequestContext request) {
    final Map<String, List<String>> parameters = request.target().queryParameters();
    final String query = first(parameters, "query");
    if (query == null) {
      return null;
    }
    final String variables = first(parameters, "variables");
    return new Asked(
        query,
        first(parameters, "operationName"),
        variables == null ? Collections.emptyMap() : asVariables(readObject(variables)));
  }

  /** What a request that carries its query as its entity asks. */
  @Nullable
  private Asked fromEntity(RequestContext request, byte[] body) {
    if (body == null || body.length == 0) {
      return null;
    }
    final String type = request.firstHeader("content-type");
    if (type != null && type.toLowerCase(Locale.ROOT).startsWith(GRAPHQL)) {
      return new Asked(new String(body, StandardCharsets.UTF_8), null, Collections.emptyMap());
    }
    final Map<String, Object> asked = readObject(new String(body, StandardCharsets.UTF_8));
    final Object query = asked.get("query");
    if (query == null) {
      return null;
    }
    final Object operation = asked.get("operationName");
    return new Asked(
        String.valueOf(query),
        operation == null ? null : String.valueOf(operation),
        asVariables(asked.get("variables")));
  }

  private static Map<String, Object> readObject(String text) {
    try {
      return MAPPER.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception cannotRead) {
      throw new IllegalArgumentException(cannotRead);
    }
  }

  /** The variables of a request: an object, nothing at all, or a request that is refused. */
  private static Map<String, Object> asVariables(@Nullable Object variables) {
    if (variables == null) {
      return Collections.emptyMap();
    }
    if (!(variables instanceof Map)) {
      throw new IllegalArgumentException("the variables of a query must be an object");
    }
    final Map<String, Object> out = new LinkedHashMap<>();
    ((Map<?, ?>) variables).forEach((name, value) -> out.put(String.valueOf(name), value));
    return out;
  }

  @Nullable
  private static String first(Map<String, List<String>> parameters, String name) {
    final List<String> values = parameters.get(name);
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  /**
   * The answer for one execution.
   *
   * <p>The caller is told about the errors it caused - a query that does not parse, a query that
   * does not fit the schema - and never about the ones the service itself ran into: those are
   * logged and reported as one error that says only that there were some. The data of the execution
   * is always named, even when there is none.
   */
  private static Answer answerFor(ExecutionResult result) {
    final List<Object> reported = new ArrayList<>();
    boolean ranIntoSomething = false;
    for (final GraphQLError error : result.getErrors()) {
      if (causedByTheCaller(error)) {
        reported.add(error.toSpecification());
      } else {
        log.error("Error while executing a graphql query: {}", error.getMessage());
        ranIntoSomething = true;
      }
    }
    if (ranIntoSomething) {
      reported.add(messageOf(MASKED));
    }
    return answerFor(reported, result.isDataPresent() ? result.getData() : null);
  }

  /** One error reported with its message alone, which is all these errors carry. */
  private static Map<String, Object> messageOf(String message) {
    final Map<String, Object> error = new LinkedHashMap<>();
    error.put("message", message);
    error.put("locations", Collections.emptyList());
    return error;
  }

  /** The answer carrying the errors of an execution and whatever data it produced. */
  private static Answer answerFor(List<Object> reported, @Nullable Object data) {
    final Map<String, Object> specification = new LinkedHashMap<>();
    if (!reported.isEmpty()) {
      specification.put("errors", reported);
    }
    specification.put("data", data);
    final Answer answer = new Answer(200);
    answer.body(marquez.http.Entities.write(specification));
    return answer.header("Content-Type", JSON);
  }

  /**
   * Whether an error is one the caller is told about, rather than one the service ran into.
   *
   * <p>Everything the schema itself reports - a query that does not parse, a field that is not
   * there, a mutation a schema without mutations cannot answer - is the caller's. What a data
   * fetcher threw is not, unless what it threw is itself one of these errors.
   */
  private static boolean causedByTheCaller(GraphQLError error) {
    return !(error instanceof ExceptionWhileDataFetching)
        || ((ExceptionWhileDataFetching) error).getException() instanceof GraphQLError;
  }
}
