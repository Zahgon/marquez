/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The connectors the service listens on: one for the API and one for the administrative endpoints,
 * each with its own port, as the configuration files of this project have always written them.
 */
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerConfig {
  private static final int DEFAULT_APPLICATION_PORT = 8080;
  private static final int DEFAULT_ADMIN_PORT = 8081;

  /** One connector of the server. */
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ConnectorConfig {
    @Getter
    @Setter
    @JsonProperty("type")
    private String type = "http";

    @Getter
    @Setter
    @JsonProperty("port")
    private int port;
  }

  @Getter
  @Setter
  @JsonProperty("applicationConnectors")
  private List<ConnectorConfig> applicationConnectors = List.of();

  @Getter
  @Setter
  @JsonProperty("adminConnectors")
  private List<ConnectorConfig> adminConnectors = List.of();

  /** The port the API is served on. */
  public int getApplicationPort() {
    return applicationConnectors.isEmpty()
        ? DEFAULT_APPLICATION_PORT
        : applicationConnectors.get(0).getPort();
  }

  /** The port the administrative endpoints are served on. */
  public int getAdminPort() {
    return adminConnectors.isEmpty() ? DEFAULT_ADMIN_PORT : adminConnectors.get(0).getPort();
  }
}
