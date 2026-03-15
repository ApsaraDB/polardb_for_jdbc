/*
 * Portions Copyright (c) 2023, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertNotNull;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;

/**
 * Tests for cross-package TABLE OF RECORD type handling in CallableStatement
 * and Connection.createArrayOf.
 */
public class CrossPkgTableOfRecordTest {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();

    // Package A: defines the RECORD type and TABLE OF RECORD type
    stmt.execute(
        "CREATE OR REPLACE PACKAGE pkg_types AS\n"
        + "  TYPE REC_PARAM_BATCH IS RECORD (\n"
        + "    POL_NUM     VARCHAR2(50),\n"
        + "    QUOTA_NUM   VARCHAR2(10),\n"
        + "    FLD_TYP     VARCHAR2(1),\n"
        + "    FLD_NM      VARCHAR2(6),\n"
        + "    FLD_ATTRIB  VARCHAR2(6),\n"
        + "    FLD_VALU    VARCHAR2(255)\n"
        + "  );\n"
        + "  TYPE TBL_PARAM_BATCH IS TABLE OF REC_PARAM_BATCH INDEX BY BINARY_INTEGER;\n"
        + "END pkg_types;");

    // Package B: uses the type from Package A as an OUT parameter
    stmt.execute(
        "CREATE OR REPLACE PACKAGE pkg_batch_ops AS\n"
        + "  PROCEDURE get_batch_params(\n"
        + "    p_pol_num   IN  VARCHAR2,\n"
        + "    p_result    OUT pkg_types.TBL_PARAM_BATCH\n"
        + "  );\n"
        + "END pkg_batch_ops;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY pkg_batch_ops AS\n"
        + "  PROCEDURE get_batch_params(\n"
        + "    p_pol_num   IN  VARCHAR2,\n"
        + "    p_result    OUT pkg_types.TBL_PARAM_BATCH\n"
        + "  ) IS\n"
        + "  BEGIN\n"
        + "    p_result(1).POL_NUM    := p_pol_num;\n"
        + "    p_result(1).QUOTA_NUM  := 'Q001';\n"
        + "    p_result(1).FLD_TYP    := 'C';\n"
        + "    p_result(1).FLD_NM     := 'PARAM1';\n"
        + "    p_result(1).FLD_ATTRIB := 'USD';\n"
        + "    p_result(1).FLD_VALU   := '100.00';\n"
        + "  END;\n"
        + "END pkg_batch_ops;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("DROP PACKAGE IF EXISTS pkg_batch_ops");
    stmt.execute("DROP PACKAGE IF EXISTS pkg_types");
    stmt.close();
    conn.close();
  }

  /**
   * Reproduces: out parameter was of type java.sql.Types=1111 however
   * type java.sql.Types=2003 was registered.
   */
  @Test
  public void testCrossPkgTableOfRecordOutParam() throws SQLException {
    CallableStatement cs = conn.prepareCall(
        "{ call pkg_batch_ops.get_batch_params(?, ?) }");
    cs.setString(1, "POL12345");
    cs.registerOutParameter(2, Types.ARRAY);
    cs.execute();
    Object result = cs.getObject(2);
    assertNotNull("Cross-package TABLE OF RECORD result should not be null", result);
    cs.close();
  }

  /**
   * Reproduces the crash in TypeInfoCache.getArrayDelimiter when
   * Connection.createArrayOf is called with a cross-package TABLE OF RECORD type.
   *
   * <p>The type exists in pg_type (via getPGArrayType fallback) but has no typelem,
   * so getArrayDelimiter's query returns no rows and throws
   * "No results were returned by the query."
   */
  @Test
  public void testCreateArrayOfCrossPkgType() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT oid, typname, typelem, typarray FROM pg_type "
        + "WHERE typname LIKE '%tbl_param_batch%'");
    boolean found = false;
    int typeOid = 0;
    int typelem = 0;
    while (rs.next()) {
      found = true;
      typeOid = rs.getInt("oid");
      typelem = rs.getInt("typelem");
      System.out.println("Found type: oid=" + rs.getInt("oid")
          + " typname=" + rs.getString("typname")
          + " typelem=" + rs.getInt("typelem")
          + " typarray=" + rs.getInt("typarray"));
    }
    rs.close();

    if (found && typelem == 0) {
      // Type exists but has no typelem — this crashes getArrayDelimiter
      rs = stmt.executeQuery(
          "SELECT e.typdelim FROM pg_catalog.pg_type t, pg_catalog.pg_type e "
          + "WHERE t.oid = " + typeOid + " and t.typelem = e.oid");
      boolean hasDelim = rs.next();
      rs.close();
      System.out.println("Delimiter query returned rows: " + hasDelim);

      try {
        conn.createArrayOf("pkg_types.tbl_param_batch", new Object[]{});
      } catch (SQLException e) {
        System.out.println("createArrayOf threw: " + e.getMessage());
        if (e.getMessage() != null
            && e.getMessage().contains("No results were returned")) {
          throw new SQLException(
              "getArrayDelimiter crashed for cross-package TABLE OF RECORD type "
              + "(typelem=0). The driver should handle this gracefully.", e);
        }
        // "Unable to find server array type" is acceptable
      }
    } else if (!found) {
      System.out.println("Type not found in pg_type — skipping");
    } else {
      conn.createArrayOf("pkg_types.tbl_param_batch", new Object[]{});
    }
    stmt.close();
  }
}
