/*
 * Copyright 2018-2022 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.cli;

import javax.sql.DataSource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.MarquezConfig;
import marquez.config.DatabaseConfig;
import marquez.db.DbMigration;
import net.sourceforge.argparse4j.inf.Namespace;

/**
 * A command to manually run database migrations. This command to be used to run migrations
 * decoupled from application deployment.
 */
@Slf4j
public class DbMigrateCommand extends ConfiguredCommand {

  public DbMigrateCommand() {
    super("db-migrate", "A command to manually run database migrations.");
  }

  @Override
  protected void run(@NonNull Namespace namespace, @NonNull MarquezConfig configuration)
      throws Exception {

    final DatabaseConfig database = configuration.getDatabase();
    final DataSource source = database.build("MarquezApp-source");

    DbMigration.migrateDbOrError(configuration.getFlywayFactory(), source, true);
  }
}
