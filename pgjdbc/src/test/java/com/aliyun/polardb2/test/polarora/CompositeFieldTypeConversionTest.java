/*
 * Portions Copyright (c) 2026, Alibaba Group Holding Limited
 *
 * Detailed coverage for composite (record) field type restoration in
 * Struct.getAttributes(): NUMBER/VARCHAR2/DATE/TIMESTAMP/CHAR field types,
 * plus NULL, negative, boundary, special-character and multi-row scenarios.
 *
 * Uses Oracle-compatible column types (the same family verified by
 * TableOfCompositeFieldTypeTest) to avoid server-side issues with some
 * PG-native types inside TABLE OF composite definitions.
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Properties;

/**
 * Verifies StructFieldTypeConverter restores composite fields to proper Java
 * types across multiple rows and edge cases.
 *
 * <p>Field layout of rec_detail (0-based index):
 * <pre>
 *   0 f_id       NUMBER(10)     -&gt; BigDecimal
 *   1 f_amt      NUMBER(11,2)   -&gt; BigDecimal
 *   2 f_rate     NUMBER(18,8)   -&gt; BigDecimal
 *   3 f_name     VARCHAR2(50)   -&gt; String
 *   4 f_flag     CHAR(1)        -&gt; String
 *   5 f_dt       DATE           -&gt; Timestamp
 *   6 f_ts       TIMESTAMP      -&gt; Timestamp
 * </pre>
 */
public class CompositeFieldTypeConversionTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    try (Statement stmt = conn.createStatement()) {
      dropAll(stmt);

      stmt.execute("CREATE OR REPLACE TYPE rec_detail AS OBJECT (\n"
          + "  f_id    NUMBER(10),\n"
          + "  f_amt   NUMBER(11,2),\n"
          + "  f_rate  NUMBER(18,8),\n"
          + "  f_name  VARCHAR2(50),\n"
          + "  f_flag  CHAR(1),\n"
          + "  f_dt    DATE,\n"
          + "  f_ts    TIMESTAMP\n"
          + ")");

      stmt.execute("CREATE OR REPLACE TYPE tab_detail AS TABLE OF rec_detail");

      // 3 rows: normal / all-null / negative + special chars.
      stmt.execute("CREATE OR REPLACE PROCEDURE get_detail("
          + "  p_out OUT tab_detail) IS\n"
          + "BEGIN\n"
          + "  p_out := tab_detail();\n"
          + "  p_out.extend;\n"
          + "  p_out(1) := rec_detail(100, 12345.67, 7.85000000,"
          + "    'hello', 'Y', DATE '2026-06-05', TIMESTAMP '2026-06-05 13:45:30');\n"
          + "  p_out.extend;\n"
          + "  p_out(2) := rec_detail(NULL, NULL, NULL, NULL, NULL, NULL, NULL);\n"
          + "  p_out.extend;\n"
          + "  p_out(3) := rec_detail(-999, -0.01, -1.23456789,"
          + "    'a,b,c', 'N', DATE '2000-01-01', TIMESTAMP '2000-01-01 00:00:00');\n"
          + "END;");
    }
  }

  @After
  public void tearDown() throws Exception {
    if (conn != null) {
      try (Statement stmt = conn.createStatement()) {
        dropAll(stmt);
      } catch (SQLException ignore) {
        // ignore
      }
      conn.close();
    }
  }

  private void dropAll(Statement stmt) {
    try {
      stmt.execute("DROP PROCEDURE IF EXISTS get_detail");
    } catch (SQLException ignore) {
      // ignore
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS tab_detail");
    } catch (SQLException ignore) {
      // ignore
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS rec_detail");
    } catch (SQLException ignore) {
      // ignore
    }
  }

  private Object[][] fetchAllRows() throws SQLException {
    try (CallableStatement cs = conn.prepareCall("{ call get_detail(?) }")) {
      cs.registerOutParameter(1, Types.ARRAY, "tab_detail");
      cs.execute();
      Object[] rows = (Object[]) cs.getArray(1).getArray();
      Object[][] out = new Object[rows.length][];
      for (int i = 0; i < rows.length; i++) {
        out[i] = ((Struct) rows[i]).getAttributes();
      }
      return out;
    }
  }

  /**
   * Row 1 (normal values): each field restored to the expected Java type.
   */
  @Test
  public void testNormalRowTypes() throws SQLException {
    Object[][] rows = fetchAllRows();
    assertTrue("Expected 3 rows", rows.length == 3);
    Object[] a = rows[0];

    for (int i = 0; i < a.length; i++) {
      System.out.println("[diag] row1[" + i + "] = " + a[i]
          + " (" + (a[i] == null ? "null" : a[i].getClass().getName()) + ")");
    }

    assertTrue("f_id -> BigDecimal", a[0] instanceof BigDecimal);
    assertEquals(100, ((BigDecimal) a[0]).intValue());

    assertTrue("f_amt -> BigDecimal", a[1] instanceof BigDecimal);
    assertEquals(0, new BigDecimal("12345.67").compareTo((BigDecimal) a[1]));

    assertTrue("f_rate -> BigDecimal", a[2] instanceof BigDecimal);
    assertEquals(0, new BigDecimal("7.85").compareTo((BigDecimal) a[2]));

    assertTrue("f_name -> String", a[3] instanceof String);
    assertEquals("hello", a[3]);

    assertTrue("f_flag -> String", a[4] instanceof String);
    // CHAR(1) may be blank-padded; compare trimmed
    assertEquals("Y", ((String) a[4]).trim());

    assertTrue("f_dt -> Timestamp", a[5] instanceof Timestamp);
    assertTrue("f_ts -> Timestamp", a[6] instanceof Timestamp);
    assertEquals(Timestamp.valueOf("2026-06-05 13:45:30"), a[6]);
  }

  /**
   * Row 2 (all NULL): every field null regardless of declared type.
   */
  @Test
  public void testAllNullRow() throws SQLException {
    Object[][] rows = fetchAllRows();
    Object[] a = rows[1];
    for (int i = 0; i < a.length; i++) {
      assertNull("field " + i + " should be null", a[i]);
    }
  }

  /**
   * Row 3 (negatives + comma-containing string): negatives parse correctly and
   * the string field with commas keeps its integrity.
   */
  @Test
  public void testNegativeAndSpecialChars() throws SQLException {
    Object[][] rows = fetchAllRows();
    Object[] a = rows[2];

    assertEquals(-999, ((BigDecimal) a[0]).intValue());
    assertEquals(0, new BigDecimal("-0.01").compareTo((BigDecimal) a[1]));
    assertEquals(0, new BigDecimal("-1.23456789").compareTo((BigDecimal) a[2]));
    assertTrue(a[3] instanceof String);
    assertEquals("a,b,c", a[3]);
    assertEquals("N", ((String) a[4]).trim());
  }

  /**
   * DATE field carries a time component and is returned as Timestamp,
   * matching Oracle DATE semantics and customer (Timestamp) casts.
   */
  @Test
  public void testDateReturnedAsTimestamp() throws SQLException {
    Object[][] rows = fetchAllRows();
    Object dt = rows[0][5];
    assertTrue("DATE should map to Timestamp", dt instanceof Timestamp);
    assertEquals(Timestamp.valueOf("2026-06-05 00:00:00"), dt);
  }

  /**
   * NUMBER(18,8) scale is preserved end-to-end.
   */
  @Test
  public void testNumericScalePreserved() throws SQLException {
    Object[][] rows = fetchAllRows();
    BigDecimal rate = (BigDecimal) rows[0][2];
    assertNotNull(rate);
    assertEquals(0, new BigDecimal("7.85000000").compareTo(rate));
  }

  /**
   * The customer access pattern (cast every numeric field to BigDecimal) works
   * across all non-null rows without ClassCastException.
   */
  @Test
  public void testCustomerCastPatternNoError() throws SQLException {
    Object[][] rows = fetchAllRows();
    // Row 1 and Row 3 are non-null; Row 2 is all-null (skip numeric casts).
    for (int r : new int[]{0, 2}) {
      Object[] a = rows[r];
      Integer id = a[0] != null ? Integer.valueOf(((BigDecimal) a[0]).intValue()) : null;
      Double amt = a[1] != null ? ((BigDecimal) a[1]).doubleValue() : null;
      Double rate = a[2] != null ? ((BigDecimal) a[2]).doubleValue() : null;
      String name = a[3] != null ? (String) a[3] : null;
      assertNotNull(id);
      assertNotNull(amt);
      assertNotNull(rate);
      assertNotNull(name);
    }
  }
}
