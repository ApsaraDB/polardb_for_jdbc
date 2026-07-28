/*
 * Portions Copyright (c) 2026, Alibaba Group Holding Limited
 *
 * Reproduces customer issue (NbSuspenseAdjustDAO.adjustNBSuspense):
 *   PSQLException: Parameter of type java.sql.Types=12 was registered,
 *   but call to getInt (sqltype=java.sql.Types=4) was made.
 * The OUT parameter was registered as VARCHAR (Types=12) and then read
 * via getInt(). Oracle ojdbc tolerates this and converts; verify current
 * PolarDB driver behavior.
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;

/**
 * Customer pattern: anonymous block "begin NB_SUSPENSE_ADJUST(?,...); end;"
 * with an OUT parameter registered as Types.VARCHAR but fetched via getInt().
 */
public class RegisterVarcharGetIntTest {
  private Connection con;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("unnamedProc", "true");
    props.put("callFunctionMode", "true");
    con = TestUtil.openDB(props);
    try (Statement s = con.createStatement()) {
      s.execute("CREATE OR REPLACE PROCEDURE p_reg_varchar_getint"
          + "(p_in IN NUMBER, p_out_num OUT NUMBER, p_out_str OUT VARCHAR2) IS\n"
          + "BEGIN\n"
          + "  p_out_num := p_in + 1;\n"
          + "  p_out_str := to_char(p_in + 2);\n"
          + "END;");
    }
  }

  @After
  public void tearDown() throws SQLException {
    try (Statement s = con.createStatement()) {
      s.execute("DROP PROCEDURE IF EXISTS p_reg_varchar_getint");
    } finally {
      con.close();
    }
  }

  /**
   * NUMBER OUT parameter registered as Types.VARCHAR, read via getInt().
   * This is the exact customer pattern that raised the PSQLException.
   */
  @Test
  public void testNumberOutRegisteredVarcharGetInt() throws Exception {
    try (CallableStatement cs =
             con.prepareCall("begin p_reg_varchar_getint(?, ?, ?); end;")) {
      cs.setInt(1, 15);
      cs.registerOutParameter(2, Types.VARCHAR);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();
      int v = cs.getInt(2);
      System.out.println("[diag] NUMBER out registered as VARCHAR, getInt = " + v);
      assertEquals(16, v);
    }
  }

  /**
   * VARCHAR2 OUT parameter (numeric content) registered as Types.VARCHAR,
   * read via getInt().
   */
  @Test
  public void testVarcharOutRegisteredVarcharGetInt() throws Exception {
    try (CallableStatement cs =
             con.prepareCall("begin p_reg_varchar_getint(?, ?, ?); end;")) {
      cs.setInt(1, 15);
      cs.registerOutParameter(2, Types.VARCHAR);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();
      int v = cs.getInt(3);
      System.out.println("[diag] VARCHAR2 out registered as VARCHAR, getInt = " + v);
      assertEquals(17, v);
    }
  }

  /**
   * Contrast: proper registration types work as expected.
   */
  @Test
  public void testProperRegistrationWorks() throws Exception {
    try (CallableStatement cs =
             con.prepareCall("begin p_reg_varchar_getint(?, ?, ?); end;")) {
      cs.setInt(1, 15);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();
      assertEquals(16, cs.getInt(2));
      assertEquals("17", cs.getString(3));
    }
  }

  /**
   * All numeric getters work against a VARCHAR-registered OUT parameter.
   */
  @Test
  public void testAllNumericGettersOnVarcharRegistration() throws Exception {
    try (CallableStatement cs =
             con.prepareCall("begin p_reg_varchar_getint(?, ?, ?); end;")) {
      cs.setInt(1, 15);
      cs.registerOutParameter(2, Types.VARCHAR);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();
      assertEquals(16L, cs.getLong(2));
      assertEquals((short) 16, cs.getShort(2));
      assertEquals((byte) 16, cs.getByte(2));
      assertEquals(16.0f, cs.getFloat(2), 0.0001f);
      assertEquals(16.0d, cs.getDouble(2), 0.0001d);
      assertEquals(0, new BigDecimal("16").compareTo(cs.getBigDecimal(2)));
      // string-typed OUT parameter with numeric content
      assertEquals(17L, cs.getLong(3));
      assertEquals(0, new BigDecimal("17").compareTo(cs.getBigDecimal(3)));
    }
  }

  /**
   * Non-numeric string content still raises the original type-mismatch error.
   */
  @Test
  public void testNonNumericStringStillThrows() throws Exception {
    try (Statement s = con.createStatement()) {
      s.execute("CREATE OR REPLACE PROCEDURE p_reg_varchar_text(p_out OUT VARCHAR2) IS\n"
          + "BEGIN\n"
          + "  p_out := 'not-a-number';\n"
          + "END;");
    }
    try (CallableStatement cs = con.prepareCall("begin p_reg_varchar_text(?); end;")) {
      cs.registerOutParameter(1, Types.VARCHAR);
      cs.execute();
      try {
        cs.getInt(1);
        fail("getInt on non-numeric string should throw");
      } catch (SQLException e) {
        // PSQLState.MOST_SPECIFIC_TYPE_DOES_NOT_MATCH - original strict error preserved
        assertEquals("2200G", e.getSQLState());
      }
    } finally {
      try (Statement s = con.createStatement()) {
        s.execute("DROP PROCEDURE IF EXISTS p_reg_varchar_text");
      }
    }
  }

  /**
   * NULL OUT value registered as VARCHAR: numeric getters return 0 and
   * wasNull() reports true, matching ojdbc semantics.
   */
  @Test
  public void testNullValueRegisteredVarcharGetInt() throws Exception {
    try (Statement s = con.createStatement()) {
      s.execute("CREATE OR REPLACE PROCEDURE p_reg_varchar_null(p_out OUT VARCHAR2) IS\n"
          + "BEGIN\n"
          + "  p_out := NULL;\n"
          + "END;");
    }
    try (CallableStatement cs = con.prepareCall("begin p_reg_varchar_null(?); end;")) {
      cs.registerOutParameter(1, Types.VARCHAR);
      cs.execute();
      assertEquals(0, cs.getInt(1));
      assertTrue("wasNull should be true for NULL OUT value", cs.wasNull());
    } finally {
      try (Statement s = con.createStatement()) {
        s.execute("DROP PROCEDURE IF EXISTS p_reg_varchar_null");
      }
    }
  }
}
