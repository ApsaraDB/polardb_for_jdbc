/*
 * Test: executeUpdate() on Oracle-style anonymous PL/SQL blocks must return 1,
 * matching Oracle ojdbc behaviour.
 *
 * Issue: customer frameworks gate on "int success = executeUpdate(); if (success == 1)".
 * Oracle returns 1 for anonymous blocks; the server reports 0 for DO blocks, so the
 * PolarDB driver used to return 0 and such frameworks threw "cannot get sequence"-style
 * errors after migration.
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;

public class DoBlockExecuteUpdateTest {

  private final String suffix = Long.toHexString(System.nanoTime());
  private final String funcName = "dob_upd_fn_" + suffix;

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    conn = TestUtil.openDB(props);
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("drop function if exists " + funcName);
      stmt.execute("create or replace function " + funcName + " return number is "
          + "begin return 42; end;");
    }
  }

  @After
  public void tearDown() throws Exception {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("drop function if exists " + funcName);
    } finally {
      TestUtil.closeDB(conn);
    }
  }

  /**
   * Customer scenario: "begin ? := pkg.fn(); end;" with a registered OUT parameter;
   * executeUpdate() must return 1 (as with Oracle) and the OUT value must be readable.
   */
  @Test
  public void testExecuteUpdateReturnsOneWithOutParam() throws SQLException {
    try (CallableStatement cs =
        conn.prepareCall("begin ? := " + funcName + "(); end;")) {
      cs.registerOutParameter(1, Types.VARCHAR);
      int success = cs.executeUpdate();
      assertEquals("executeUpdate on anonymous block must return 1 (Oracle parity)", 1, success);
      assertEquals("42", cs.getString(1));
    }
  }

  /**
   * Anonymous block without parameters: executeUpdate() must also return 1.
   */
  @Test
  public void testExecuteUpdateReturnsOneWithoutParams() throws SQLException {
    try (CallableStatement cs = conn.prepareCall("begin null; end;")) {
      assertEquals(1, cs.executeUpdate());
    }
  }

  /**
   * executeLargeUpdate() gets the same Oracle-parity adjustment.
   */
  @Test
  public void testExecuteLargeUpdateReturnsOne() throws SQLException {
    try (CallableStatement cs =
        conn.prepareCall("begin ? := " + funcName + "(); end;")) {
      cs.registerOutParameter(1, Types.VARCHAR);
      assertEquals(1L, cs.executeLargeUpdate());
    }
  }

  /**
   * Regression: regular DML update counts are untouched.
   */
  @Test
  public void testDmlUpdateCountUnchanged() throws SQLException {
    String table = "dob_upd_tbl_" + suffix;
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("create table " + table + " (x int)");
      assertEquals(1, stmt.executeUpdate("insert into " + table + " values (1)"));
      assertEquals(1, stmt.executeUpdate("insert into " + table + " values (2)"));
      assertEquals(2, stmt.executeUpdate("update " + table + " set x = x + 1"));
      stmt.execute("drop table " + table);
    }
  }
}
