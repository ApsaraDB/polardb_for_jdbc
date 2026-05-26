/*
 * Portions Copyright (c) 2023, Alibaba Group Holding Limited
 *
 * Reproduces the customer issue where CallableStatement.executeQuery()
 * returns null for a stored procedure with OUT parameters.
 *
 * When HikariCP wraps the null ResultSet in a HikariProxyResultSet,
 * calling close() on the proxy throws NullPointerException:
 *   "Cannot invoke java.sql.ResultSet.close() because this.delegate is null"
 *
 * Root cause: PgCallableStatement.executeQuery() returns null when isFunction=true.
 * This violates the JDBC spec (Statement.executeQuery should never return null).
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;

/**
 * Reproduces NullPointerException on ResultSet.close() when
 * CallableStatement.executeQuery() is used on a stored procedure
 * with only OUT parameters (no REF CURSOR).
 *
 * <p>Customer scenario:
 * <pre>
 *   CallableStatement cs = conn.prepareCall(
 *       "call SOA_CUST_ACCESS_MANAGEMENT.RETRIEVE_PARTY_ACCESS_INFO(?, ?, ?, ...)");
 *   cs.setString(1, "PIN");
 *   cs.setString(2, "500090915");
 *   cs.registerOutParameter(3, Types.VARCHAR);
 *   ...
 *   cs.registerOutParameter(13, Types.NUMERIC);
 *   ResultSet rs = cs.executeQuery();   // returns null!
 *   ...
 *   rs.close();  // NPE: delegate is null (HikariProxyResultSet wrapping null)
 * </pre>
 */
public class ExecuteQueryNullResultSetTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();

    // Cleanup
    try {
      stmt.execute("DROP PACKAGE IF EXISTS SOA_CUST_ACCESS_PKG");
    } catch (Exception ignored) {
    }

    // Create a simple package with a procedure that has IN + OUT parameters
    // Simulates SOA_CUST_ACCESS_MANAGEMENT.RETRIEVE_PARTY_ACCESS_INFO
    stmt.execute(
        "CREATE OR REPLACE PACKAGE SOA_CUST_ACCESS_PKG AS\n"
            + "  PROCEDURE RETRIEVE_PARTY_ACCESS_INFO(\n"
            + "    PI_ID_TYPE     IN  VARCHAR2,\n"
            + "    PI_ID_VALUE    IN  VARCHAR2,\n"
            + "    PO_ACCESS_TYPE OUT VARCHAR2,\n"
            + "    PO_STATUS      OUT INTEGER,\n"
            + "    PO_RETURN_CODE OUT INTEGER\n"
            + "  );\n"
            + "END SOA_CUST_ACCESS_PKG;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY SOA_CUST_ACCESS_PKG AS\n"
            + "  PROCEDURE RETRIEVE_PARTY_ACCESS_INFO(\n"
            + "    PI_ID_TYPE     IN  VARCHAR2,\n"
            + "    PI_ID_VALUE    IN  VARCHAR2,\n"
            + "    PO_ACCESS_TYPE OUT VARCHAR2,\n"
            + "    PO_STATUS      OUT INTEGER,\n"
            + "    PO_RETURN_CODE OUT INTEGER\n"
            + "  ) IS\n"
            + "  BEGIN\n"
            + "    PO_ACCESS_TYPE := 'FULL';\n"
            + "    PO_STATUS      := 1;\n"
            + "    PO_RETURN_CODE := 0;\n"
            + "  END;\n"
            + "END SOA_CUST_ACCESS_PKG;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    if (conn != null) {
      Statement stmt = conn.createStatement();
      try {
        stmt.execute("DROP PACKAGE IF EXISTS SOA_CUST_ACCESS_PKG");
      } catch (Exception ignored) {
      }
      stmt.close();
      conn.close();
    }
  }

  /**
   * Verifies the fix: CallableStatement.executeQuery() on a stored procedure
   * with OUT params should return a non-null (empty) ResultSet.
   *
   * <p>Before the fix, this returned null, causing NPE in HikariCP:
   * "Cannot invoke java.sql.ResultSet.close() because this.delegate is null"
   *
   * <p>After the fix, an empty ResultSet is returned that can be safely closed.
   */
  @Test
  public void testExecuteQueryOnProcedureReturnsNonNull() throws Exception {
    String sql = "call SOA_CUST_ACCESS_PKG.RETRIEVE_PARTY_ACCESS_INFO(?, ?, ?, ?, ?)";
    CallableStatement cs = conn.prepareCall(sql);

    // IN parameters
    cs.setString(1, "PIN");
    cs.setString(2, "500090915");

    // OUT parameters
    cs.registerOutParameter(3, Types.VARCHAR);  // PO_ACCESS_TYPE
    cs.registerOutParameter(4, Types.INTEGER);  // PO_STATUS
    cs.registerOutParameter(5, Types.INTEGER);  // PO_RETURN_CODE

    // After fix: executeQuery() returns a non-null empty ResultSet
    ResultSet rs = cs.executeQuery();

    // The returned ResultSet must not be null (JDBC spec compliance)
    assertNotNull(
        "executeQuery() must not return null per JDBC spec. "
            + "Previously null caused NPE in HikariCP proxy on close().",
        rs);

    // The empty ResultSet should have no rows
    assertFalse("Empty ResultSet should have no rows", rs.next());

    // close() should work without NPE (this is where the customer's NPE occurred)
    rs.close();

    // Verify OUT parameters are still accessible after executeQuery()
    int returnCode = cs.getInt(5);
    assertEquals("PO_RETURN_CODE should be 0", 0, returnCode);

    int status = cs.getInt(4);
    assertEquals("PO_STATUS should be 1", 1, status);

    String accessType = cs.getString(3);
    assertEquals("PO_ACCESS_TYPE should be FULL", "FULL", accessType);

    cs.close();
  }

  /**
   * Demonstrates the workaround: using execute() instead of executeQuery().
   * This is the correct approach for stored procedures with only OUT parameters.
   */
  @Test
  public void testExecuteWorksCorrectly() throws Exception {
    String sql = "call SOA_CUST_ACCESS_PKG.RETRIEVE_PARTY_ACCESS_INFO(?, ?, ?, ?, ?)";
    CallableStatement cs = conn.prepareCall(sql);

    // IN parameters
    cs.setString(1, "PIN");
    cs.setString(2, "500090915");

    // OUT parameters
    cs.registerOutParameter(3, Types.VARCHAR);  // PO_ACCESS_TYPE
    cs.registerOutParameter(4, Types.INTEGER);  // PO_STATUS
    cs.registerOutParameter(5, Types.INTEGER);  // PO_RETURN_CODE

    // execute() works correctly for procedures with OUT params
    cs.execute();

    // Verify OUT parameters
    int returnCode = cs.getInt(5);
    assertEquals("PO_RETURN_CODE should be 0", 0, returnCode);

    int status = cs.getInt(4);
    assertEquals("PO_STATUS should be 1", 1, status);

    String accessType = cs.getString(3);
    assertEquals("PO_ACCESS_TYPE should be FULL", "FULL", accessType);

    cs.close();
  }
}
