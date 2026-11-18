/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.codahale.metrics.health.jvm.ThreadDeadlockHealthCheck;
import com.codahale.metrics.jdbi3.InstrumentedSqlLogger;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.dropwizard.DropwizardExports;
import io.prometheus.client.hotspot.DefaultExports;
import io.sentry.Sentry;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.api.filter.JobRedirectFilter;
import marquez.api.filter.exclusions.Exclusions;
import marquez.api.filter.exclusions.ExclusionsConfig;
import marquez.db.DbMigration;
import marquez.db.NamePrependingTemplateEngine;
import marquez.graphql.GraphqlEndpoint;
import marquez.http.Answer;
import marquez.http.Dispatcher;
import marquez.http.Failures;
import marquez.http.MarquezServer;
import marquez.http.RequestContext;
import marquez.http.RequestFilters;
import marquez.http.Route;
import marquez.http.RouteTable;
import marquez.http.StaticAssets;
import marquez.http.admin.AdminEndpoints;
import marquez.http.admin.PrometheusEndpoint;
import marquez.http.metrics.ResourceMetrics;
import marquez.jobs.DbRetentionJob;
import marquez.jobs.MaterializeViewRefresherJob;
import marquez.lifecycle.Managed;
import marquez.logging.DelegatingSqlLogger;
import marquez.logging.LabelledSqlLogger;
import marquez.logging.LoggingMdcFilter;
import marquez.service.DatabaseMetrics;
import marquez.tracing.SentryConfig;
import marquez.tracing.TracingRequestFilter;
import marquez.tracing.TracingResponseFilter;
import marquez.tracing.TracingSQLLogger;
import org.flywaydb.core.api.FlywayException;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.SqlStatements;
import org.jdbi.v3.jackson2.Jackson2Config;
import org.jdbi.v3.jackson2.Jackson2Plugin;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/**
 * Everything the service is made of, put together and started.
 *
 * <p>The database, the migration, the services, the resources and the scheduled jobs are what they
 * always were; what has changed under them is the layer that serves the resources, which is now
 * {@code marquez.http} on Helidon's web server rather than a JAX-RS runtime inside a servlet
 * container.
 */
@Slf4j
public final class MarquezService {
  private static final String APP_NAME = "MarquezApp";
  private static final String DB_SOURCE_NAME = APP_NAME + "-source";
  private static final String DB_HEALTH_CHECK = "postgresql";
  private static final String PROMETHEUS_ENDPOINT = "/metrics";
  private static final String PROMETHEUS_ENDPOINT_V2 = "/v2beta/metrics";

  private MarquezService() {}

  /** Starts the service and waits until it is shut down. */
  public static void run(@NonNull MarquezConfig config) throws Exception {
    config.getLogging().apply();

    final MetricRegistry metrics = new MetricRegistry();
    final HealthCheckRegistry health = new HealthCheckRegistry();
    CollectorRegistry.defaultRegistry.register(new DropwizardExports(metrics));
    DatabaseMetrics.registry.register(new DropwizardExports(metrics));
    DefaultExports.initialize();
    DefaultExports.register(DatabaseMetrics.registry);

    final DataSource source = config.getDatabase().build(DB_SOURCE_NAME);

    log.info("Running startup actions...");
    try {
      DbMigration.migrateDbOrError(config.getFlywayFactory(), source, config.isMigrateOnStartup());
    } catch (FlywayException errorOnDbMigrate) {
      log.error("Stopping app...", errorOnDbMigrate);
      throw errorOnDbMigrate;
    }

    final boolean tracing = isSentryEnabled(config);
    if (tracing) {
      Sentry.init(
          options -> {
            options.setTracesSampleRate(config.getSentry().getTracesSampleRate());
            options.setEnvironment(config.getSentry().getEnvironment());
            options.setDsn(config.getSentry().getDsn());
            options.setDebug(config.getSentry().isDebug());
          });
    }

    final Jdbi jdbi = newJdbi(config, metrics, source, tracing);
    final MarquezContext context =
        MarquezContext.builder()
            .jdbi(jdbi)
            .searchConfig(config.getSearchConfig())
            .tags(config.getTags())
            .build();

    health.register("deadlocks", new ThreadDeadlockHealthCheck());
    health.register(DB_HEALTH_CHECK, new DatabaseHealthCheck(source));

    final RouteTable routes = RouteTable.of(context.getResources());
    final ResourceMetrics resourceMetrics = new ResourceMetrics(metrics);
    for (final Route route : routes.routes()) {
      resourceMetrics.register(route);
    }

    // The filters the old wiring registered, each in the place its registration covered and in the
    // order it registered them: the two the sentry switch turns on - the one that stood in front
    // of every request, and the one that named its transaction after the matched template - then
    // the logging filter, which puts up the context for every request the API layer takes in,
    // names the template once the request has been matched, reports the answer and takes the
    // context down once that answer has been written, and then the redirect, which needs a
    // matched job path.
    final LoggingMdcFilter logging = new LoggingMdcFilter();
    final List<RequestFilters.AroundRequest> aroundRequest =
        tracing ? List.of(new TracingRequestFilter()) : List.of();
    final List<RequestFilters.WhenTakenIn> whenTakenIn = List.of(logging);
    final List<RequestFilters.BeforeResource> beforeResource =
        List.of(logging, new JobRedirectFilter(context.getJobService()));
    // The response filters in the order the old wiring registered them: the tracing one, which
    // went in beside the sentry options, and then the logging one.
    final List<RequestFilters.AfterResource> afterResource =
        tracing ? List.of(new TracingResponseFilter(), logging) : List.of(logging);
    final List<RequestFilters.WhenComplete> whenComplete = List.of(logging);

    final Dispatcher dispatcher =
        new Dispatcher(
            routes,
            new Failures(context.getJsonException(), context.getJdbiException()),
            resourceMetrics,
            whenTakenIn,
            beforeResource,
            afterResource,
            whenComplete);

    final Map<String, PrometheusEndpoint> measurements = new LinkedHashMap<>();
    measurements.put(
        PROMETHEUS_ENDPOINT, new PrometheusEndpoint(CollectorRegistry.defaultRegistry));
    measurements.put(PROMETHEUS_ENDPOINT_V2, new PrometheusEndpoint(DatabaseMetrics.registry));

    final List<Managed> jobs = new ArrayList<>();
    if (config.hasDbRetentionPolicy()) {
      jobs.add(new DbRetentionJob(jdbi, config.getDbRetention()));
    }
    jobs.add(new MaterializeViewRefresherJob(jdbi));
    for (final Managed job : jobs) {
      job.start();
    }

    final ExclusionsConfig exclusions = config.getExclude();
    Exclusions.use(exclusions);

    final MarquezServer server =
        MarquezServer.start(
            config.getServer().getApplicationPort(),
            config.getServer().getAdminPort(),
            dispatcher,
            aroundRequest,
            measurements,
            besideTheApi(config, context),
            config.getGraphql().isEnabled()
                ? new StaticAssets("/graphql-playground", "/assets", "graphql-playground/index.htm")
                : null,
            new AdminEndpoints(metrics, health));
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  server.stop();
                  for (final Managed job : jobs) {
                    try {
                      job.stop();
                    } catch (Exception cannotStop) {
                      log.warn("Could not stop a scheduled job", cannotStop);
                    }
                  }
                }));
    server.awaitShutdown();
  }

  /**
   * What the API connector serves beside the API itself: the graphql endpoint and the page that
   * talks to it, both still behind the switch that has always turned them on.
   */
  private static Map<String, BiFunction<RequestContext, byte[], Answer>> besideTheApi(
      MarquezConfig config, MarquezContext context) {
    final Map<String, BiFunction<RequestContext, byte[], Answer>> served = new LinkedHashMap<>();
    if (config.getGraphql().isEnabled()) {
      final GraphqlEndpoint graphql = context.getGraphqlEndpoint();
      served.put("/api/v1-beta/graphql", graphql::answer);
      served.put("/api/v1/schema.json", graphql::answer);
    }
    return served;
  }

  private static boolean isSentryEnabled(MarquezConfig config) {
    return config.getSentry() != null
        && !config.getSentry().getDsn().equals(SentryConfig.DEFAULT_DSN);
  }

  /** Returns a new {@link Jdbi} object. */
  private static Jdbi newJdbi(
      @NonNull MarquezConfig config,
      @NonNull MetricRegistry metrics,
      @NonNull DataSource source,
      boolean tracing) {
    final Jdbi jdbi =
        Jdbi.create(source)
            .installPlugin(new SqlObjectPlugin())
            .installPlugin(new PostgresPlugin())
            .installPlugin(new Jackson2Plugin());
    jdbi.setTemplateEngine(
        new NamePrependingTemplateEngine(jdbi.getConfig(SqlStatements.class).getTemplateEngine()));
    SqlLogger sqlLogger =
        new DelegatingSqlLogger(new LabelledSqlLogger(), new InstrumentedSqlLogger(metrics));
    if (tracing) {
      sqlLogger = new TracingSQLLogger(sqlLogger);
    }
    jdbi.setSqlLogger(sqlLogger);
    jdbi.getConfig(Jackson2Config.class).setMapper(marquez.common.Utils.getMapper());
    return jdbi;
  }

  /** The health check the database answers, under the name the connector has always carried. */
  private static final class DatabaseHealthCheck extends HealthCheck {
    private final DataSource source;

    private DatabaseHealthCheck(DataSource source) {
      this.source = source;
    }

    @Override
    protected Result check() throws Exception {
      try (Connection connection = source.getConnection();
          Statement statement = connection.createStatement()) {
        statement.execute("/* MarquezApp Health Check */ SELECT 1");
      }
      return Result.healthy();
    }
  }
}
