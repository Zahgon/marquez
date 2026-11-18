/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.cli;

import marquez.MarquezConfig;
import marquez.MarquezService;
import net.sourceforge.argparse4j.inf.Namespace;

/** The command that runs the service: {@code java -jar marquez-api.jar server marquez.yml}. */
public class ServerCommand extends ConfiguredCommand {
  public ServerCommand() {
    super("server", "runs the Marquez HTTP API");
  }

  @Override
  protected void run(Namespace namespace, MarquezConfig configuration) throws Exception {
    MarquezService.run(configuration);
  }
}
