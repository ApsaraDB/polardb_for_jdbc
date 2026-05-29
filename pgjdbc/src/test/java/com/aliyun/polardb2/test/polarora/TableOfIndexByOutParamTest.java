/*
 * Portions Copyright (c) 2024, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.test.TestUtil;
import com.aliyun.polardb2.util.PGobject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;

/**
 * Regression tests for {@code TABLE OF ... INDEX BY} OUT parameter handling.
 *
 * <p>Background: A previous TypeInfoCache change force-mapped {@code typtype='a'}
 * (PolarDB nested table / VARRAY / associative array) to {@link Types#ARRAY},
 * which broke legacy code that relied on
 * {@code registerOutParameter(idx, Types.OTHER)} returning a {@link PGobject}
 * with the raw HSTORE-style text. The fix:
 * <ol>
 *   <li>TypeInfoCache no longer overrides typtype='a' to ARRAY.</li>
 *   <li>PgCallableStatement still honours
 *       {@code registerOutParameter(idx, Types.ARRAY)} by wrapping the PGobject
 *       text into a {@code PgArray}.</li>
 *   <li>ArrayDecoding preserves the original keys so
 *       {@code Array.getResultSet()} can surface them as the index column.</li>
 * </ol>
 */
public class TableOfIndexByOutParamTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();
    try {
      stmt.execute("DROP PACKAGE IF EXISTS pkg_index_by_test");
    } catch (SQLException ignore) {
      // first-time setup
    }

    stmt.execute(
        "CREATE OR REPLACE PACKAGE pkg_index_by_test AS\n"
            + "  TYPE tbl_str_idx IS TABLE OF VARCHAR2(50) INDEX BY BINARY_INTEGER;\n"
            + "  PROCEDURE get_strings(p_out OUT tbl_str_idx);\n"
            + "END pkg_index_by_test;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY pkg_index_by_test AS\n"
            + "  PROCEDURE get_strings(p_out OUT tbl_str_idx) IS\n"
            + "  BEGIN\n"
            + "    p_out(1) := 'alpha';\n"
            + "    p_out(2) := 'beta';\n"
            + "    p_out(3) := 'gamma';\n"
            + "  END;\n"
            + "END pkg_index_by_test;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    if (conn != null) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP PACKAGE IF EXISTS pkg_index_by_test");
      } catch (SQLException ignore) {
        // tear-down failures are not interesting
      }
      conn.close();
    }
  }

  /**
   * Restores the legacy behaviour where
   * {@code registerOutParameter(idx, Types.OTHER)} returns a {@link PGobject}
   * whose value is the server's raw HSTORE-style text.
   */
  @Test
  public void testRegisterAsOtherReturnsPGobject() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ call pkg_index_by_test.get_strings(?) }")) {
      cs.registerOutParameter(1, Types.OTHER);
      cs.execute();

      Object out = cs.getObject(1);
      assertNotNull("OUT param should not be null", out);
      assertTrue(
          "Expected PGobject for Types.OTHER registration, got "
              + out.getClass().getName(),
          out instanceof PGobject);

      PGobject pgo = (PGobject) out;
      String raw = pgo.getValue();
      assertNotNull("PGobject value should not be null", raw);
      // The raw text uses HSTORE-style 'k => "v"' separators on PolarDB.
      assertTrue("Raw value should contain '=>': " + raw, raw.contains("=>"));
      assertTrue("Raw value should contain 'alpha': " + raw, raw.contains("alpha"));
      assertTrue("Raw value should contain 'beta': " + raw, raw.contains("beta"));
      assertTrue("Raw value should contain 'gamma': " + raw, raw.contains("gamma"));
    }
  }

  /**
   * The Types.ARRAY registration path must keep working after the
   * TypeInfoCache change: the PGobject coming back from the server should be
   * unwrapped into a {@link Array} whose {@code getArray()} returns the
   * element values.
   */
  @Test
  public void testRegisterAsArrayReturnsPgArray() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ call pkg_index_by_test.get_strings(?) }")) {
      cs.registerOutParameter(1, Types.ARRAY, "tbl_str_idx");
      cs.execute();

      Array arr = cs.getArray(1);
      assertNotNull("getArray should not return null", arr);

      Object[] values = (Object[]) arr.getArray();
      assertEquals("Should have 3 elements", 3, values.length);
      assertEquals("alpha", String.valueOf(values[0]));
      assertEquals("beta", String.valueOf(values[1]));
      assertEquals("gamma", String.valueOf(values[2]));
    }
  }

  /**
   * {@code Array.getResultSet()} must surface the server's original index keys
   * (e.g. {@code 1}, {@code 2}, {@code 3} for INDEX BY BINARY_INTEGER) instead
   * of the synthetic 1..n integer sequence the standard PG path uses.
   */
  @Test
  public void testGetResultSetPreservesKeys() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ call pkg_index_by_test.get_strings(?) }")) {
      cs.registerOutParameter(1, Types.ARRAY, "tbl_str_idx");
      cs.execute();

      Array arr = cs.getArray(1);
      assertNotNull(arr);

      int rowCount = 0;
      try (ResultSet rs = arr.getResultSet()) {
        while (rs.next()) {
          rowCount++;
          String key = rs.getString(1);
          String value = rs.getString(2);
          assertNotNull("INDEX column should not be null", key);
          assertNotNull("VALUE column should not be null", value);
          // Key should be the original PL/SQL index ('1'/'2'/'3') or PolarDB's
          // '-' default-marker; never an empty string.
          assertFalse("Key should not be empty", key.isEmpty());
          assertTrue(
              "Key should be numeric or '-': " + key,
              "-".equals(key) || key.matches("-?\\d+"));
        }
      }
      assertEquals("Should iterate exactly 3 rows", 3, rowCount);
    }
  }
}
