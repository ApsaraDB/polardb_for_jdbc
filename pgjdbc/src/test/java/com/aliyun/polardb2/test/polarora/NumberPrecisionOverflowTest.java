/*
 * Portions Copyright (c) 2026, Alibaba Group Holding Limited
 *
 * Tests for Oracle NUMBER(p,s) precision enforcement behavior.
 * Validates that PolarDB Oracle-compatible mode follows the same rules:
 *   - OBJECT TYPE attributes: NO runtime constraint
 *   - PL/SQL local variables: NO runtime constraint
 *   - TABLE column INSERT/UPDATE: Enforces precision (ORA-01438 equivalent)
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNoException;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.Properties;

/**
 * Reproduces the Oracle NUMBER(p,s) precision enforcement behavior documented in test.md.
 *
 * <p>Oracle rules (verified on Oracle 23ai):
 * <ul>
 *   <li>OBJECT TYPE attributes with NUMBER(p,s) — no runtime check</li>
 *   <li>PL/SQL local variables NUMBER(p,s) — no runtime check</li>
 *   <li>INSERT into table column NUMBER(p,s) — enforces precision, throws ORA-01438</li>
 *   <li>Excess decimal digits — silently rounded (no error)</li>
 * </ul>
 *
 * <p>This test validates the same behavior on PolarDB Oracle-compatible mode.
 */
public class NumberPrecisionOverflowTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    try (Statement stmt = conn.createStatement()) {
      // Create test table with NUMBER(11,2) column
      stmt.execute("DROP TABLE IF EXISTS t_test_num_overflow");
      stmt.execute("CREATE TABLE t_test_num_overflow ("
          + "  id       NUMBER(10),"
          + "  name     VARCHAR2(100),"
          + "  amount   NUMBER(11,2)"
          + ")");

      // Create composite type
      try {
        stmt.execute("DROP TYPE tbl_test_num");
      } catch (SQLException ignore) {
      }
      try {
        stmt.execute("DROP TYPE rec_test_num");
      } catch (SQLException ignore) {
      }

      stmt.execute("CREATE OR REPLACE TYPE rec_test_num AS OBJECT ("
          + "  id       NUMBER(10),"
          + "  name     VARCHAR2(100),"
          + "  amount   NUMBER(11,2)"
          + ")");

      stmt.execute("CREATE OR REPLACE TYPE tbl_test_num AS TABLE OF rec_test_num");

      // Procedure A: only traverse input, no INSERT (tests parameter passing)
      stmt.execute(
          "CREATE OR REPLACE PROCEDURE p_test_num_no_insert(\n"
              + "  p_data IN tbl_test_num\n"
              + ") IS\n"
              + "  v_amount NUMBER(11,2);\n"
              + "BEGIN\n"
              + "  FOR i IN 1..p_data.COUNT LOOP\n"
              + "    v_amount := p_data(i).amount;\n"
              + "    NULL;\n"
              + "  END LOOP;\n"
              + "END;");

      // Procedure B: INSERT into table (tests column precision enforcement)
      stmt.execute(
          "CREATE OR REPLACE PROCEDURE p_test_num_insert(\n"
              + "  p_data IN tbl_test_num\n"
              + ") IS\n"
              + "BEGIN\n"
              + "  FOR i IN 1..p_data.COUNT LOOP\n"
              + "    INSERT INTO t_test_num_overflow(id, name, amount)\n"
              + "    VALUES (p_data(i).id, p_data(i).name, p_data(i).amount);\n"
              + "  END LOOP;\n"
              + "END;");

      // Procedure C: assign to local NUMBER(11,2) variable
      stmt.execute(
          "CREATE OR REPLACE PROCEDURE p_test_num_local_var(\n"
              + "  p_data IN tbl_test_num,\n"
              + "  p_result OUT NUMBER\n"
              + ") IS\n"
              + "  v_amount NUMBER(11,2);\n"
              + "BEGIN\n"
              + "  FOR i IN 1..p_data.COUNT LOOP\n"
              + "    v_amount := p_data(i).amount;\n"
              + "  END LOOP;\n"
              + "  p_result := v_amount;\n"
              + "END;");

    } catch (SQLException e) {
      assumeNoException("PolarDB Oracle-compatible types not available", e);
    }
  }

  @After
  public void tearDown() throws Exception {
    if (conn != null) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP PROCEDURE IF EXISTS p_test_num_no_insert");
        stmt.execute("DROP PROCEDURE IF EXISTS p_test_num_insert");
        stmt.execute("DROP PROCEDURE IF EXISTS p_test_num_local_var");
        stmt.execute("DROP TABLE IF EXISTS t_test_num_overflow");
        try {
          stmt.execute("DROP TYPE tbl_test_num");
        } catch (SQLException ignore) {
        }
        try {
          stmt.execute("DROP TYPE rec_test_num");
        } catch (SQLException ignore) {
        }
      } catch (SQLException ignore) {
      }
      conn.close();
    }
  }

  // =========================================================================
  // PART A: Parameter passing — no precision check expected
  // =========================================================================

  /**
   * Normal value within NUMBER(11,2) range.
   * Expected: passes without error.
   */
  @Test
  public void testPartA_normalValue() throws SQLException {
    callNoInsert(new BigDecimal("12345.67"));
    // No exception = pass
  }

  /**
   * Boundary maximum for NUMBER(11,2): 999999999.99
   * Expected: passes without error.
   */
  @Test
  public void testPartA_boundaryMax() throws SQLException {
    callNoInsert(new BigDecimal("999999999.99"));
  }

  /**
   * Integer part 10 digits (exceeds 9-digit limit of NUMBER(11,2)).
   * Oracle: ORA-06502 (PL/SQL: numeric or value error: number precision too large)
   * when using inline SQL constructor tbl_test_num(rec_test_num(1, 'test', ?)).
   * Expected: precision error (consistent with Oracle).
   */
  @Test
  public void testPartA_integerOverflow1Digit() throws SQLException {
    try {
      callNoInsert(new BigDecimal("9999999999.99"));
      fail("Expected precision error for overflow via inline SQL constructor");
    } catch (SQLException e) {
      // ORA-06502 equivalent: numeric precision too large
      assertTrue("Expected numeric overflow error, got: " + e.getMessage(),
          e.getMessage().contains("overflow") || e.getMessage().contains("precision")
              || "22003".equals(e.getSQLState()));
    }
  }

  /**
   * Integer part 15 digits — massively exceeds.
   * Oracle: ORA-06502.
   * Expected: precision error.
   */
  @Test
  public void testPartA_integerOverflowMassive() throws SQLException {
    try {
      callNoInsert(new BigDecimal("123456789012345.67"));
      fail("Expected precision error for massive overflow via inline SQL constructor");
    } catch (SQLException e) {
      assertTrue("Expected numeric overflow error, got: " + e.getMessage(),
          e.getMessage().contains("overflow") || e.getMessage().contains("precision")
              || "22003".equals(e.getSQLState()));
    }
  }

  /**
   * Negative overflow.
   * Oracle: ORA-06502.
   * Expected: precision error.
   */
  @Test
  public void testPartA_negativeOverflow() throws SQLException {
    try {
      callNoInsert(new BigDecimal("-9999999999.99"));
      fail("Expected precision error for negative overflow via inline SQL constructor");
    } catch (SQLException e) {
      assertTrue("Expected numeric overflow error, got: " + e.getMessage(),
          e.getMessage().contains("overflow") || e.getMessage().contains("precision")
              || "22003".equals(e.getSQLState()));
    }
  }

  /**
   * Excess decimal digits (6 digits vs allowed 2).
   * Expected: passes without error — no truncation at parameter level.
   */
  @Test
  public void testPartA_excessDecimalDigits() throws SQLException {
    callNoInsert(new BigDecimal("123.456789"));
  }

  // =========================================================================
  // PART B: INSERT into table — precision check enforced
  // =========================================================================

  /**
   * Normal value inserted to NUMBER(11,2) column.
   * Expected: success.
   */
  @Test
  public void testPartB_normalInsert() throws SQLException {
    callInsert(new BigDecimal("12345.67"));
    assertInsertedAmount("12345.67");
  }

  /**
   * Boundary max inserted.
   * Expected: success.
   */
  @Test
  public void testPartB_boundaryMaxInsert() throws SQLException {
    callInsert(new BigDecimal("999999999.99"));
    assertInsertedAmount("999999999.99");
  }

  /**
   * Integer overflow (10 digits) on INSERT.
   * Expected: ORA-01438 equivalent error (NUMERIC_VALUE_OUT_OF_RANGE).
   */
  @Test
  public void testPartB_integerOverflowInsert() throws SQLException {
    try {
      callInsert(new BigDecimal("9999999999.99"));
      fail("Expected precision overflow error on INSERT");
    } catch (SQLException e) {
      // ORA-01438 = "value larger than specified precision"
      // PG/PolarDB equivalent: SQLSTATE 22003 = numeric_value_out_of_range
      System.out.println("[diagnostic] INSERT overflow error: " + e.getMessage()
          + ", SQLState=" + e.getSQLState());
      assertTrue("Expected numeric overflow SQLState 22003 or ORA-01438, got: "
              + e.getSQLState(),
          "22003".equals(e.getSQLState()) || e.getMessage().contains("01438")
              || e.getMessage().contains("precision"));
    }
  }

  /**
   * Massive integer overflow on INSERT.
   * Expected: same precision error.
   */
  @Test
  public void testPartB_massiveOverflowInsert() throws SQLException {
    try {
      callInsert(new BigDecimal("123456789012345.67"));
      fail("Expected precision overflow error on INSERT");
    } catch (SQLException e) {
      assertTrue("Expected numeric overflow error",
          "22003".equals(e.getSQLState()) || e.getMessage().contains("01438")
              || e.getMessage().contains("precision"));
    }
  }

  /**
   * Excess decimal digits on INSERT — should be silently rounded.
   * Expected: INSERT succeeds, value rounded to 2 decimal places (123.46).
   */
  @Test
  public void testPartB_excessDecimalRounded() throws SQLException {
    callInsert(new BigDecimal("123.456789"));
    // Oracle rounds 123.456789 -> 123.46 (round half up)
    assertInsertedAmount("123.46");
  }

  // =========================================================================
  // PART C: PL/SQL local variable — no precision check expected
  // =========================================================================

  /**
   * Assign overflow value to NUMBER(11,2) local variable.
   * Oracle: ORA-06502 (PL/SQL: numeric or value error: number precision too large).
   * Expected: precision error (consistent with Oracle).
   */
  @Test
  public void testPartC_localVarOverflow() throws SQLException {
    try {
      callLocalVar(new BigDecimal("9999999999.99"));
      fail("Expected precision error for overflow assignment to NUMBER(11,2) local variable");
    } catch (SQLException e) {
      // ORA-06502 equivalent
      assertTrue("Expected numeric overflow error, got: " + e.getMessage(),
          e.getMessage().contains("overflow") || e.getMessage().contains("precision")
              || "22003".equals(e.getSQLState()));
    }
  }

  /**
   * Normal value assigned to local variable.
   * Expected: passes, value preserved.
   */
  @Test
  public void testPartC_localVarNormal() throws SQLException {
    BigDecimal result = callLocalVar(new BigDecimal("12345.67"));
    assertEquals(0, new BigDecimal("12345.67").compareTo(result));
  }

  // =========================================================================
  // Helper methods
  // =========================================================================

  private void callNoInsert(BigDecimal amount) throws SQLException {
    String sql = "{ call p_test_num_no_insert(tbl_test_num(rec_test_num(1, 'test', ?))) }";
    try (CallableStatement cs = conn.prepareCall(sql)) {
      cs.setBigDecimal(1, amount);
      cs.execute();
    }
  }

  private void callInsert(BigDecimal amount) throws SQLException {
    // Clean table first
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("DELETE FROM t_test_num_overflow");
    }
    String sql = "{ call p_test_num_insert(tbl_test_num(rec_test_num(1, 'test', ?))) }";
    try (CallableStatement cs = conn.prepareCall(sql)) {
      cs.setBigDecimal(1, amount);
      cs.execute();
    }
  }

  private BigDecimal callLocalVar(BigDecimal amount) throws SQLException {
    String sql = "{ call p_test_num_local_var(tbl_test_num(rec_test_num(1, 'test', ?)), ?) }";
    try (CallableStatement cs = conn.prepareCall(sql)) {
      cs.setBigDecimal(1, amount);
      cs.registerOutParameter(2, Types.NUMERIC);
      cs.execute();
      return cs.getBigDecimal(2);
    }
  }

  private void assertInsertedAmount(String expectedAmount) throws SQLException {
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT amount FROM t_test_num_overflow WHERE id = 1")) {
      assertTrue("Expected one row in table", rs.next());
      BigDecimal actual = rs.getBigDecimal(1);
      assertEquals("Inserted amount mismatch",
          0, new BigDecimal(expectedAmount).compareTo(actual));
    }
  }

  // =========================================================================
  // PART D: createStruct + createArrayOf + setArray (Oracle ojdbc equivalent)
  //
  // This mirrors the Oracle pattern:
  //   Struct struct = oraConn.createStruct("REC_TEST_NUM", attrs);
  //   Array  array  = oraConn.createOracleArray("TBL_TEST_NUM", new Object[]{struct});
  //   cs.setArray(1, array);
  // =========================================================================

  /**
   * Normal value via createStruct/createArrayOf/setArray — should work.
   */
  @Test
  public void testPartD_structArray_normalValue() throws SQLException {
    callNoInsertViaStructArray(new BigDecimal("12345.67"));
  }

  /**
   * Boundary max via Struct/Array.
   * Expected: passes (same as Oracle — no precision check at parameter level).
   */
  @Test
  public void testPartD_structArray_boundaryMax() throws SQLException {
    callNoInsertViaStructArray(new BigDecimal("999999999.99"));
  }

  /**
   * Integer overflow (10 digits) via Struct/Array — tests if createStruct or
   * the server enforces NUMBER(11,2) precision on the OBJECT TYPE attribute.
   * Oracle: does NOT enforce. PolarDB: may enforce (this is the behavioral gap).
   */
  @Test
  public void testPartD_structArray_integerOverflow() throws SQLException {
    callNoInsertViaStructArray(new BigDecimal("9999999999.99"));
  }

  /**
   * Massive overflow (15 digits) via Struct/Array.
   * Oracle: passes. PolarDB: may fail.
   */
  @Test
  public void testPartD_structArray_massiveOverflow() throws SQLException {
    callNoInsertViaStructArray(new BigDecimal("123456789012345.67"));
  }

  /**
   * Negative overflow via Struct/Array.
   */
  @Test
  public void testPartD_structArray_negativeOverflow() throws SQLException {
    callNoInsertViaStructArray(new BigDecimal("-9999999999.99"));
  }

  /**
   * Excess decimal digits via Struct/Array.
   * Expected: passes at parameter level (no truncation until INSERT).
   */
  @Test
  public void testPartD_structArray_excessDecimals() throws SQLException {
    callNoInsertViaStructArray(new BigDecimal("123.456789"));
  }

  /**
   * INSERT via Struct/Array with overflow — should trigger precision error.
   */
  @Test
  public void testPartD_structArray_insertOverflow() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("DELETE FROM t_test_num_overflow");
    }
    try {
      callInsertViaStructArray(new BigDecimal("9999999999.99"));
      fail("Expected precision overflow error on INSERT via Struct/Array");
    } catch (SQLException e) {
      System.out.println("[diagnostic] Struct/Array INSERT overflow: "
          + e.getMessage() + ", SQLState=" + e.getSQLState());
      assertTrue("Expected numeric overflow error",
          "22003".equals(e.getSQLState()) || e.getMessage().contains("01438")
              || e.getMessage().contains("precision") || e.getMessage().contains("overflow"));
    }
  }

  /**
   * INSERT via Struct/Array with normal value — should succeed.
   */
  @Test
  public void testPartD_structArray_insertNormal() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("DELETE FROM t_test_num_overflow");
    }
    callInsertViaStructArray(new BigDecimal("12345.67"));
    assertInsertedAmount("12345.67");
  }

  // --- PART D helper methods ---

  private void callNoInsertViaStructArray(BigDecimal amount) throws SQLException {
    Object[] attrs = new Object[]{Integer.valueOf(1), "test", amount};
    Struct struct = conn.createStruct("rec_test_num", attrs);
    Array array = conn.createArrayOf("tbl_test_num", new Struct[]{struct});

    try (CallableStatement cs = conn.prepareCall("{ call p_test_num_no_insert(?) }")) {
      cs.setArray(1, array);
      cs.execute();
    }
  }

  private void callInsertViaStructArray(BigDecimal amount) throws SQLException {
    Object[] attrs = new Object[]{Integer.valueOf(1), "test", amount};
    Struct struct = conn.createStruct("rec_test_num", attrs);
    Array array = conn.createArrayOf("tbl_test_num", new Struct[]{struct});

    try (CallableStatement cs = conn.prepareCall("{ call p_test_num_insert(?) }")) {
      cs.setArray(1, array);
      cs.execute();
    }
  }
}
