/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import marquez.MarquezConfig;
import marquez.common.Utils;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads the configuration file the service is started with.
 *
 * <p>The file is YAML, and anything written as {@code ${NAME}} in it is replaced by the environment
 * variable of that name before it is read, with {@code ${NAME:-default}} naming what to use when
 * the variable is not set - which is what the deployments of this project rely on.
 */
public final class ConfigLoader {
  private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z0-9_]+)(?::-([^}]*))?}");

  private ConfigLoader() {}

  /** Reads one configuration file. */
  public static MarquezConfig load(Path file) throws IOException {
    return read(Files.readString(file, StandardCharsets.UTF_8));
  }

  /** Reads one configuration, already in hand. */
  public static MarquezConfig read(String yaml) {
    final Object tree = new Yaml().load(substitute(yaml));
    final ObjectMapper mapper = Utils.getMapper();
    if (tree == null) {
      return new MarquezConfig();
    }
    return mapper.convertValue(tree, MarquezConfig.class);
  }

  /** Replaces every environment variable written in the text. */
  static String substitute(String yaml) {
    final Map<String, String> environment = System.getenv();
    final Matcher variable = VARIABLE.matcher(yaml);
    final StringBuilder out = new StringBuilder();
    while (variable.find()) {
      final String value = environment.get(variable.group(1));
      final String fallback = variable.group(2) == null ? "" : variable.group(2);
      variable.appendReplacement(out, Matcher.quoteReplacement(value == null ? fallback : value));
    }
    variable.appendTail(out);
    return out.toString();
  }
}
