/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The database the service reads and writes, and the pool it reaches it through.
 *
 * <p>The keys are the ones the configuration files of this project already carry; the pool itself
 * belonged to the stack that is gone, so it is built here instead.
 */
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DatabaseConfig {
  @Getter
  @Setter
  @JsonProperty("driverClass")
  private String driverClass = "org.postgresql.Driver";

  @Getter
  @Setter
  @JsonProperty("url")
  private String url;

  @Getter
  @Setter
  @JsonProperty("user")
  private String user;

  @Getter
  @Setter
  @JsonProperty("password")
  private String password;

  @Getter
  @Setter
  @JsonProperty("maxSize")
  private int maxSize = 32;

  @Getter
  @Setter
  @JsonProperty("minSize")
  private int minSize = 2;

  @Getter
  @Setter
  @JsonProperty("properties")
  private Map<String, String> properties = new LinkedHashMap<>();

  /** Builds the pool the service takes its connections from. */
  public DataSource build(String poolName) {
    final HikariConfig pool = new HikariConfig();
    pool.setPoolName(poolName);
    pool.setJdbcUrl(url);
    pool.setUsername(user);
    pool.setPassword(password);
    if (driverClass != null && !driverClass.isEmpty()) {
      pool.setDriverClassName(driverClass);
    }
    pool.setMaximumPoolSize(maxSize);
    pool.setMinimumIdle(Math.min(minSize, maxSize));
    pool.setConnectionTestQuery("SELECT 1");
    properties.forEach(pool::addDataSourceProperty);
    return new HikariDataSource(pool);
  }
}
