/*
 * Portions Copyright (c) 2023, Alibaba Group Holding Limited
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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Properties;

/**
 * POLAR: Test ORADATE (Oracle-compatible DATE) type behavior with mapDateToTimestamp=true.
 *
 * <p>In PolarDB Oracle-compatibility mode, DATE is sys.date (OID=9002, ORADATE).
 * When mapDateToTimestamp=true (default), ORADATE is mapped to TIMESTAMP internally.
 *
 * <p>Behavior rules:
 * <ul>
 *   <li>From table SELECT: getObject() returns Timestamp (Oracle behavior)</li>
 *   <li>CallableStatement with registerOutParameter(n, Types.DATE):
 *       getObject() returns java.sql.Date</li>
 *   <li>getTimestamp() always returns Timestamp with full time precision</li>
 *   <li>getDate() always returns java.sql.Date</li>
 *   <li>getString() always returns string with full time (yyyy-MM-dd HH:mm:ss)</li>
 * </ul>
 */
public class OraDateGetObjectTest {
  private Connection conn;
  private Statement stmt;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("mapDateToTimestamp", "true");
    conn = TestUtil.openDB(props);
    stmt = conn.createStatement();

    // Create table with ORADATE column (Oracle-mode DATE = sys.date, OID=9002)
    stmt.execute("DROP TABLE IF EXISTS test_oradate_getobj");
    stmt.execute("CREATE TABLE test_oradate_getobj ("
        + "id int, "
        + "d date, "               // ORADATE in Oracle mode
        + "ts timestamp"           // regular TIMESTAMP for comparison
        + ")");

    // Insert test data with time portion
    stmt.execute("INSERT INTO test_oradate_getobj VALUES "
        + "(1, TO_DATE('2025-06-15 10:30:45', 'YYYY-MM-DD HH24:MI:SS'), "
        + "   TO_TIMESTAMP('2025-06-15 10:30:45', 'YYYY-MM-DD HH24:MI:SS'))");

    // Insert null row
    stmt.execute("INSERT INTO test_oradate_getobj VALUES (2, NULL, NULL)");

    // Create procedure with DATE OUT parameter
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE test_oradate_proc(p_out OUT date) IS "
            + "BEGIN p_out := TO_DATE('2025-06-15 10:30:45', 'YYYY-MM-DD HH24:MI:SS'); END;");
  }

  @After
  public void tearDown() throws Exception {
    stmt.execute("DROP TABLE IF EXISTS test_oradate_getobj");
    stmt.execute("DROP PROCEDURE IF EXISTS test_oradate_proc");
    stmt.close();
    conn.close();
  }

  // ==================== Case 1: SELECT date from table ====================

  /**
   * From table: getObject() on ORADATE column returns Timestamp (Oracle behavior).
   */
  @Test
  public void testTableSelectGetObject() throws SQLException {
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM test_oradate_getobj WHERE id = 1");
    assertTrue(rs.next());

    Object obj = rs.getObject(1);
    assertNotNull(obj);
    assertTrue("Table SELECT getObject on ORADATE should return Timestamp, got: "
        + obj.getClass().getName(), obj instanceof Timestamp);

    Timestamp ts = (Timestamp) obj;
    assertEquals("2025-06-15 10:30:45.0", ts.toString());

    rs.close();
  }

  /**
   * From table: getDate() returns java.sql.Date (date portion only).
   */
  @Test
  public void testTableSelectGetDate() throws SQLException {
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM test_oradate_getobj WHERE id = 1");
    assertTrue(rs.next());

    java.sql.Date date = rs.getDate(1);
    assertNotNull("getDate should not be null", date);
    assertEquals("2025-06-15", date.toString());

    rs.close();
  }

  /**
   * From table: getTimestamp() returns Timestamp with full time precision.
   */
  @Test
  public void testTableSelectGetTimestamp() throws SQLException {
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM test_oradate_getobj WHERE id = 1");
    assertTrue(rs.next());

    Timestamp ts = rs.getTimestamp(1);
    assertNotNull("getTimestamp should not be null", ts);
    assertEquals("2025-06-15 10:30:45.0", ts.toString());

    rs.close();
  }

  /**
   * From table: getString() returns string with full time (hours:minutes:seconds).
   */
  @Test
  public void testTableSelectGetString() throws SQLException {
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM test_oradate_getobj WHERE id = 1");
    assertTrue(rs.next());

    String str = rs.getString(1);
    assertNotNull("getString should not be null", str);
    assertTrue("getString should contain time portion '10:30:45', got: " + str,
        str.contains("10:30:45"));

    rs.close();
  }

  // ==================== Case 2: Procedure OUT date parameter ====================

  /**
   * From procedure: getObject() with registerOutParameter(Types.DATE)
   * returns java.sql.Date.
   */
  @Test
  public void testProcedureGetObject() throws SQLException {
    Properties props = new Properties();
    props.put("mapDateToTimestamp", "true");
    props.put("callFunctionMode", "true");
    Connection csConn = TestUtil.openDB(props);

    try {
      CallableStatement cs = csConn.prepareCall("begin test_oradate_proc(?); end;");
      cs.registerOutParameter(1, Types.DATE);
      cs.execute();

      Object obj = cs.getObject(1);
      assertNotNull("Procedure getObject should not be null", obj);
      assertTrue("Procedure getObject with registered DATE should return java.sql.Date, got: "
          + obj.getClass().getName(), obj instanceof java.sql.Date);
      // java.sql.Date is also instanceof java.util.Date
      assertTrue(obj instanceof java.util.Date);

      cs.close();
    } finally {
      csConn.close();
    }
  }

  /**
   * From procedure: getDate() returns java.sql.Date.
   */
  @Test
  public void testProcedureGetDate() throws SQLException {
    Properties props = new Properties();
    props.put("mapDateToTimestamp", "true");
    props.put("callFunctionMode", "true");
    Connection csConn = TestUtil.openDB(props);

    try {
      CallableStatement cs = csConn.prepareCall("begin test_oradate_proc(?); end;");
      cs.registerOutParameter(1, Types.DATE);
      cs.execute();

      java.sql.Date date = cs.getDate(1);
      assertNotNull("Procedure getDate should not be null", date);
      assertEquals("2025-06-15", date.toString());

      cs.close();
    } finally {
      csConn.close();
    }
  }

  /**
   * From procedure: getTimestamp() returns Timestamp with full time (hours:minutes:seconds).
   */
  @Test
  public void testProcedureGetTimestamp() throws SQLException {
    Properties props = new Properties();
    props.put("mapDateToTimestamp", "true");
    props.put("callFunctionMode", "true");
    Connection csConn = TestUtil.openDB(props);

    try {
      CallableStatement cs = csConn.prepareCall("begin test_oradate_proc(?); end;");
      cs.registerOutParameter(1, Types.DATE);
      cs.execute();

      Timestamp ts = cs.getTimestamp(1);
      assertNotNull("Procedure getTimestamp should not be null", ts);
      assertEquals("2025-06-15 10:30:45.0", ts.toString());

      cs.close();
    } finally {
      csConn.close();
    }
  }

  /**
   * From procedure: getString() returns string with full time.
   */
  @Test
  public void testProcedureGetString() throws SQLException {
    Properties props = new Properties();
    props.put("mapDateToTimestamp", "true");
    props.put("callFunctionMode", "true");
    Connection csConn = TestUtil.openDB(props);

    try {
      CallableStatement cs = csConn.prepareCall("begin test_oradate_proc(?); end;");
      cs.registerOutParameter(1, Types.DATE);
      cs.execute();

      String str = cs.getString(1);
      assertNotNull("Procedure getString should not be null", str);
      assertTrue("Procedure getString should contain time '10:30:45', got: " + str,
          str.contains("10:30:45"));

      cs.close();
    } finally {
      csConn.close();
    }
  }

  // ==================== Null & Metadata Tests ====================

  /**
   * Null ORADATE from table: getObject() returns null.
   */
  @Test
  public void testTableSelectNullGetObject() throws SQLException {
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM test_oradate_getobj WHERE id = 2");
    assertTrue(rs.next());
    assertNull("getObject on null ORADATE should return null", rs.getObject(1));
    rs.close();
  }

  /**
   * ORADATE column metadata should report SQL type as TIMESTAMP.
   */
  @Test
  public void testColumnMetadataReportsTimestamp() throws SQLException {
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM test_oradate_getobj WHERE id = 1");
    ResultSetMetaData meta = rs.getMetaData();
    assertEquals("ORADATE column SQL type should be TIMESTAMP",
        Types.TIMESTAMP, meta.getColumnType(1));
    rs.close();
  }

  /**
   * Comparison: regular TIMESTAMP column getObject() returns Timestamp.
   */
  @Test
  public void testRegularTimestampGetObject() throws SQLException {
    ResultSet rs = stmt.executeQuery(
        "SELECT ts FROM test_oradate_getobj WHERE id = 1");
    assertTrue(rs.next());
    Object obj = rs.getObject(1);
    assertNotNull(obj);
    assertTrue("Regular TIMESTAMP getObject should return Timestamp",
        obj instanceof Timestamp);
    rs.close();
  }
}
