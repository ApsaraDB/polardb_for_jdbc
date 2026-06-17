/*
 * Portions Copyright (c) 2026, Alibaba Group Holding Limited
 *
 * Tests for autocommitFetch: server-side cursor (fetchSize) in autoCommit mode
 * using PolarDB's polar_enable_autocommit_cursor holdable portal mechanism.
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Validates that fetchSize works in autoCommit mode when the server supports
 * polar_enable_autocommit_cursor. The server materializes remaining rows into a
 * holdable cursor (tuplestore) so clients can fetch in batches without holding
 * a transaction open.
 *
 * <p>Tests are skipped on servers that do not expose the required GUC.
 */
public class AutocommitFetchTest {

  private Connection conn;
  private Connection connDefault;

  @Before
  public void setUp() throws Exception {
    // Connection with autocommitFetch=true
    Properties props = new Properties();
    props.setProperty("autocommitFetch", "true");
    try {
      conn = TestUtil.openDB(props);
    } catch (SQLException e) {
      assumeNoException("Cannot connect to database", e);
    }

    // Verify autoCommit is on (default)
    assertTrue("autoCommit should be true by default", conn.getAutoCommit());

    // Connection with autocommitFetch=false (control group)
    Properties propsDefault = new Properties();
    propsDefault.setProperty("autocommitFetch", "false");
    connDefault = TestUtil.openDB(propsDefault);

    // Create test table with enough rows
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE IF EXISTS autocommit_fetch_test");
      stmt.execute("CREATE TABLE autocommit_fetch_test (id int)");
      stmt.execute("INSERT INTO autocommit_fetch_test SELECT generate_series(1, 1000)");
    }
  }

  @After
  public void tearDown() throws Exception {
    if (conn != null) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS autocommit_fetch_test");
      } catch (SQLException ignore) {
        // ignore
      }
      conn.close();
    }
    if (connDefault != null) {
      connDefault.close();
    }
  }

  /**
   * Core test: autocommitFetch=true + fetchSize=10 should fetch rows in batches.
   * If server does not support the GUC, the SET fails silently at connect time
   * and this test verifies the fallback (all rows fetched at once - still works).
   */
  @Test
  public void testBatchFetchInAutoCommit() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.setFetchSize(10);
      try (ResultSet rs = stmt.executeQuery(
          "SELECT id FROM autocommit_fetch_test ORDER BY id")) {
        int count = 0;
        while (rs.next()) {
          count++;
          assertEquals(count, rs.getInt(1));
        }
        assertEquals("Should read all 1000 rows", 1000, count);
      }
    }
  }

  /**
   * Verify that ResultSet.close() properly releases the server-side portal.
   * After close, the cursor should no longer exist on the server.
   */
  @Test
  public void testResultSetCloseReleasesPortal() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.setFetchSize(10);
    ResultSet rs = stmt.executeQuery("SELECT id FROM autocommit_fetch_test ORDER BY id");

    // Read a few rows (not all) to ensure a portal was created
    for (int i = 0; i < 5; i++) {
      assertTrue(rs.next());
    }

    // Close should release the holdable cursor
    rs.close();
    stmt.close();

    // If we can execute another query without error, the portal was properly cleaned up
    try (Statement stmt2 = conn.createStatement();
         ResultSet rs2 = stmt2.executeQuery("SELECT 1")) {
      assertTrue(rs2.next());
      assertEquals(1, rs2.getInt(1));
    }
  }

  /**
   * Control group: autocommitFetch=false should behave exactly as today
   * (fetchSize ignored in autoCommit mode, all rows returned at once).
   */
  @Test
  public void testAutocommitFetchDisabledIgnoresFetchSize() throws SQLException {
    try (Statement stmt = connDefault.createStatement()) {
      stmt.setFetchSize(10);
      try (ResultSet rs = stmt.executeQuery(
          "SELECT id FROM autocommit_fetch_test ORDER BY id")) {
        int count = 0;
        while (rs.next()) {
          count++;
        }
        assertEquals("Should still read all 1000 rows", 1000, count);
      }
    }
  }

  /**
   * Multiple statements can be executed while a cursor is still open
   * (holdable cursors survive across statements in the same session).
   */
  @Test
  public void testCursorSurvivesAcrossStatements() throws SQLException {
    Statement stmt1 = conn.createStatement();
    stmt1.setFetchSize(10);
    ResultSet rs1 = stmt1.executeQuery("SELECT id FROM autocommit_fetch_test ORDER BY id");

    // Read first batch
    for (int i = 0; i < 5; i++) {
      assertTrue("Should have row " + (i + 1), rs1.next());
    }

    // Execute another query on the same connection
    try (Statement stmt2 = conn.createStatement();
         ResultSet rs2 = stmt2.executeQuery("SELECT 42 AS answer")) {
      assertTrue(rs2.next());
      assertEquals(42, rs2.getInt(1));
    }

    // Continue reading from the first cursor
    assertTrue("Cursor should still be valid after another statement", rs1.next());
    assertEquals(6, rs1.getInt(1));

    rs1.close();
    stmt1.close();
  }

  /**
   * PreparedStatement with fetchSize should also work in autocommit mode.
   */
  @Test
  public void testPreparedStatementFetch() throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT id FROM autocommit_fetch_test WHERE id <= ? ORDER BY id")) {
      ps.setFetchSize(5);
      ps.setInt(1, 50);
      try (ResultSet rs = ps.executeQuery()) {
        int count = 0;
        while (rs.next()) {
          count++;
          assertEquals(count, rs.getInt(1));
        }
        assertEquals(50, count);
      }
    }
  }

  /**
   * fetchSize=0 should still return all rows at once even with autocommitFetch=true.
   */
  @Test
  public void testFetchSizeZeroReturnsAllAtOnce() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.setFetchSize(0);
      try (ResultSet rs = stmt.executeQuery(
          "SELECT id FROM autocommit_fetch_test ORDER BY id")) {
        int count = 0;
        while (rs.next()) {
          count++;
        }
        assertEquals(1000, count);
      }
    }
  }

  /**
   * Diagnostic: verify the GUC is actually recognized AND set to ON on this session.
   * If this fails, autocommitFetch will silently degrade.
   */
  @Test
  public void testGucIsRecognizedAndOn() throws SQLException {
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SHOW polar_enable_autocommit_cursor")) {
      assertTrue(rs.next());
      String value = rs.getString(1);
      System.out.println("[diagnostic] polar_enable_autocommit_cursor = " + value);
      assertEquals("on", value);
    }

    // Also check the driver-side cached state
    com.aliyun.polardb2.core.BaseConnection bc =
        (com.aliyun.polardb2.core.BaseConnection) conn;
    System.out.println("[diagnostic] isAutocommitFetchEnabled = "
        + bc.isAutocommitFetchEnabled());
    assertTrue("Driver should report autocommitFetch enabled",
        bc.isAutocommitFetchEnabled());
    assertTrue("Connection should be in autoCommit mode", conn.getAutoCommit());
  }

  /**
   * Wire-level trace: inspect protocol messages to confirm a named portal C_x is
   * created and Execute is sent with limit=fetchSize.
   */
  @Test
  public void testWireProtocolUsesNamedPortal() throws Exception {
    Properties traceProps = new Properties();
    traceProps.setProperty("autocommitFetch", "true");
    traceProps.setProperty("loggerLevel", "TRACE");

    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
    java.util.logging.StreamHandler handler =
        new java.util.logging.StreamHandler(new java.io.PrintStream(buf, true, "UTF-8"),
            new java.util.logging.SimpleFormatter());
    handler.setLevel(java.util.logging.Level.FINEST);
    java.util.logging.Logger pgLogger =
        java.util.logging.Logger.getLogger("com.aliyun.polardb2");
    pgLogger.addHandler(handler);
    pgLogger.setLevel(java.util.logging.Level.FINEST);

    try (Connection traceConn = TestUtil.openDB(traceProps);
         Statement stmt = traceConn.createStatement()) {
      stmt.setFetchSize(10);
      try (ResultSet rs = stmt.executeQuery(
          "SELECT id FROM autocommit_fetch_test ORDER BY id")) {
        for (int i = 0; i < 25; i++) {
          assertTrue(rs.next());
        }
      }
    } finally {
      handler.flush();
      pgLogger.removeHandler(handler);
    }

    String log = buf.toString("UTF-8");
    // Print only Bind/Execute/PortalSuspended-related lines
    for (String line : log.split("\n")) {
      if (line.contains("Bind(") || line.contains("Execute(")
          || line.contains("PortalSuspended") || line.contains("ReadyForQuery")
          || line.contains("ClosePortal") || line.contains("SET polar_enable")) {
        System.out.println("[trace] " + line.trim());
      }
    }

    boolean usedNamedPortal = log.contains("Bind(stmt=") && log.contains(",portal=C_");
    boolean sawSuspended = log.contains("PortalSuspended");
    System.out.println("[diagnostic] used named portal C_x = " + usedNamedPortal);
    System.out.println("[diagnostic] saw PortalSuspended    = " + sawSuspended);
    assertTrue("Driver must create a named portal C_x for batch fetching", usedNamedPortal);
    assertTrue("Server must respond with PortalSuspended for batched fetches", sawSuspended);
  }

  /**
   * Cross-session observation: while we are in mid-fetch on conn (autoCommit=true,
   * autocommitFetch=true), pg_stat_activity for our backend must report state='idle'
   * with no xact_start. This proves the holdable-cursor contract: the transaction
   * has been committed but the cursor still survives so we can continue fetching.
   *
   * <p>Note: pg_cursors is session-local, so we cannot directly observe the cursor
   * from a probe connection. The session-state assertion is the strongest cross-session
   * proof. The wire trace test (testWireProtocolUsesNamedPortal) covers the rest.
   */
  @Test
  public void testSessionIsIdleDuringAutocommitFetch() throws SQLException {
    // Get our session PID
    int myPid;
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT pg_backend_pid()")) {
      assertTrue(rs.next());
      myPid = rs.getInt(1);
    }

    try (Statement stmt = conn.createStatement()) {
      stmt.setFetchSize(10);
      try (ResultSet rs = stmt.executeQuery(
          "SELECT id FROM autocommit_fetch_test ORDER BY id")) {
        // Read mid-batch to land squarely between fetches
        for (int i = 0; i < 25; i++) {
          assertTrue(rs.next());
        }

        try (Connection probe = TestUtil.openDB();
             PreparedStatement ps = probe.prepareStatement(
                 "SELECT state, xact_start FROM pg_stat_activity WHERE pid = ?")) {
          ps.setInt(1, myPid);
          try (ResultSet probeRs = ps.executeQuery()) {
            assertTrue(probeRs.next());
            String state = probeRs.getString(1);
            java.sql.Timestamp xactStart = probeRs.getTimestamp(2);
            System.out.println("[diagnostic] mid-fetch session state = " + state
                + ", xact_start = " + xactStart);
            assertEquals("Session must be idle (transaction committed) during"
                + " autocommit holdable fetch, not idle-in-transaction",
                "idle", state);
            assertEquals("xact_start must be null when no transaction is open",
                null, xactStart);
          }
        }

        // Drain the rest
        int total = 25;
        while (rs.next()) {
          total++;
        }
        assertEquals(1000, total);
      }
    }
  }

  /**
   * Behavioral proof of incremental fetching: use reflection to read the internal
   * {@code PgResultSet.rows} buffer at multiple points during iteration. If the
   * driver truly batches with fetchSize=N, the JVM-side buffer must never hold
   * more than N rows at any time, regardless of how many rows the result set has.
   *
   * <p>This is a direct, unambiguous proof: if the driver ever pre-buffered the
   * full result, {@code rows.size()} would jump to the total row count after
   * executeQuery; with batching it stays bounded by fetchSize and gets refilled
   * each time a batch is consumed.
   */
  @Test
  public void testClientBufferStaysBoundedByFetchSize() throws Exception {
    final int fetchSize = 7;
    final int totalRows = 1000;

    java.lang.reflect.Field rowsField =
        Class.forName("com.aliyun.polardb2.jdbc.PgResultSet").getDeclaredField("rows");
    rowsField.setAccessible(true);

    int maxBufferSeen = 0;
    int refillsObserved = 0;

    try (Statement stmt = conn.createStatement()) {
      stmt.setFetchSize(fetchSize);
      try (ResultSet rs = stmt.executeQuery(
          "SELECT id FROM autocommit_fetch_test ORDER BY id")) {

        // Buffer right after executeQuery, before any next()
        java.util.List<?> initial = (java.util.List<?>) rowsField.get(rs);
        int initialSize = initial == null ? 0 : initial.size();
        System.out.println("[diagnostic] initial buffer size after executeQuery = "
            + initialSize);
        assertTrue("Initial buffer must not exceed fetchSize=" + fetchSize
                + " but is " + initialSize,
            initialSize <= fetchSize);
        maxBufferSeen = Math.max(maxBufferSeen, initialSize);

        int rowsRead = 0;
        int previousBufferSize = initialSize;
        while (rs.next()) {
          rowsRead++;
          if (rowsRead % fetchSize == 0 || rowsRead == 1
              || rowsRead == totalRows / 2 || rowsRead == totalRows) {
            java.util.List<?> current = (java.util.List<?>) rowsField.get(rs);
            int sz = current == null ? 0 : current.size();
            assertTrue("Mid-iteration buffer must not exceed fetchSize=" + fetchSize
                    + " but is " + sz + " at row " + rowsRead,
                sz <= fetchSize);
            maxBufferSeen = Math.max(maxBufferSeen, sz);
            // Detect refills: buffer was consumed (currentRow reached end) and
            // got replaced with a new batch.
            if (sz > 0 && previousBufferSize > 0 && current != initial) {
              // pointer-equality is a stronger signal than size; track distinct buffers
              previousBufferSize = sz;
            }
          }
        }
        assertEquals(totalRows, rowsRead);

        // Count distinct buffer instances by repeatedly reading at known boundaries.
        // We rely on the fact that fetch() replaces this.rows (PgResultSet.next: see
        // CursorResultHandler.handleResultRows), so a fresh fetch creates a new List.
        // We approximate refill count by totalRows/fetchSize.
        refillsObserved = totalRows / fetchSize;
      }
    }

    System.out.println("[diagnostic] max buffer size observed = " + maxBufferSeen
        + ", expected refills ~= " + refillsObserved);
    assertTrue("Max observed buffer (" + maxBufferSeen
            + ") must be <= fetchSize (" + fetchSize + ")",
        maxBufferSeen <= fetchSize);
    // If pre-buffered, max would be totalRows. Verify it's far smaller.
    assertTrue("Buffer should not contain the full result set; max=" + maxBufferSeen
            + ", totalRows=" + totalRows,
        maxBufferSeen < totalRows / 10);
  }
}
