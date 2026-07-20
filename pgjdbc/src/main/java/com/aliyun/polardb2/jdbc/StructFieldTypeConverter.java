/*
 * Copyright (c) 2026, Alibaba Group Holding Limited
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.jdbc;

import com.aliyun.polardb2.core.BaseConnection;
import com.aliyun.polardb2.core.Oid;

/**
 * POLAR: Converts a single composite (record) field, parsed as raw text, into
 * the Java object matching its PostgreSQL field type OID.
 *
 * <p>This restores Oracle ojdbc behavior for {@link java.sql.Struct#getAttributes()}:
 * NUMBER fields become {@link java.math.BigDecimal}, DATE/TIMESTAMP become
 * {@link java.sql.Timestamp}, integer types become Integer/Long/Short, etc.
 * Without this, all fields are returned as {@link String} and customer casts
 * like {@code (BigDecimal) attributes[i]} throw {@link ClassCastException}.
 *
 * <p>Any conversion failure falls back to the original String so a single
 * unparseable field never aborts reading the whole record.
 */
final class StructFieldTypeConverter {

  private StructFieldTypeConverter() {
  }

  /**
   * Convert a raw field string to a Java object based on its field type OID.
   *
   * @param raw the raw field text (never null; callers skip null fields)
   * @param fieldOid the PostgreSQL type OID of the field
   * @param conn the connection (for TimestampUtils)
   * @return the converted value, or the original {@code raw} on failure / unknown type
   */
  static Object convert(String raw, int fieldOid, BaseConnection conn) {
    try {
      switch (fieldOid) {
        case Oid.INT2:
          return Short.valueOf(raw.trim());
        case Oid.INT4:
          return Integer.valueOf(raw.trim());
        case Oid.INT8:
        case Oid.OID:
          return Long.valueOf(raw.trim());
        case Oid.FLOAT4:
          return Float.valueOf(raw.trim());
        case Oid.FLOAT8:
        case Oid.MONEY:
          return Double.valueOf(raw.trim());
        case Oid.NUMERIC:
          return PgResultSet.toBigDecimal(raw.trim());
        case Oid.BOOL:
          return toBoolean(raw);
        case Oid.DATE:
          // Oracle DATE carries time; align with customer's (Timestamp) cast.
          return conn.getTimestampUtils().toTimestamp(null, raw);
        case Oid.ORADATE:
          // POLAR: PolarDB Oracle-compatible DATE (ORADATE, oid 9002) also carries
          // a time component and is cast to Timestamp by Oracle-style client code.
          return conn.getTimestampUtils().toTimestamp(null, raw);
        case Oid.TIME:
        case Oid.TIMETZ:
          return conn.getTimestampUtils().toTime(null, raw);
        case Oid.TIMESTAMP:
        case Oid.TIMESTAMPTZ:
          return conn.getTimestampUtils().toTimestamp(null, raw);
        default:
          // VARCHAR / TEXT / CHAR / BPCHAR / UUID / json / unknown: keep String
          return raw;
      }
    } catch (Exception e) {
      // Never let a single field break the whole record; fall back to String.
      return raw;
    }
  }

  private static Boolean toBoolean(String raw) {
    String v = raw.trim();
    return "t".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v)
        || "1".equals(v) || "y".equalsIgnoreCase(v) ? Boolean.TRUE : Boolean.FALSE;
  }
}
