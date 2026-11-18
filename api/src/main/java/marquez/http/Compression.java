/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.http;

import java.util.List;

/**
 * The compression of answers, which stood in front of both connectors and still does.
 *
 * <p>A read that succeeded is always announced as varying by encoding, whether or not anything was
 * compressed; nothing else is - not a write that succeeded, and not an error. Compression itself
 * needs the caller to have asked for this encoding by name and the answer to be big enough to be
 * worth it.
 */
public final class Compression {
  private Compression() {}

  /** Applies the rule to one answer. */
  public static void apply(String method, List<String> acceptEncodings, Answer answer) {
    if (!Gzip.announcesVary(answer.status(), method)) {
      return;
    }
    answer.header("Vary", "Accept-Encoding");
    if (Gzip.accepted(acceptEncodings) && Gzip.worthCompressing(answer.body())) {
      answer.body(Gzip.deflate(answer.body()));
      answer.header("Content-Encoding", "gzip");
    }
  }
}
