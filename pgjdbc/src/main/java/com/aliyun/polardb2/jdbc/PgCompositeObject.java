/*
 * Copyright (c) 2025, Alibaba Group Holding Limited
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.jdbc;

import com.aliyun.polardb2.util.PGobject;

import java.sql.SQLException;
import java.sql.Struct;
import java.util.Map;

/**
 * POLAR: A PGobject subclass that also implements {@link java.sql.Struct}.
 *
 * <p>When PostgreSQL/PolarDB returns composite type values (e.g. elements of a TABLE OF
 * composite_type array), the driver historically returned plain {@link PGobject} instances.
 * User code that casts these elements to {@link java.sql.Struct} throws ClassCastException.
 *
 * <p>This class preserves backward compatibility ({@code instanceof PGobject} still works)
 * while also satisfying the JDBC standard ({@code instanceof Struct} now works too).
 *
 * <p>The composite literal string (e.g. {@code (val1,"val2",,val4)}) is stored as the
 * PGobject value. Attributes are parsed on demand via {@link #getAttributes()}.
 */
public class PgCompositeObject extends PGobject implements Struct {

  @Override
  public String getSQLTypeName() throws SQLException {
    return getType();
  }

  @Override
  public Object[] getAttributes() throws SQLException {
    String val = getValue();
    if (val == null) {
      return new Object[0];
    }
    return PostgresStructConverter.parsePostgresStruct(val);
  }

  @Override
  public Object[] getAttributes(Map<String, Class<?>> map) throws SQLException {
    return getAttributes();
  }
}
