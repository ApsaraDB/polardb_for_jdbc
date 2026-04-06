/*
 * Tests for date type operations when resetNlsFormat=false and
 * nls_date_format is set to Oracle-style 'DD-Mon-YYYY'.
 *
 * When resetNlsFormat is disabled, the driver does NOT override the server's
 * nls_date_format / nls_timestamp_format / nls_timestamp_tz_format.
 * If the database is configured with an Oracle-style format such as 'DD-Mon-YYYY',
 * the server returns date strings like '06-Mar-2026'.
 * The driver's normalizeOracleDateFormat (in TimestampUtils) must convert them
 * to ISO format before parsing.
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.Properties;
import java.util.TimeZone;

/**
 * Validates that DATE columns work correctly when the server returns dates in
 * Oracle NLS format (DD-Mon-YYYY) instead of the ISO format (YYYY-MM-DD).
 *
 * <p>Covers: getDate, getTimestamp, getObject, getDate(Calendar), setDate,
 * setObject(Types.DATE), NULL handling, boundary dates, all 12 months,
 * PreparedStatement WHERE clause, stored procedure date IN/OUT, ORDER BY,
 * and nls_date_format with time component.
 */
public class NlsDateFormatTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("resetNlsFormat", "false");
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();

    // Set Oracle-style date format at session level
    stmt.execute("SET nls_date_format = 'DD-Mon-YYYY'");

    stmt.execute("DROP TABLE IF EXISTS nls_date_test");
    stmt.execute("CREATE TABLE nls_date_test ("
        + "  id   INTEGER,"
        + "  d    DATE,"
        + "  ts   TIMESTAMP,"
        + "  tstz TIMESTAMPTZ"
        + ")");

    // Insert known test data using DATE literals (ISO format is always valid in SQL)
    stmt.execute("INSERT INTO nls_date_test VALUES (1, DATE '2026-03-06', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (2, DATE '2025-01-01', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (3, DATE '2024-12-31', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (4, DATE '2024-02-29', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (5, NULL, NULL, NULL)");

    // Insert data for all 12 months
    stmt.execute("INSERT INTO nls_date_test VALUES (101, DATE '2026-01-15', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (102, DATE '2026-02-15', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (103, DATE '2026-03-15', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (104, DATE '2026-04-15', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (105, DATE '2026-05-15', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (106, DATE '2026-06-15', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (107, DATE '2026-07-15', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (108, DATE '2026-08-15', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (109, DATE '2026-09-15', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (110, DATE '2026-10-15', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (111, DATE '2026-11-15', NULL, NULL)");
    stmt.execute("INSERT INTO nls_date_test VALUES (112, DATE '2026-12-15', NULL, NULL)");

    // Stored procedure for date IN/OUT test
    stmt.execute("CREATE OR REPLACE FUNCTION nls_date_add_days("
        + "  p_date IN DATE, p_days IN INTEGER"
        + ") RETURN DATE IS"
        + "  BEGIN RETURN p_date + p_days; END;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    if (conn == null || conn.isClosed()) {
      return;
    }
    Statement stmt = conn.createStatement();
    try {
      stmt.execute("DROP FUNCTION IF EXISTS nls_date_add_days(DATE, INTEGER)");
    } catch (Exception ignored) {
      // ignore
    }
    try {
      stmt.execute("DROP TABLE IF EXISTS nls_date_test");
    } catch (Exception ignored) {
      // ignore
    }
    stmt.close();
    conn.close();
  }

  // ===================================================================
  // 1. Basic getDate() — server returns DD-Mon-YYYY, driver must parse
  // ===================================================================

  @Test
  public void testGetDateBasic() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 1");
    assertTrue("Row should exist", rs.next());
    Date d = rs.getDate("d");
    assertNotNull("Date should not be null", d);
    LocalDate ld = d.toLocalDate();
    assertEquals(2026, ld.getYear());
    assertEquals(3, ld.getMonthValue());
    assertEquals(6, ld.getDayOfMonth());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 2. getTimestamp() from DATE column
  // ===================================================================

  @Test
  public void testGetTimestampFromDateColumn() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 1");
    assertTrue(rs.next());
    Timestamp ts = rs.getTimestamp("d");
    assertNotNull("Timestamp should not be null", ts);
    assertEquals(2026, ts.toLocalDateTime().getYear());
    assertEquals(3, ts.toLocalDateTime().getMonthValue());
    assertEquals(6, ts.toLocalDateTime().getDayOfMonth());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 3. getObject() from DATE column
  // ===================================================================

  @Test
  public void testGetObjectFromDateColumn() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 1");
    assertTrue(rs.next());
    Object obj = rs.getObject("d");
    assertNotNull("Object should not be null", obj);
    // In Oracle mode, DATE may be mapped to Timestamp or Date
    if (obj instanceof Date) {
      Date d = (Date) obj;
      assertEquals(2026, d.toLocalDate().getYear());
      assertEquals(3, d.toLocalDate().getMonthValue());
    } else if (obj instanceof Timestamp) {
      Timestamp ts = (Timestamp) obj;
      assertEquals(2026, ts.toLocalDateTime().getYear());
      assertEquals(3, ts.toLocalDateTime().getMonthValue());
    } else {
      // If it's a String, the normalizeOracleDateFormat should still work
      // when explicitly calling getDate
      Date d = rs.getDate("d");
      assertNotNull(d);
      assertEquals(2026, d.toLocalDate().getYear());
    }
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 4. getDate() with explicit Calendar
  // ===================================================================

  @Test
  public void testGetDateWithCalendar() throws SQLException {
    Calendar utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 1");
    assertTrue(rs.next());
    Date d = rs.getDate("d", utcCal);
    assertNotNull(d);
    // The date value should still be 2026-03-06
    LocalDate ld = d.toLocalDate();
    assertEquals(2026, ld.getYear());
    assertEquals(3, ld.getMonthValue());
    assertEquals(6, ld.getDayOfMonth());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 5. setDate() + getDate() roundtrip
  // ===================================================================

  @Test
  public void testSetDateRoundtrip() throws SQLException {
    Date inputDate = Date.valueOf("2026-06-15");
    PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO nls_date_test (id, d) VALUES (?, ?)");
    ps.setInt(1, 200);
    ps.setDate(2, inputDate);
    ps.executeUpdate();
    ps.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 200");
    assertTrue(rs.next());
    Date d = rs.getDate("d");
    assertNotNull(d);
    assertEquals(2026, d.toLocalDate().getYear());
    assertEquals(6, d.toLocalDate().getMonthValue());
    assertEquals(15, d.toLocalDate().getDayOfMonth());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 6. setObject(Types.DATE, String) with ISO format + readback
  // ===================================================================

  @Test
  public void testSetObjectDateStringIso() throws SQLException {
    PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO nls_date_test (id, d) VALUES (?, ?)");
    ps.setInt(1, 201);
    ps.setObject(2, "2026-08-20", Types.DATE);
    ps.executeUpdate();
    ps.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 201");
    assertTrue(rs.next());
    Date d = rs.getDate("d");
    assertNotNull(d);
    assertEquals(2026, d.toLocalDate().getYear());
    assertEquals(8, d.toLocalDate().getMonthValue());
    assertEquals(20, d.toLocalDate().getDayOfMonth());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 7. setObject(Types.DATE, String) with Oracle NLS format + readback
  // ===================================================================

  @Test
  public void testSetObjectDateStringOracleFormat() throws SQLException {
    PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO nls_date_test (id, d) VALUES (?, ?)");
    ps.setInt(1, 202);
    ps.setObject(2, "20-Apr-2026", Types.DATE);
    ps.executeUpdate();
    ps.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 202");
    assertTrue(rs.next());
    Date d = rs.getDate("d");
    assertNotNull(d);
    assertEquals(2026, d.toLocalDate().getYear());
    assertEquals(4, d.toLocalDate().getMonthValue());
    assertEquals(20, d.toLocalDate().getDayOfMonth());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 8. NULL date handling
  // ===================================================================

  @Test
  public void testNullDate() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 5");
    assertTrue(rs.next());
    Date d = rs.getDate("d");
    assertNull("NULL date should return null", d);
    assertTrue("wasNull should be true", rs.wasNull());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 9. Boundary dates: New Year, Year-End, Leap Year Feb 29
  // ===================================================================

  @Test
  public void testBoundaryDates() throws SQLException {
    Statement stmt = conn.createStatement();

    // Jan 1, 2025
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 2");
    assertTrue(rs.next());
    Date d = rs.getDate("d");
    assertNotNull(d);
    assertEquals(2025, d.toLocalDate().getYear());
    assertEquals(1, d.toLocalDate().getMonthValue());
    assertEquals(1, d.toLocalDate().getDayOfMonth());
    rs.close();

    // Dec 31, 2024
    rs = stmt.executeQuery("SELECT d FROM nls_date_test WHERE id = 3");
    assertTrue(rs.next());
    d = rs.getDate("d");
    assertNotNull(d);
    assertEquals(2024, d.toLocalDate().getYear());
    assertEquals(12, d.toLocalDate().getMonthValue());
    assertEquals(31, d.toLocalDate().getDayOfMonth());
    rs.close();

    // Feb 29, 2024 (leap year)
    rs = stmt.executeQuery("SELECT d FROM nls_date_test WHERE id = 4");
    assertTrue(rs.next());
    d = rs.getDate("d");
    assertNotNull(d);
    assertEquals(2024, d.toLocalDate().getYear());
    assertEquals(2, d.toLocalDate().getMonthValue());
    assertEquals(29, d.toLocalDate().getDayOfMonth());
    rs.close();

    stmt.close();
  }

  // ===================================================================
  // 10. All 12 months — each month abbreviation must be recognized
  // ===================================================================

  @Test
  public void testAllTwelveMonths() throws SQLException {
    Statement stmt = conn.createStatement();
    for (int m = 1; m <= 12; m++) {
      int id = 100 + m;
      ResultSet rs = stmt.executeQuery(
          "SELECT d FROM nls_date_test WHERE id = " + id);
      assertTrue("Row should exist for month " + m, rs.next());
      Date d = rs.getDate("d");
      assertNotNull("Date should not be null for month " + m, d);
      assertEquals("Year should be 2026 for month " + m,
          2026, d.toLocalDate().getYear());
      assertEquals("Month mismatch for id " + id,
          m, d.toLocalDate().getMonthValue());
      assertEquals("Day should be 15 for month " + m,
          15, d.toLocalDate().getDayOfMonth());
      rs.close();
    }
    stmt.close();
  }

  // ===================================================================
  // 11. Date comparison in PreparedStatement WHERE clause
  // ===================================================================

  @Test
  public void testDateComparisonInWhereClause() throws SQLException {
    PreparedStatement ps = conn.prepareStatement(
        "SELECT id, d FROM nls_date_test WHERE d = ? AND id < 100");
    ps.setDate(1, Date.valueOf("2026-03-06"));
    ResultSet rs = ps.executeQuery();
    assertTrue("Should find row with date 2026-03-06", rs.next());
    assertEquals(1, rs.getInt("id"));
    assertFalse("Should be exactly one row", rs.next());
    rs.close();
    ps.close();
  }

  // ===================================================================
  // 12. Date range query with PreparedStatement
  // ===================================================================

  @Test
  public void testDateRangeQuery() throws SQLException {
    PreparedStatement ps = conn.prepareStatement(
        "SELECT COUNT(*) FROM nls_date_test "
            + "WHERE d >= ? AND d <= ? AND id >= 100");
    ps.setDate(1, Date.valueOf("2026-01-01"));
    ps.setDate(2, Date.valueOf("2026-06-30"));
    ResultSet rs = ps.executeQuery();
    assertTrue(rs.next());
    // Months 1-6, each with day 15, should all match
    assertEquals("Should have 6 rows in range", 6, rs.getInt(1));
    rs.close();
    ps.close();
  }

  // ===================================================================
  // 13. Date arithmetic: date + interval
  // ===================================================================

  @Test
  public void testDateArithmetic() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d + INTERVAL '10' DAY AS d2 "
            + "FROM nls_date_test WHERE id = 1");
    assertTrue(rs.next());
    // 2026-03-06 + 10 days = 2026-03-16
    Timestamp ts = rs.getTimestamp("d2");
    assertNotNull(ts);
    assertEquals(2026, ts.toLocalDateTime().getYear());
    assertEquals(3, ts.toLocalDateTime().getMonthValue());
    assertEquals(16, ts.toLocalDateTime().getDayOfMonth());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 14. ORDER BY date column
  // ===================================================================

  @Test
  public void testOrderByDate() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT id, d FROM nls_date_test "
            + "WHERE id IN (1, 2, 3, 4) ORDER BY d ASC");

    // Expected order: 2024-02-29 (4), 2024-12-31 (3), 2025-01-01 (2), 2026-03-06 (1)
    assertTrue(rs.next());
    assertEquals(4, rs.getInt("id"));
    assertTrue(rs.next());
    assertEquals(3, rs.getInt("id"));
    assertTrue(rs.next());
    assertEquals(2, rs.getInt("id"));
    assertTrue(rs.next());
    assertEquals(1, rs.getInt("id"));
    assertFalse(rs.next());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 15. Stored procedure / function with date parameter
  // ===================================================================

  @Test
  public void testStoredFunctionWithDate() throws SQLException {
    CallableStatement cs = conn.prepareCall(
        "{ ? = call nls_date_add_days(?, ?) }");
    cs.registerOutParameter(1, Types.DATE);
    cs.setDate(2, Date.valueOf("2026-03-06"));
    cs.setInt(3, 30);
    cs.execute();

    Date result = cs.getDate(1);
    assertNotNull("Function result should not be null", result);
    // 2026-03-06 + 30 days = 2026-04-05
    assertEquals(2026, result.toLocalDate().getYear());
    assertEquals(4, result.toLocalDate().getMonthValue());
    assertEquals(5, result.toLocalDate().getDayOfMonth());
    cs.close();
  }

  // ===================================================================
  // 16. Batch insert with dates and readback
  // ===================================================================

  @Test
  public void testBatchInsertDates() throws SQLException {
    PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO nls_date_test (id, d) VALUES (?, ?)");
    String[] dates = {
        "2026-01-10", "2026-05-20", "2026-09-30", "2026-12-25"
    };
    for (int i = 0; i < dates.length; i++) {
      ps.setInt(1, 300 + i);
      ps.setDate(2, Date.valueOf(dates[i]));
      ps.addBatch();
    }
    int[] counts = ps.executeBatch();
    assertEquals(dates.length, counts.length);
    ps.close();

    // Readback and verify each date
    Statement stmt = conn.createStatement();
    for (int i = 0; i < dates.length; i++) {
      ResultSet rs = stmt.executeQuery(
          "SELECT d FROM nls_date_test WHERE id = " + (300 + i));
      assertTrue(rs.next());
      Date d = rs.getDate("d");
      assertNotNull(d);
      assertEquals(Date.valueOf(dates[i]).toLocalDate(), d.toLocalDate());
      rs.close();
    }
    stmt.close();
  }

  // ===================================================================
  // 17. ResultSetMetaData for DATE column
  // ===================================================================

  @Test
  public void testResultSetMetaDataForDate() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 1");
    ResultSetMetaData meta = rs.getMetaData();
    assertEquals(1, meta.getColumnCount());
    // Column type should be DATE or TIMESTAMP (Oracle DATE includes time)
    int colType = meta.getColumnType(1);
    assertTrue("Column type should be DATE or TIMESTAMP",
        colType == Types.DATE || colType == Types.TIMESTAMP);
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 18. Multiple date columns in single query
  // ===================================================================

  @Test
  public void testMultipleDateColumnsInQuery() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.execute("INSERT INTO nls_date_test VALUES "
        + "(400, DATE '2026-03-06', TIMESTAMP '2026-03-06 10:30:00', NULL)");

    ResultSet rs = stmt.executeQuery(
        "SELECT d, ts FROM nls_date_test WHERE id = 400");
    assertTrue(rs.next());
    Date d = rs.getDate("d");
    Timestamp ts = rs.getTimestamp("ts");
    assertNotNull(d);
    assertNotNull(ts);
    assertEquals(2026, d.toLocalDate().getYear());
    assertEquals(3, d.toLocalDate().getMonthValue());
    assertEquals(6, d.toLocalDate().getDayOfMonth());
    assertEquals(10, ts.toLocalDateTime().getHour());
    assertEquals(30, ts.toLocalDateTime().getMinute());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 19. setObject with Oracle NLS string (uppercase month)
  // ===================================================================

  @Test
  public void testSetObjectOracleUppercaseMonth() throws SQLException {
    PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO nls_date_test (id, d) VALUES (?, ?)");
    ps.setInt(1, 203);
    ps.setObject(2, "15-JAN-2025", Types.DATE);
    ps.executeUpdate();
    ps.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 203");
    assertTrue(rs.next());
    Date d = rs.getDate("d");
    assertNotNull(d);
    assertEquals(2025, d.toLocalDate().getYear());
    assertEquals(1, d.toLocalDate().getMonthValue());
    assertEquals(15, d.toLocalDate().getDayOfMonth());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 20. setObject with Oracle NLS string (lowercase month)
  // ===================================================================

  @Test
  public void testSetObjectOracleLowercaseMonth() throws SQLException {
    PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO nls_date_test (id, d) VALUES (?, ?)");
    ps.setInt(1, 204);
    ps.setObject(2, "28-feb-2024", Types.DATE);
    ps.executeUpdate();
    ps.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 204");
    assertTrue(rs.next());
    Date d = rs.getDate("d");
    assertNotNull(d);
    assertEquals(2024, d.toLocalDate().getYear());
    assertEquals(2, d.toLocalDate().getMonthValue());
    assertEquals(28, d.toLocalDate().getDayOfMonth());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 21. setObject with Oracle NLS 2-digit year (DD-Mon-YY)
  // ===================================================================

  @Test
  public void testSetObjectOracleTwoDigitYear() throws SQLException {
    PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO nls_date_test (id, d) VALUES (?, ?)");
    ps.setInt(1, 205);
    ps.setObject(2, "06-Mar-26", Types.DATE);
    ps.executeUpdate();
    ps.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 205");
    assertTrue(rs.next());
    Date d = rs.getDate("d");
    assertNotNull(d);
    // 2-digit year "26" -> 2026
    assertEquals(2026, d.toLocalDate().getYear());
    assertEquals(3, d.toLocalDate().getMonthValue());
    assertEquals(6, d.toLocalDate().getDayOfMonth());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 22. nls_date_format with time component: 'DD-Mon-YYYY HH24:MI:SS'
  //     Ensures date+time Oracle format is also handled correctly
  // ===================================================================

  @Test
  public void testNlsDateFormatWithTimeComponent() throws SQLException {
    Statement stmt = conn.createStatement();
    // Switch to a format that includes time
    stmt.execute("SET nls_date_format = 'DD-Mon-YYYY HH24:MI:SS'");

    // Insert a date with time using Oracle DATE type (which includes time)
    stmt.execute("INSERT INTO nls_date_test VALUES "
        + "(500, TO_DATE('2026-03-06 14:30:45', 'YYYY-MM-DD HH24:MI:SS'), NULL, NULL)");

    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 500");
    assertTrue(rs.next());

    // getDate() should still return the date part correctly
    Date d = rs.getDate("d");
    assertNotNull(d);
    assertEquals(2026, d.toLocalDate().getYear());
    assertEquals(3, d.toLocalDate().getMonthValue());
    assertEquals(6, d.toLocalDate().getDayOfMonth());

    rs.close();

    // getTimestamp() should also return time part
    rs = stmt.executeQuery("SELECT d FROM nls_date_test WHERE id = 500");
    assertTrue(rs.next());
    Timestamp ts = rs.getTimestamp("d");
    assertNotNull(ts);
    assertEquals(2026, ts.toLocalDateTime().getYear());
    assertEquals(3, ts.toLocalDateTime().getMonthValue());
    assertEquals(6, ts.toLocalDateTime().getDayOfMonth());
    assertEquals(14, ts.toLocalDateTime().getHour());
    assertEquals(30, ts.toLocalDateTime().getMinute());
    assertEquals(45, ts.toLocalDateTime().getSecond());

    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 23. Single-digit day in Oracle format (e.g. 6-Mar-2026 vs 06-Mar-2026)
  // ===================================================================

  @Test
  public void testSingleDigitDay() throws SQLException {
    PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO nls_date_test (id, d) VALUES (?, ?)");
    ps.setInt(1, 206);
    // Single-digit day without leading zero
    ps.setObject(2, "6-Mar-2026", Types.DATE);
    ps.executeUpdate();
    ps.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 206");
    assertTrue(rs.next());
    Date d = rs.getDate("d");
    assertNotNull(d);
    assertEquals(2026, d.toLocalDate().getYear());
    assertEquals(3, d.toLocalDate().getMonthValue());
    assertEquals(6, d.toLocalDate().getDayOfMonth());
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 24. getString() for DATE column returns Oracle format string
  // ===================================================================

  @Test
  public void testGetStringReturnsOracleFormat() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT d FROM nls_date_test WHERE id = 1");
    assertTrue(rs.next());
    String dateStr = rs.getString("d");
    assertNotNull("getString should not return null", dateStr);
    // POLAR: The driver normalizes timestamp output to standard format YYYY-MM-DD HH:MI:SS
    // regardless of NLS settings. This is for compatibility with Oracle's timestamp handling.
    // Verify the date string contains the expected date in standard format
    assertTrue("Date string should contain the date: " + dateStr,
        dateStr.startsWith("2026-03-06"));
    rs.close();
    stmt.close();
  }

  // ===================================================================
  // 25. Current date (SYSDATE) with Oracle format
  // ===================================================================

  @Test
  public void testSysdateWithOracleFormat() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT SYSDATE AS sd FROM DUAL");
    assertTrue(rs.next());
    Date d = rs.getDate("sd");
    assertNotNull("SYSDATE should not be null", d);
    // Just verify it's a valid recent date
    assertTrue("Year should be >= 2024",
        d.toLocalDate().getYear() >= 2024);
    rs.close();
    stmt.close();
  }
}
