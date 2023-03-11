/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.jdbc;

import com.aliyun.polardb2.core.Provider;
import com.aliyun.polardb2.core.QueryExecutor;
import com.aliyun.polardb2.util.GT;

import java.util.TimeZone;

/**
 * This class workarounds <a href="https://github.com/wildfly/jandex/issues/93">Exception when
 * indexing guava-30.0-jre</a>.
 * <p>It looks like {@code jandex} does not support {@code new Interface<..>} with type annotations.
 * </p>
 */
class QueryExecutorTimeZoneProvider implements Provider<TimeZone> {
  private final QueryExecutor queryExecutor;

  QueryExecutorTimeZoneProvider(QueryExecutor queryExecutor) {
    this.queryExecutor = queryExecutor;
  }

  @Override
  public TimeZone get() {
    TimeZone timeZone = queryExecutor.getTimeZone();
    if (timeZone == null) {
      throw new IllegalStateException(
          GT.tr("Backend timezone is not known. Backend should have returned TimeZone when "
              + "establishing a connection")
      );
    }
    return timeZone;
  }
}
