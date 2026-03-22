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
import java.util.ArrayList;
import java.util.List;
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

  /**
   * Parses a PostgreSQL composite type literal into an array of attribute values.
   * This is the reverse of {@link #objectArrayToPostgresStruct(Object[])}.
   *
   * <p>Format: {@code (field1,field2,...)} where:
   * <ul>
   *   <li>Empty field (nothing between commas) represents NULL</li>
   *   <li>Quoted field ({@code "value"}) is a string; inner double-quotes are escaped
   *       as {@code ""}</li>
   *   <li>Unquoted field is a raw value (number, date, etc.)</li>
   * </ul>
   *
   * @param literal the composite type literal, e.g. {@code ("NFORPU",'2025-06-15',,1234.56)}
   * @return array of attribute values (String or null for each field)
   */
  public static Object[] parsePostgresStruct(String literal) {
    if (literal == null || literal.length() < 2) {
      return new Object[0];
    }
    // Strip outer parentheses
    String inner = literal.substring(1, literal.length() - 1);
    if (inner.isEmpty()) {
      return new Object[0];
    }

    List<Object> fields = new ArrayList<Object>();
    int pos = 0;
    // Track whether we just consumed a comma and expect another field
    boolean expectField = true;

    while (pos <= inner.length()) {
      if (pos == inner.length()) {
        // Reached end right after a comma → trailing empty (null) field
        if (expectField) {
          fields.add(null);
        }
        break;
      }

      char c = inner.charAt(pos);
      if (c == '"') {
        // ---- Quoted field ----
        pos++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < inner.length()) {
          if (inner.charAt(pos) == '"') {
            if (pos + 1 < inner.length() && inner.charAt(pos + 1) == '"') {
              sb.append('"'); // escaped double-quote
              pos += 2;
            } else {
              pos++; // closing quote
              break;
            }
          } else {
            sb.append(inner.charAt(pos));
            pos++;
          }
        }
        fields.add(sb.toString());
        expectField = false;
        if (pos < inner.length() && inner.charAt(pos) == ',') {
          pos++;
          expectField = true;
        }
      } else if (c == ',') {
        // ---- Empty field = NULL ----
        fields.add(null);
        pos++;
        expectField = true;
      } else {
        // ---- Unquoted field ----
        StringBuilder sb = new StringBuilder();
        while (pos < inner.length() && inner.charAt(pos) != ',') {
          sb.append(inner.charAt(pos));
          pos++;
        }
        fields.add(sb.toString());
        expectField = false;
        if (pos < inner.length() && inner.charAt(pos) == ',') {
          pos++;
          expectField = true;
        }
      }
    }

    return fields.toArray(new Object[0]);
  }
}
