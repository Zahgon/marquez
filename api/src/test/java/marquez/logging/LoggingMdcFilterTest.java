/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Map;
import marquez.http.RequestContext;
import marquez.http.RequestTarget;
import marquez.http.Route;
import marquez.http.RouteTable;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

public class LoggingMdcFilterTest {

  private LoggingMdcFilter filter;

  @Before
  public void setUp() {
    filter = new LoggingMdcFilter();
  }

  @Test
  public void testPathWithPlaceholders() {
    final RequestContext request =
        new RequestContext(
            "GET", RequestTarget.of("/api/v1/namespaces/a"), Map.of(), "http://localhost:8080/");
    final RouteTable table = RouteTable.of(List.of(new NamespaceLike()));
    final List<Route> matched = table.matching(request.target());
    request.route(matched.get(0));

    filter.taken(request);
    filter.before(request);

    assertEquals("/api/v1/namespaces/{namespace}", MDC.get("pathWithParams"));
    assertEquals("GET", MDC.get("method"));
    assertEquals("/api/v1/namespaces/a", MDC.get("path"));

    filter.complete(null);
    assertNull(MDC.get("pathWithParams"));
    assertNull(MDC.get("method"));
  }

  /** A request nothing matched still gets a context of its own, and loses it when it is over. */
  @Test
  public void testContextForARequestThatWasNeverMatched() {
    final RequestContext request =
        new RequestContext(
            "DELETE", RequestTarget.of("/api/v1/nothing/here"), Map.of(), "http://localhost:8080/");

    filter.taken(request);

    assertNotNull(MDC.get("requestID"));
    assertEquals("DELETE", MDC.get("method"));
    assertEquals("/api/v1/nothing/here", MDC.get("path"));
    assertEquals("", MDC.get("pathWithParams"));

    filter.complete(null);
    assertNull(MDC.get("requestID"));
    assertNull(MDC.get("method"));
    assertNull(MDC.get("path"));
    assertNull(MDC.get("pathWithParams"));
  }

  /** A resource with one path, which is all this test needs to match against. */
  @javax.ws.rs.Path("/api/v1")
  public static class NamespaceLike {
    @javax.ws.rs.GET
    @javax.ws.rs.Path("/namespaces/{namespace}")
    public String get(@javax.ws.rs.PathParam("namespace") String namespace) {
      return namespace;
    }
  }
}
