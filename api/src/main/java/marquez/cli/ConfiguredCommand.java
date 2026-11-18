/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.cli;

import java.nio.file.Path;
import marquez.MarquezConfig;
import marquez.config.ConfigLoader;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

/** A command that is given the service's configuration file, as every such command always was. */
public abstract class ConfiguredCommand extends MarquezCommand {
  private static final String FILE = "file";

  protected ConfiguredCommand(final String name, final String description) {
    super(name, description);
  }

  @Override
  public void configure(Subparser subparser) {
    subparser.addArgument(FILE).nargs("?").help("the configuration file");
  }

  @Override
  public final void run(Namespace namespace) throws Exception {
    final String file = namespace.getString(FILE);
    if (file == null) {
      throw new IllegalArgumentException("a configuration file is required");
    }
    run(namespace, ConfigLoader.load(Path.of(file)));
  }

  /** Runs the command against the configuration it was given. */
  protected abstract void run(Namespace namespace, MarquezConfig configuration) throws Exception;
}
