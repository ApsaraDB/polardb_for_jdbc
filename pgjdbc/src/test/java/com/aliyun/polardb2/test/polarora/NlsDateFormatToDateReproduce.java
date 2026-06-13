/*
 * Portions Copyright (c) 2026, Alibaba Group Holding Limited
 *
 * Reproduces: setObject with a date/timestamp value into to_date(:1,'DD-MON-YY')
 * when nls_date_format='DD-MON-RR'.
 */

package com.aliyun.polardb2.test.polarora;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Properties;

/**
 * Reproduces the issue where setObject passes a Date/Timestamp to
 * {@code to_date(:1, 'DD-MON-YY')} under {@code nls_date_format='DD-MON-RR'}.
 *
 * <p>The suspected problem: the driver formats the date as ISO (e.g. '2025-06-05')
 * which does not match the 'DD-MON-YY' format mask, causing a server parse error.
 */
public class NlsDateFormatToDateReproduce {

  private Connection conn;
  private Connection connNoMap;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    // Do not override the server's NLS format
    props.setProperty("resetNlsFormat", "false");
    conn = TestUtil.openDB(props);

    // Second connection: explicitly turn off the POLAR DIFF that maps Date->Timestamp.
    Properties propsNoMap = new Properties();
    propsNoMap.setProperty("resetNlsFormat", "false");
    propsNoMap.setProperty("mapDateToTimestamp", "false");
    connNoMap = TestUtil.openDB(propsNoMap);

    for (Connection c : new Connection[] {conn, connNoMap}) {
      Statement stmt = c.createStatement();
      // Simulate production: nls_date_format = 'DD-MON-RR'
      stmt.execute("SET nls_date_format = 'DD-MON-RR'");

      // Create a minimal reproduction of tinvestment_days
      stmt.execute("DROP TABLE IF EXISTS tinvestment_days");
      stmt.execute("CREATE TABLE tinvestment_days ("
          + "  run_dt     DATE,"
          + "  buy_in_dt  DATE"
          + ")");

      // Insert some test data
      stmt.execute("INSERT INTO tinvestment_days VALUES "
          + "(DATE '2025-06-05', DATE '2025-06-10')");
      stmt.execute("INSERT INTO tinvestment_days VALUES "
          + "(DATE '2024-01-15', DATE '2024-01-20')");
      stmt.close();
    }
  }

  @After
  public void tearDown() throws Exception {
    for (Connection c : new Connection[] {conn, connNoMap}) {
      if (c != null) {
        try (Statement stmt = c.createStatement()) {
          stmt.execute("DROP TABLE IF EXISTS tinvestment_days");
        } catch (SQLException ignore) {
          // ignore
        }
        c.close();
      }
    }
  }

  /**
   * Case 1: setObject with java.sql.Date into to_date(:1, 'DD-MON-YY').
   */
  @Test
  public void testSetObjectSqlDate() throws SQLException {
    String sql = "SELECT buy_in_dt FROM tinvestment_days "
        + "WHERE run_dt = to_date(?, 'DD-MON-YY')";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      // Pass a java.sql.Date representing 2025-06-05
      ps.setObject(1, Date.valueOf("2025-06-05"));
      try (ResultSet rs = ps.executeQuery()) {
        System.out.println("[testSetObjectSqlDate] executed OK");
        while (rs.next()) {
          System.out.println("  buy_in_dt = " + rs.getDate(1));
        }
      }
    }
  }

  /**
   * Case 2: setObject with java.sql.Timestamp into to_date(:1, 'DD-MON-YY').
   */
  @Test
  public void testSetObjectTimestamp() throws SQLException {
    String sql = "SELECT buy_in_dt FROM tinvestment_days "
        + "WHERE run_dt = to_date(?, 'DD-MON-YY')";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setObject(1, Timestamp.valueOf("2025-06-05 00:00:00"));
      try (ResultSet rs = ps.executeQuery()) {
        System.out.println("[testSetObjectTimestamp] executed OK");
        while (rs.next()) {
          System.out.println("  buy_in_dt = " + rs.getDate(1));
        }
      }
    }
  }

  /**
   * Case 3: setObject with String (manual format) into to_date(:1, 'DD-MON-YY').
   * This is typically how the application would work correctly.
   */
  @Test
  public void testSetObjectString() throws SQLException {
    String sql = "SELECT buy_in_dt FROM tinvestment_days "
        + "WHERE run_dt = to_date(?, 'DD-MON-YY')";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      // Pass a pre-formatted string matching the format mask
      ps.setObject(1, "05-JUN-25");
      try (ResultSet rs = ps.executeQuery()) {
        System.out.println("[testSetObjectString] executed OK");
        while (rs.next()) {
          System.out.println("  buy_in_dt = " + rs.getDate(1));
        }
      }
    }
  }

  /**
   * Case 5: setObject with LocalDate (Java 8 time API).
   */
  @Test
  public void testSetObjectLocalDate() throws SQLException {
    String sql = "SELECT buy_in_dt FROM tinvestment_days "
        + "WHERE run_dt = to_date(?, 'DD-MON-YY')";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setObject(1, LocalDate.of(2025, 6, 5));
      try (ResultSet rs = ps.executeQuery()) {
        System.out.println("[testSetObjectLocalDate] executed OK");
        while (rs.next()) {
          System.out.println("  buy_in_dt = " + rs.getDate(1));
        }
      }
    }
  }

  /**
   * Case 6: setObject with LocalDateTime (Java 8 time API).
   */
  @Test
  public void testSetObjectLocalDateTime() throws SQLException {
    String sql = "SELECT buy_in_dt FROM tinvestment_days "
        + "WHERE run_dt = to_date(?, 'DD-MON-YY')";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setObject(1, LocalDateTime.of(2025, 6, 5, 0, 0, 0));
      try (ResultSet rs = ps.executeQuery()) {
        System.out.println("[testSetObjectLocalDateTime] executed OK");
        while (rs.next()) {
          System.out.println("  buy_in_dt = " + rs.getDate(1));
        }
      }
    }
  }

  // ----------------------------------------------------------------------
  // Control group: mapDateToTimestamp=false. Probe whether the failure is
  // caused solely by the POLAR DIFF that silently rewrites Date -> Timestamp.
  // ----------------------------------------------------------------------

  /** Case 5 with mapDateToTimestamp=false. */
  @Test
  public void testSetObjectLocalDate_NoMap() throws SQLException {
    String sql = "SELECT buy_in_dt FROM tinvestment_days "
        + "WHERE run_dt = to_date(?, 'DD-MON-YY')";
    try (PreparedStatement ps = connNoMap.prepareStatement(sql)) {
      ps.setObject(1, LocalDate.of(2025, 6, 5));
      try (ResultSet rs = ps.executeQuery()) {
        System.out.println("[testSetObjectLocalDate_NoMap] executed OK");
        while (rs.next()) {
          System.out.println("  buy_in_dt = " + rs.getDate(1));
        }
      }
    }
  }

  /** Case 2 (Timestamp) with mapDateToTimestamp=false. Type itself is timestamp,
   * so this is expected to keep failing if the kernel's timestamp::text path is the issue. */
  @Test
  public void testSetObjectTimestamp_NoMap() throws SQLException {
    String sql = "SELECT buy_in_dt FROM tinvestment_days "
        + "WHERE run_dt = to_date(?, 'DD-MON-YY')";
    try (PreparedStatement ps = connNoMap.prepareStatement(sql)) {
      ps.setObject(1, Timestamp.valueOf("2025-06-05 00:00:00"));
      try (ResultSet rs = ps.executeQuery()) {
        System.out.println("[testSetObjectTimestamp_NoMap] executed OK");
        while (rs.next()) {
          System.out.println("  buy_in_dt = " + rs.getDate(1));
        }
      }
    }
  }
}
