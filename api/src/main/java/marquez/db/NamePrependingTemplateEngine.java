/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db;

import org.jdbi.v3.core.extension.ExtensionMethod;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.core.statement.TemplateEngine;

/**
 * Writes the name of the data access method a statement came from into the statement itself, as a
 * comment in front of it.
 *
 * <p>The wiring that is gone did this for every statement, and it is worth keeping: the name
 * travels with the statement into the database's own logs, into the timings the service records and
 * into the message of any error the statement raises.
 */
public final class NamePrependingTemplateEngine implements TemplateEngine {
  private final TemplateEngine delegate;

  public NamePrependingTemplateEngine(final TemplateEngine delegate) {
    this.delegate = delegate;
  }

  @Override
  public String render(final String template, final StatementContext context) {
    final String rendered = delegate.render(template, context);
    final ExtensionMethod method = context.getExtensionMethod();
    if (method == null) {
      return rendered;
    }
    return "/* "
        + method.getType().getSimpleName()
        + '.'
        + method.getMethod().getName()
        + " */ "
        + rendered;
  }
}
