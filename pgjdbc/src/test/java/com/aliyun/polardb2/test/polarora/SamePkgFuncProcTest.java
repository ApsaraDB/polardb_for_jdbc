/*
 * Portions Copyright (c) 2026, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeNoException;

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

/**
 * Verifies the design.md JDBC<->kernel verb contract for same-name
 * function/procedure pairs in an Oracle compatible package:
 * <ul>
 *   <li>{@code { call pkg.foo(?) }}      &rarr; driver dispatches {@code CALL pkg.foo(?)},
 *       kernel rewrites to {@code _foo} and hits the PROCEDURE.</li>
 *   <li>{@code { ? = call pkg.foo(?) }}  &rarr; driver dispatches {@code EXEC pkg.foo(?)},
 *       kernel keeps the bare name and hits the FUNCTION.</li>
 * </ul>
 *
 * <p>Depends on PolarDB GUCs:
 * <ul>
 *   <li>{@code polar_enable_same_pkg_fp_name} - allows same-name function and procedure</li>
 *   <li>{@code polar_enable_call_function_syntax} - lets CALL/EXEC resolve to a function</li>
 * </ul>
 *
 * <p>If either GUC is missing on the target server, the test is skipped via
 * {@link org.junit.Assume}.
 */
public class SamePkgFuncProcTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    try (Statement stmt = conn.createStatement()) {
      // Required GUCs: skip the test gracefully on servers that do not expose them.
      try {
        stmt.execute("SET polar_enable_same_pkg_fp_name = on");
        stmt.execute("SET polar_enable_call_function_syntax = on");
      } catch (SQLException e) {
        assumeNoException(
            "PolarDB GUC for same-name function/procedure not available", e);
      }

      try {
        stmt.execute("DROP PACKAGE IF EXISTS psf_pkg");
      } catch (SQLException ignore) {
        // first-time setup
      }

      // The procedure mutates an INOUT param so we can observe which overload was hit;
      // design.md uses IN-only signatures plus dbms_output, but INOUT lets JUnit assert.
      stmt.execute(
          "CREATE OR REPLACE PACKAGE psf_pkg AS\n"
              + "  PROCEDURE main_proc(a IN OUT int);\n"
              + "  FUNCTION  main_proc(a int) RETURN int;\n"
              + "END psf_pkg;");

      stmt.execute(
          "CREATE OR REPLACE PACKAGE BODY psf_pkg AS\n"
              + "  PROCEDURE main_proc(a IN OUT int) IS\n"
              + "  BEGIN\n"
              + "    a := a + 1;\n"
              + "  END;\n"
              + "  FUNCTION main_proc(a int) RETURN int IS\n"
              + "  BEGIN\n"
              + "    RETURN a + 100;\n"
              + "  END;\n"
              + "END psf_pkg;");
    }
  }

  @After
  public void tearDown() throws Exception {
    if (conn != null) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP PACKAGE IF EXISTS psf_pkg");
      } catch (SQLException ignore) {
        // tear-down failures are not interesting
      }
      conn.close();
    }
  }

  /**
   * {@code { call ... }} carries no return placeholder, the driver sends
   * {@code CALL psf_pkg.main_proc(?)} and the kernel rewrites it to
   * {@code _main_proc}, hitting the procedure overload (a := a + 1).
   */
  @Test
  public void testCallEscapeRoutesToProcedure() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ call psf_pkg.main_proc(?) }")) {
      cs.setInt(1, 5);
      cs.registerOutParameter(1, Types.INTEGER);
      cs.execute();
      assertEquals("CALL escape must hit the PROCEDURE (a + 1)",
          6, cs.getInt(1));
    }
  }

  /**
   * {@code { ? = call ... }} carries the return placeholder, the driver sends
   * {@code EXEC psf_pkg.main_proc(?)} and the kernel hits the bare-name
   * FUNCTION overload (return a + 100).
   */
  @Test
  public void testExecEscapeRoutesToFunction() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ ? = call psf_pkg.main_proc(?) }")) {
      cs.registerOutParameter(1, Types.INTEGER);
      cs.setInt(2, 5);
      cs.execute();
      assertEquals("EXEC escape must hit the FUNCTION (a + 100)",
          105, cs.getInt(1));
    }
  }
}
