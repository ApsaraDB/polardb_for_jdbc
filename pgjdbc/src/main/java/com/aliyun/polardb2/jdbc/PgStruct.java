/*
 * Copyright (c) 2025, Alibaba Group Holding Limited
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.jdbc;

import java.sql.SQLException;
import java.sql.Struct;
import java.util.Map;

public class PgStruct implements Struct {
  private final String typeName;
  private final Object[] attributes;

  public PgStruct(String typeName, Object[] attributes) {
    this.typeName = typeName;
    this.attributes = attributes != null ? attributes.clone() : null;
  }

  @Override
  public String getSQLTypeName() throws SQLException {
    return typeName;
  }

  @Override
  public Object[] getAttributes() throws SQLException {
    return attributes != null ? attributes.clone() : null;
  }

  @Override
  public Object[] getAttributes(Map<String, Class<?>> map) throws SQLException {
    return getAttributes();
  }

  @Override
  public String toString() {
    try {
      return PostgresStructConverter.objectArrayToPostgresStruct(attributes);
    } catch (Exception e) {
      return super.toString();
    }
  }
}
