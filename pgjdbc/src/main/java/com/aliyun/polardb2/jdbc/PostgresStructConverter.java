/*
 * Copyright (c) 2025, Alibaba Group Holding Limited
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.jdbc;

import com.aliyun.polardb2.util.PGobject;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.UUID;

public class PostgresStructConverter {

  public static String objectArrayToPostgresStruct(Object[] attributes) {
    if (attributes == null) {
      return "NULL";
    }

    StringBuilder sb = new StringBuilder("(");

    for (int i = 0; i < attributes.length; i++) {
      if (i > 0) {
        sb.append(",");
      }

      appendPostgresValue(sb, attributes[i]);
    }

    sb.append(")");
    return sb.toString();
  }

  /**
   * Appends a properly formatted and escaped value to the StringBuilder based on its type.
   *
   * @param sb StringBuilder to append to
   * @param value The value to append
   */
  private static void appendPostgresValue(StringBuilder sb, Object value) {
    if (value == null) {
      /* POLAR DIFF: PolarDB/PostgreSQL composite type literal syntax uses empty field
       * (nothing between commas) to represent NULL, not the literal word "NULL".
       * Outputting "NULL" causes: ERROR: invalid input syntax for type date: "NULL"
       * when the field type is DATE/TIME/TIMESTAMP and the value is null.
       * See: https://www.postgresql.org/docs/current/rowtypes.html#ROWTYPES-IO-SYNTAX
       */
      // Leave empty: caller writes only the comma separator
      // POLAR DIFF end
      return;
    }

    // Handle different types
    if (value instanceof String) {
      appendString(sb, (String) value);
    } else if (value instanceof Integer || value instanceof Long
        || value instanceof Short || value instanceof Byte) {
      sb.append(value.toString());
    } else if (value instanceof Float || value instanceof Double
        || value instanceof BigDecimal) {
      sb.append(value.toString());
    } else if (value instanceof Boolean) {
      sb.append(((Boolean) value) ? "true" : "false");
    } else if (value instanceof Date) {
      appendDate(sb, (Date) value);
    } else if (value instanceof Time) {
      appendTime(sb, (Time) value);
    } else if (value instanceof Timestamp) {
      appendTimestamp(sb, (Timestamp) value);
    } else if (value instanceof java.util.Date) {
      appendTimestamp(sb, new Timestamp(((java.util.Date) value).getTime()));
    } else if (value instanceof byte[]) {
      appendBinary(sb, (byte[]) value);
    } else if (value instanceof UUID) {
      appendUUID(sb, (UUID) value);
    } else if (value instanceof PGobject) {
      appendPGobject(sb, (PGobject) value);
    } else {
      // Default to string representation
      appendString(sb, value.toString());
    }
  }

  /**
   * Appends a string value with proper escaping for PostgreSQL.
   *
   * @param sb StringBuilder to append to
   * @param value String value to append
   */
  private static void appendString(StringBuilder sb, String value) {
    // PostgreSQL uses double quotes for string literals in composite types
    // Double quotes within the string need to be escaped by doubling them
    sb.append("\"");
    sb.append(value.replace("\"", "\"\""));
    sb.append("\"");
  }

  /**
   * Appends a date value in PostgreSQL format.
   *
   * @param sb StringBuilder to append to
   * @param value Date value to append
   */
  private static void appendDate(StringBuilder sb, Date value) {
    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    sb.append("'").append(dateFormat.format(value)).append("'");
  }

  /**
   * Appends a time value in PostgreSQL format.
   *
   * @param sb StringBuilder to append to
   * @param value Time value to append
   */
  private static void appendTime(StringBuilder sb, Time value) {
    SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    sb.append("'").append(timeFormat.format(value)).append("'");
  }

  /**
   * Appends a timestamp value in PostgreSQL format.
   *
   * @param sb StringBuilder to append to
   * @param value Timestamp value to append
   */
  private static void appendTimestamp(StringBuilder sb, Timestamp value) {
    SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    sb.append("'").append(timestampFormat.format(value)).append("'");
  }

  /**
   * Appends binary data in PostgreSQL hexadecimal format.
   *
   * @param sb StringBuilder to append to
   * @param value Binary data to append
   */
  private static void appendBinary(StringBuilder sb, byte[] value) {
    sb.append("'\\x");
    for (byte b : value) {
      sb.append(String.format("%02x", b));
    }
    sb.append("'");
  }

  /**
   * Appends a UUID value.
   *
   * @param sb StringBuilder to append to
   * @param value UUID to append
   */
  private static void appendUUID(StringBuilder sb, UUID value) {
    sb.append("'").append(value.toString()).append("'");
  }

  /**
   * Appends a PGobject value.
   *
   * @param sb StringBuilder to append to
   * @param value PGobject to append
   */
  private static void appendPGobject(StringBuilder sb, PGobject value) {
    if (value.getValue() == null) {
      sb.append("NULL");
    } else {
      // Handle special PGobject types
      if ("json".equals(value.getType()) || "jsonb".equals(value.getType())) {
        sb.append("'").append(value.getValue().replace("'", "''")).append("'");
      } else {
        sb.append("'").append(value.getValue().replace("'", "''")).append("'");
      }
    }
  }
}
