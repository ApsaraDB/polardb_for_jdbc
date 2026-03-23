/*
 * Reproduce and fix: setObject(paramIdx, LocalDateTime, Types.DATE) throws
 * "Bad value for type timestamp/date/time: 2026-03-23T16:55:38.401"
 *
 * Root cause: PgPreparedStatement.setObject(Types.DATE) had no branch for
 * LocalDateTime, so it fell through to in.toString() which produces
 * ISO 8601 format "2026-03-23T16:55:38.401" (with 'T' separator).
 * parseBackendTimestamp cannot handle the 'T' separator and throws
 * "Trailing junk on timestamp: 'T16:55:38.401'".
 *
 * Fix: Added LocalDateTime branch in Types.DATE case that extracts the
 * date part via ((LocalDateTime) in).toLocalDate().
 *
 * Customer scenario: Spring JdbcTemplate calls
 * cs.setObject(paramIdx, localDateTime, Types.DATE) for a stored procedure
 * parameter of type DATE, where the value comes from a LinkedHashMap
 * containing a LocalDateTime instance.
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Properties;

/**
 * Tests that passing a LocalDateTime via setObject(paramIdx, localDateTime,
 * Types.DATE) works correctly, both with resetNlsFormat=true and false.
 *
 * <p>This simulates the Spring JdbcTemplate / SimpleJdbcCall scenario
 * where parameter maps contain LocalDateTime values and the target
 * SQL type is DATE.
 */
public class LocalDateTimeSetObjectTest {

  private Connection conn;
  private Connection connNlsOff;

  @Before
  public void setUp() throws Exception {
    // Connection with resetNlsFormat=true (original customer scenario)
    Properties props = new Properties();
    props.setProperty("resetNlsFormat", "true");
    conn = TestUtil.openDB(props);

    // Connection with resetNlsFormat=false + Oracle NLS date format
    Properties propsNls = new Properties();
    propsNls.setProperty("resetNlsFormat", "false");
    connNlsOff = TestUtil.openDB(propsNls);

    Statement stmt = conn.createStatement();
    stmt.execute("DROP TABLE IF EXISTS ldt_test");
    stmt.execute("CREATE TABLE ldt_test ("
        + "  id   INTEGER,"
        + "  d    DATE,"
        + "  ts   TIMESTAMP"
        + ")");

    // Create a simple stored procedure with DATE IN parameter
    stmt.execute("CREATE OR REPLACE PROCEDURE ldt_proc("
        + "  p_id       IN INTEGER,"
        + "  p_date_in  IN DATE,"
        + "  p_date_out OUT VARCHAR2"
        + ") IS BEGIN"
        + "  INSERT INTO ldt_test (id, d) VALUES (p_id, p_date_in);"
        + "  p_date_out := TO_CHAR(p_date_in, 'YYYY-MM-DD');"
        + " END;");

    stmt.close();

    // Set Oracle-style date format on the NLS-off connection
    Statement stmtNls = connNlsOff.createStatement();
    stmtNls.execute("SET nls_date_format = 'DD-Mon-YYYY'");
    stmtNls.close();
  }

  @After
  public void tearDown() throws Exception {
    if (connNlsOff != null && !connNlsOff.isClosed()) {
      connNlsOff.close();
    }
    if (conn == null || conn.isClosed()) {
      return;
    }
    Statement stmt = conn.createStatement();
    try {
      stmt.execute("DROP PROCEDURE IF EXISTS ldt_proc(INTEGER, DATE, VARCHAR2)");
    } catch (Exception ignored) {
      // ignore
    }
    try {
      stmt.execute("DROP TABLE IF EXISTS ldt_test");
    } catch (Exception ignored) {
      // ignore
    }
    stmt.close();
    conn.close();
  }

  // ===================================================================
  // 1. Exact customer scenario (resetNlsFormat=true)
  // CallableStatement.setObject(paramIdx, LocalDateTime, Types.DATE)
  // ===================================================================

  @Test
  public void testSetObjectLocalDateTimeAsDateInCallableStatement() throws SQLException {
    LocalDateTime ldt = LocalDateTime.of(2026, 3, 23, 16, 55, 38, 401000000);

    CallableStatement cs = conn.prepareCall("{ call ldt_proc(?, ?, ?) }");
    cs.setInt(1, 1);
    cs.setObject(2, ldt, Types.DATE);
    cs.registerOutParameter(3, Types.VARCHAR);
    cs.execute();

    String dateOut = cs.getString(3);
    assertNotNull("OUT parameter should not be null", dateOut);
    assertEquals("2026-03-23", dateOut);
    cs.close();

    verifyDate(conn, 1, 2026, 3, 23);
  }

  // ===================================================================
  // 2. PreparedStatement (resetNlsFormat=true)
  // ===================================================================

  @Test
  public void testSetObjectLocalDateTimeAsDateInPreparedStatement() throws SQLException {
    LocalDateTime ldt = LocalDateTime.of(2026, 3, 23, 16, 55, 38, 401000000);

    PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO ldt_test (id, d) VALUES (?, ?)");
    ps.setInt(1, 2);
    ps.setObject(2, ldt, Types.DATE);
    ps.executeUpdate();
    ps.close();

    verifyDate(conn, 2, 2026, 3, 23);
  }

  // ===================================================================
  // 3. setObject with LocalDateTime and Types.TIMESTAMP (comparison)
  // ===================================================================

  @Test
  public void testSetObjectLocalDateTimeAsTimestampWorks() throws SQLException {
    LocalDateTime ldt = LocalDateTime.of(2026, 3, 23, 16, 55, 38, 401000000);

    PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO ldt_test (id, ts) VALUES (?, ?)");
    ps.setInt(1, 3);
    ps.setObject(2, ldt, Types.TIMESTAMP);
    ps.executeUpdate();
    ps.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT ts FROM ldt_test WHERE id = 3");
    assertTrue(rs.next());
    Timestamp ts = rs.getTimestamp("ts");
    assertNotNull(ts);
    assertEquals(2026, ts.toLocalDateTime().getYear());
    assertEquals(3, ts.toLocalDateTime().getMonthValue());
    assertEquals(23, ts.toLocalDateTime().getDayOfMonth());
    assertEquals(16, ts.toLocalDateTime().getHour());
    assertEquals(55, ts.toLocalDateTime().getMinute());
    assertEquals(38, ts.toLocalDateTime().getSecond());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 4. begin...end block syntax (Oracle-compatible, resetNlsFormat=true)
  // ===================================================================

  @Test
  public void testSetObjectLocalDateTimeAsDateInBeginEndBlock() throws SQLException {
    LocalDateTime ldt = LocalDateTime.of(2026, 3, 23, 16, 55, 38, 401000000);

    CallableStatement cs = conn.prepareCall(
        "begin ldt_proc(?, ?, ?); end;");
    cs.setInt(1, 4);
    cs.setObject(2, ldt, Types.DATE);
    cs.registerOutParameter(3, Types.VARCHAR);
    cs.execute();

    String dateOut = cs.getString(3);
    assertNotNull(dateOut);
    assertEquals("2026-03-23", dateOut);
    cs.close();
  }

  // ===================================================================
  // 5. Simulates Spring LinkedHashMap scenario (resetNlsFormat=true)
  // ===================================================================

  @Test
  public void testSpringStyleLinkedHashMapWithLocalDateTime() throws SQLException {
    java.util.LinkedHashMap<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("p_id", 5);
    params.put("p_date_in", LocalDateTime.of(2026, 3, 23, 16, 55, 38, 401000000));

    PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO ldt_test (id, d) VALUES (?, ?)");
    ps.setObject(1, params.get("p_id"), Types.INTEGER);
    ps.setObject(2, params.get("p_date_in"), Types.DATE);
    ps.executeUpdate();
    ps.close();

    verifyDate(conn, 5, 2026, 3, 23);
  }

  // ===================================================================
  // 6. resetNlsFormat=false + nls_date_format='DD-Mon-YYYY'
  //    CallableStatement with LocalDateTime as DATE
  // ===================================================================

  @Test
  public void testNlsOffCallableStatementLocalDateTimeAsDate() throws SQLException {
    LocalDateTime ldt = LocalDateTime.of(2026, 3, 23, 16, 55, 38, 401000000);

    CallableStatement cs = connNlsOff.prepareCall("{ call ldt_proc(?, ?, ?) }");
    cs.setInt(1, 10);
    cs.setObject(2, ldt, Types.DATE);
    cs.registerOutParameter(3, Types.VARCHAR);
    cs.execute();

    String dateOut = cs.getString(3);
    assertNotNull(dateOut);
    assertEquals("2026-03-23", dateOut);
    cs.close();

    verifyDate(connNlsOff, 10, 2026, 3, 23);
  }

  // ===================================================================
  // 7. resetNlsFormat=false + nls_date_format='DD-Mon-YYYY'
  //    PreparedStatement with LocalDateTime as DATE
  // ===================================================================

  @Test
  public void testNlsOffPreparedStatementLocalDateTimeAsDate() throws SQLException {
    LocalDateTime ldt = LocalDateTime.of(2026, 3, 23, 16, 55, 38, 401000000);

    PreparedStatement ps = connNlsOff.prepareStatement(
        "INSERT INTO ldt_test (id, d) VALUES (?, ?)");
    ps.setInt(1, 11);
    ps.setObject(2, ldt, Types.DATE);
    ps.executeUpdate();
    ps.close();

    verifyDate(connNlsOff, 11, 2026, 3, 23);
  }

  // ===================================================================
  // 8. resetNlsFormat=false + begin...end block with LocalDateTime
  // ===================================================================

  @Test
  public void testNlsOffBeginEndBlockLocalDateTimeAsDate() throws SQLException {
    LocalDateTime ldt = LocalDateTime.of(2026, 3, 23, 16, 55, 38, 401000000);

    CallableStatement cs = connNlsOff.prepareCall(
        "begin ldt_proc(?, ?, ?); end;");
    cs.setInt(1, 12);
    cs.setObject(2, ldt, Types.DATE);
    cs.registerOutParameter(3, Types.VARCHAR);
    cs.execute();

    String dateOut = cs.getString(3);
    assertNotNull(dateOut);
    assertEquals("2026-03-23", dateOut);
    cs.close();
  }

  // ===================================================================
  // 9. resetNlsFormat=false, Spring LinkedHashMap scenario
  // ===================================================================

  @Test
  public void testNlsOffSpringStyleLinkedHashMap() throws SQLException {
    java.util.LinkedHashMap<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("p_id", 13);
    params.put("p_date_in", LocalDateTime.of(2026, 3, 23, 16, 55, 38, 401000000));

    PreparedStatement ps = connNlsOff.prepareStatement(
        "INSERT INTO ldt_test (id, d) VALUES (?, ?)");
    ps.setObject(1, params.get("p_id"), Types.INTEGER);
    ps.setObject(2, params.get("p_date_in"), Types.DATE);
    ps.executeUpdate();
    ps.close();

    verifyDate(connNlsOff, 13, 2026, 3, 23);
  }

  // ===================================================================
  // 10. resetNlsFormat=false, read back with getDate after Oracle-format insert
  // ===================================================================

  @Test
  public void testNlsOffLocalDateTimeInsertAndGetDateReadback() throws SQLException {
    LocalDateTime ldt = LocalDateTime.of(2024, 12, 25, 9, 30, 0);

    PreparedStatement ps = connNlsOff.prepareStatement(
        "INSERT INTO ldt_test (id, d) VALUES (?, ?)");
    ps.setInt(1, 14);
    ps.setObject(2, ldt, Types.DATE);
    ps.executeUpdate();
    ps.close();

    verifyDate(connNlsOff, 14, 2024, 12, 25);
  }

  // ===================================================================
  // 11. resetNlsFormat=false, LocalDateTime with Types.TIMESTAMP
  // ===================================================================

  @Test
  public void testNlsOffLocalDateTimeAsTimestamp() throws SQLException {
    LocalDateTime ldt = LocalDateTime.of(2026, 3, 23, 16, 55, 38, 401000000);

    PreparedStatement ps = connNlsOff.prepareStatement(
        "INSERT INTO ldt_test (id, ts) VALUES (?, ?)");
    ps.setInt(1, 15);
    ps.setObject(2, ldt, Types.TIMESTAMP);
    ps.executeUpdate();
    ps.close();

    Statement stmt = connNlsOff.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT ts FROM ldt_test WHERE id = 15");
    assertTrue(rs.next());
    Timestamp ts = rs.getTimestamp("ts");
    assertNotNull(ts);
    assertEquals(2026, ts.toLocalDateTime().getYear());
    assertEquals(16, ts.toLocalDateTime().getHour());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // Helper: verify date in ldt_test table
  // ===================================================================

  private void verifyDate(Connection c, int id, int year, int month, int day)
      throws SQLException {
    Statement stmt = c.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT d FROM ldt_test WHERE id = " + id);
    assertTrue("Row should exist for id=" + id, rs.next());
    Date d = rs.getDate("d");
    assertNotNull("Date should not be null for id=" + id, d);
    assertEquals("Year mismatch", year, d.toLocalDate().getYear());
    assertEquals("Month mismatch", month, d.toLocalDate().getMonthValue());
    assertEquals("Day mismatch", day, d.toLocalDate().getDayOfMonth());
    rs.close();
    stmt.close();
  }
}
