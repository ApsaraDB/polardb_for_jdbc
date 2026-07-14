/*
 * Portions Copyright (c) 2026, Alibaba Group Holding Limited
 *
 * Tests for PgCallableStatement.getClob(int) which previously threw
 * SQLFeatureNotSupportedException ("getClob(int) is not yet implemented").
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;

/**
 * Verifies that CallableStatement.getClob(int) returns the OUT parameter value
 * as a Clob (text-backed under clobAsText), instead of throwing
 * SQLFeatureNotSupportedException.
 */
public class CallableStatementGetClobTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    props.put("clobAsText", "true");
    conn = TestUtil.openDB(props);

    try (Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE OR REPLACE FUNCTION get_clob_val(p_in int) RETURN text AS\n"
          + "BEGIN\n"
          + "  RETURN 'clob-content-' || p_in;\n"
          + "END;");

      stmt.execute("CREATE OR REPLACE FUNCTION get_clob_null() RETURN text AS\n"
          + "BEGIN\n"
          + "  RETURN NULL;\n"
          + "END;");
    }
  }

  @After
  public void tearDown() throws Exception {
    if (conn != null) {
      try (Statement stmt = conn.createStatement()) {
        try {
          stmt.execute("DROP FUNCTION IF EXISTS get_clob_val(int)");
        } catch (SQLException ignore) {
          // ignore
        }
        try {
          stmt.execute("DROP FUNCTION IF EXISTS get_clob_null()");
        } catch (SQLException ignore) {
          // ignore
        }
      }
      conn.close();
    }
  }

  /**
   * getClob(int) should return the text OUT value wrapped as a Clob.
   */
  @Test
  public void testGetClobByIndex() throws SQLException {
    try (CallableStatement cs = conn.prepareCall("{ ? = call get_clob_val(?) }")) {
      cs.registerOutParameter(1, Types.CLOB);
      cs.setInt(2, 42);
      cs.execute();

      Clob clob = cs.getClob(1);
      assertNotNull("getClob should not return null for non-null text", clob);
      String content = clob.getSubString(1, (int) clob.length());
      assertEquals("clob-content-42", content);
    }
  }

  /**
   * getClob(int) should also work when the OUT parameter is registered as VARCHAR
   * (clobAsText path), since the value comes back as text.
   */
  @Test
  public void testGetClobRegisteredAsVarchar() throws SQLException {
    try (CallableStatement cs = conn.prepareCall("{ ? = call get_clob_val(?) }")) {
      cs.registerOutParameter(1, Types.VARCHAR);
      cs.setInt(2, 7);
      cs.execute();

      Clob clob = cs.getClob(1);
      assertNotNull(clob);
      assertEquals("clob-content-7", clob.getSubString(1, (int) clob.length()));
    }
  }

  /**
   * getClob(int) should return null when the OUT value is SQL NULL.
   */
  @Test
  public void testGetClobNull() throws SQLException {
    try (CallableStatement cs = conn.prepareCall("{ ? = call get_clob_null() }")) {
      cs.registerOutParameter(1, Types.CLOB);
      cs.execute();

      Clob clob = cs.getClob(1);
      assertNull("getClob should return null for SQL NULL", clob);
    }
  }
}
