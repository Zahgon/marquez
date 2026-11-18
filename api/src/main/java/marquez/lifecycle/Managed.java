/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.lifecycle;

/**
 * Something the application starts when it comes up and stops when it goes down.
 *
 * <p>The scheduled jobs were tied to the lifecycle of the runtime that is gone; this is the same
 * contract, owned by the application, so the jobs are still started and stopped with it.
 */
public interface Managed {
  /** Starts the component; the application stops coming up if this throws. */
  void start() throws Exception;

  /** Stops the component. */
  void stop() throws Exception;
}
