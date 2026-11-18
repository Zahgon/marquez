/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Iterator;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.slf4j.LoggerFactory;

/**
 * How much the service writes, and where.
 *
 * <p>The levels in the configuration file are the ones the service has always carried, and they are
 * applied to the logging the service actually uses, so that a configuration asking for warnings and
 * above produces warnings and above - a service left running cannot write without limit.
 */
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoggingConfig {
  /** One place the service writes to. */
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class AppenderConfig {
    @Getter
    @Setter
    @JsonProperty("type")
    private String type = "console";

    @Getter
    @Setter
    @JsonProperty("threshold")
    private String threshold;
  }

  @Getter
  @Setter
  @JsonProperty("level")
  private String level = "INFO";

  @Getter
  @Setter
  @JsonProperty("appenders")
  private List<AppenderConfig> appenders = List.of();

  /** Applies the configuration to the logging the service does. */
  public void apply() {
    final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    final ch.qos.logback.classic.Logger root =
        context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
    root.setLevel(Level.toLevel(level, Level.INFO));
    String threshold = null;
    for (final AppenderConfig appender : appenders) {
      if (appender.getThreshold() != null) {
        threshold = appender.getThreshold();
      }
    }
    if (threshold == null) {
      return;
    }
    for (final Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
      final Appender<ILoggingEvent> appender = it.next();
      final ThresholdFilter filter = new ThresholdFilter();
      filter.setLevel(threshold);
      filter.setContext(context);
      filter.start();
      appender.addFilter(filter);
    }
  }
}
