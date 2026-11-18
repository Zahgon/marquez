/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.cli;

import lombok.Getter;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

/**
 * One thing the jar can be asked to do from the command line.
 *
 * <p>The commands themselves are unchanged work; what is new is that the application owns them,
 * rather than the framework that used to.
 */
public abstract class MarquezCommand {
  @Getter private final String name;
  @Getter private final String description;

  protected MarquezCommand(final String name, final String description) {
    this.name = name;
    this.description = description;
  }

  /** Declares the arguments this command takes. */
  public void configure(Subparser subparser) {}

  /** Runs the command. */
  public abstract void run(Namespace namespace) throws Exception;
}
