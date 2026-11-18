/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableSet;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import marquez.api.filter.exclusions.ExclusionsConfig;
import marquez.config.DatabaseConfig;
import marquez.config.LoggingConfig;
import marquez.config.ServerConfig;
import marquez.db.FlywayFactory;
import marquez.graphql.GraphqlConfig;
import marquez.jobs.DbRetentionConfig;
import marquez.search.SearchConfig;
import marquez.service.models.Tag;
import marquez.tracing.SentryConfig;

/** Configuration for {@code Marquez}. */
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MarquezConfig {
  private static final boolean DEFAULT_MIGRATE_ON_STARTUP = true;
  private static final ImmutableSet<Tag> DEFAULT_TAGS = ImmutableSet.of();

  @Getter private boolean migrateOnStartup = DEFAULT_MIGRATE_ON_STARTUP;
  @Getter private ImmutableSet<Tag> tags = DEFAULT_TAGS;

  @Getter
  @JsonProperty("server")
  private final ServerConfig server = new ServerConfig();

  @Getter
  @JsonProperty("logging")
  private final LoggingConfig logging = new LoggingConfig();

  @Getter
  @JsonProperty("db")
  private final DatabaseConfig database = new DatabaseConfig();

  @Getter
  @JsonProperty("flyway")
  private final FlywayFactory flywayFactory = new FlywayFactory();

  @Getter
  @JsonProperty("graphql")
  private final GraphqlConfig graphql = new GraphqlConfig();

  @Getter
  @JsonProperty("sentry")
  private final SentryConfig sentry = new SentryConfig();

  @Getter
  @JsonProperty("search")
  private final SearchConfig searchConfig = new SearchConfig();

  @Getter
  @Setter
  @JsonProperty("dbRetention")
  private DbRetentionConfig dbRetention; // OPTIONAL

  @Getter
  @JsonProperty("exclude")
  private ExclusionsConfig exclude = new ExclusionsConfig();

  /** Returns {@code true} if a data retention policy has been configured. */
  public boolean hasDbRetentionPolicy() {
    return (dbRetention != null);
  }
}
