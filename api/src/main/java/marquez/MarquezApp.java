/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import marquez.cli.DbMigrateCommand;
import marquez.cli.DbRetentionCommand;
import marquez.cli.MarquezCommand;
import marquez.cli.MetadataCommand;
import marquez.cli.SeedCommand;
import marquez.cli.ServerCommand;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparsers;

/**
 * The way in: {@code java -jar marquez-api.jar <command> [arguments]}.
 *
 * <p>The commands are the ones this jar has always offered, {@code server} among them; the
 * framework that used to find and run them is gone, so the application names them itself.
 */
@Slf4j
public final class MarquezApp {
  private static final String APP_NAME = "MarquezApp";
  private static final String COMMAND = "command";

  private MarquezApp() {}

  public static void main(final String[] args) throws Exception {
    final Map<String, MarquezCommand> commands = new LinkedHashMap<>();
    for (final MarquezCommand command :
        new MarquezCommand[] {
          new ServerCommand(),
          new DbMigrateCommand(),
          new DbRetentionCommand(),
          new MetadataCommand(),
          new SeedCommand()
        }) {
      commands.put(command.getName(), command);
    }
    final ArgumentParser parser = ArgumentParsers.newFor(APP_NAME).build().defaultHelp(true);
    final Subparsers subparsers = parser.addSubparsers().dest(COMMAND);
    commands
        .values()
        .forEach(
            command ->
                command.configure(
                    subparsers.addParser(command.getName()).help(command.getDescription())));
    final Namespace namespace;
    try {
      namespace = parser.parseArgs(args);
    } catch (ArgumentParserException cannotParse) {
      parser.handleError(cannotParse);
      System.exit(1);
      return;
    }
    final MarquezCommand command = commands.get(namespace.getString(COMMAND));
    if (command == null) {
      parser.printHelp();
      System.exit(1);
      return;
    }
    try {
      command.run(namespace);
    } catch (Exception failed) {
      log.error("Failed to run '{}'.", command.getName(), failed);
      System.exit(1);
    }
  }
}
