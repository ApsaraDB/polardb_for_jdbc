/*
 * Copyright (c) 2025, Alibaba Group Holding Limited
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.jdbc;

import com.aliyun.polardb2.core.BaseConnection;
import com.aliyun.polardb2.core.Oid;
import com.aliyun.polardb2.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

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

  /* POLAR: connection used to look up the composite field type OIDs so that
   * getAttributes() can restore each field to its proper Java type (NUMBER ->
   * BigDecimal, DATE/TIMESTAMP -> Timestamp, etc.). May be null (e.g. legacy
   * construction), in which case getAttributes() falls back to all-String. */
  private transient @Nullable BaseConnection connection;

  public void setConnection(@Nullable BaseConnection connection) {
    this.connection = connection;
  }

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
    Object[] raw = PostgresStructConverter.parsePostgresStruct(val);

    // POLAR: Restore field Java types using the composite type's attribute OIDs.
    // Falls back to all-String when connection/type metadata is unavailable.
    BaseConnection c = this.connection;
    String typeName = getType();
    if (c == null || typeName == null || typeName.isEmpty()) {
      return raw;
    }
    int typeOid = c.getTypeInfo().getPGType(typeName.toLowerCase(java.util.Locale.ROOT));
    if (typeOid == Oid.UNSPECIFIED) {
      typeOid = c.getTypeInfo().getPGType(typeName);
    }
    if (typeOid == Oid.UNSPECIFIED) {
      return raw;
    }
    int @Nullable [] fieldOids = c.getTypeInfo().getCompositeFieldTypeOids(typeOid);
    if (fieldOids == null) {
      return raw;
    }
    for (int i = 0; i < raw.length && i < fieldOids.length; i++) {
      if (raw[i] instanceof String) {
        raw[i] = StructFieldTypeConverter.convert((String) raw[i], fieldOids[i], c);
      }
    }
    return raw;
  }

  @Override
  public Object[] getAttributes(Map<String, Class<?>> map) throws SQLException {
    return getAttributes();
  }
}
