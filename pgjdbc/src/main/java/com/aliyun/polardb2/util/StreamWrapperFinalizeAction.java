/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.util;

import static com.aliyun.polardb2.util.internal.Nullness.castNonNull;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The action deletes temporary file in case the user submits a large input stream,
 * and then abandons the statement.
 */
class StreamWrapperFinalizeAction implements Closeable {
  private @Nullable InputStream stream;
  private @Nullable Path tempFile;

  StreamWrapperFinalizeAction(Path tempFile) {
    this.tempFile = tempFile;
  }

  public InputStream getStream() throws IOException {
    InputStream stream = this.stream;
    if (stream == null) {
      stream = Files.newInputStream(castNonNull(tempFile));
      this.stream = stream;
    }
    return stream;
  }

  @Override
  public void close() throws IOException {
    Path tempFile = this.tempFile;
    if (tempFile != null) {
      tempFile.toFile().delete();
      this.tempFile = null;
    }
    InputStream stream = this.stream;
    if (stream != null) {
      stream.close();
      this.stream = null;
    }
  }

  @Override
  @SuppressWarnings("deprecation")
  protected void finalize() throws Throwable {
    close();
  }
}
