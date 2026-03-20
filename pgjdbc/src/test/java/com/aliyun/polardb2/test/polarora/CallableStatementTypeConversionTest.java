/*
 * Portions Copyright (c) 2023, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Properties;

/**
 * POLAR: Test cases for CallableStatement out parameter type conversion.
 * Tests conversion between compatible type families (int, bigint, numeric, string, date/time, etc.)
 */
public class CallableStatementTypeConversionTest {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();

    // Create test functions for type conversion testing (Oracle PL/SQL mode)
    // Note: Oracle functions with out parameters still need a RETURN statement
    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_int_out(a int, b out int) RETURN int IS "
            + "BEGIN b := a + 1; RETURN b; END;");

    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_bigint_out(a bigint, b out bigint) RETURN bigint IS "
            + "BEGIN b := a * 10; RETURN b; END;");

    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_numeric_out(a numeric, b out numeric) RETURN numeric IS "
            + "BEGIN b := a + 0.5; RETURN b; END;");

    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_varchar_out(a varchar, b out varchar) RETURN varchar IS "
            + "BEGIN b := a || '_suffix'; RETURN b; END;");

    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_bool_out(a boolean, b out boolean) RETURN boolean IS "
            + "BEGIN b := NOT a; RETURN b; END;");

    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_double_out(a double precision, b out double precision) RETURN double precision IS "
            + "BEGIN b := a * 2.0; RETURN b; END;");

    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_real_out(a real, b out real) RETURN real IS "
            + "BEGIN b := a * 3.0; RETURN b; END;");

    // Function that returns a DATE out parameter (simulates CIS.GET_UNCONFIRMED_LINKAGE2 scenario)
    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_date_out(a int, b out date) RETURN int IS "
            + "BEGIN b := TO_DATE('2025-06-15', 'YYYY-MM-DD'); RETURN a; END;");

    // Function that returns a TIMESTAMP out parameter
    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_timestamp_out(a int, b out timestamp) RETURN int IS "
            + "BEGIN b := TO_TIMESTAMP('2025-06-15 12:30:00', 'YYYY-MM-DD HH24:MI:SS'); RETURN a; END;");

    // Function that returns DATE as VARCHAR (triggers ClassCastException: String -> Timestamp)
    // This simulates the case where PolarDB returns the date column type as VARCHAR
    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_date_as_varchar_out(a int, b out varchar) RETURN int IS "
            + "BEGIN b := TO_CHAR(TO_DATE('2025-06-15', 'YYYY-MM-DD'), 'YYYY-MM-DD'); RETURN a; END;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("DROP FUNCTION IF EXISTS test_int_out(int)");
    stmt.execute("DROP FUNCTION IF EXISTS test_bigint_out(bigint)");
    stmt.execute("DROP FUNCTION IF EXISTS test_numeric_out(numeric)");
    stmt.execute("DROP FUNCTION IF EXISTS test_varchar_out(varchar)");
    stmt.execute("DROP FUNCTION IF EXISTS test_bool_out(boolean)");
    stmt.execute("DROP FUNCTION IF EXISTS test_double_out(double precision)");
    stmt.execute("DROP FUNCTION IF EXISTS test_real_out(real)");
    stmt.execute("DROP FUNCTION IF EXISTS test_date_out(int)");
    stmt.execute("DROP FUNCTION IF EXISTS test_timestamp_out(int)");
    stmt.execute("DROP FUNCTION IF EXISTS test_date_as_varchar_out(int)");
    stmt.close();
    conn.close();
  }

  // ==================== Integer Family Tests ====================

  /**
   * Test: Register INTEGER out param, get as INTEGER (normal case)
   */
  @Test
  public void testIntToInt() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_int_out(?, ?) }");
    cs.registerOutParameter(1, Types.INTEGER);  // function return value
    cs.setInt(2, 100);
    cs.registerOutParameter(3, Types.INTEGER);  // out parameter b
    cs.execute();
    assertEquals(101, cs.getInt(3));
    cs.close();
  }

  /**
   * Test: Register BIGINT out param, get as BIGINT (normal case)
   */
  @Test
  public void testBigintToBigint() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_bigint_out(?, ?) }");
    cs.registerOutParameter(1, Types.BIGINT);   // function return value
    cs.setLong(2, 100L);
    cs.registerOutParameter(3, Types.BIGINT);   // out parameter b
    cs.execute();
    assertEquals(1000L, cs.getLong(3));
    cs.close();
  }

  /**
   * Test: Register INTEGER out param, get as VARCHAR (int -> string)
   */
  @Test
  public void testIntToVarchar() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_int_out(?, ?) }");
    cs.registerOutParameter(1, Types.INTEGER);  // function return value
    cs.setInt(2, 100);
    cs.registerOutParameter(3, Types.VARCHAR);  // out parameter b
    cs.execute();
    assertEquals("101", cs.getString(3));
    cs.close();
  }

  /**
   * Test: Register BIGINT out param, get as VARCHAR (bigint -> string)
   */
  @Test
  public void testBigintToVarchar() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_bigint_out(?, ?) }");
    cs.registerOutParameter(1, Types.BIGINT);   // function return value
    cs.setLong(2, 100L);
    cs.registerOutParameter(3, Types.VARCHAR);  // out parameter b
    cs.execute();
    assertEquals("1000", cs.getString(3));
    cs.close();
  }

  // ==================== Numeric Family Tests ====================

  /**
   * Test: Register NUMERIC out param, get as NUMERIC (normal case)
   */
  @Test
  public void testNumericToNumeric() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_numeric_out(?, ?) }");
    cs.registerOutParameter(1, Types.NUMERIC);  // function return value
    cs.setBigDecimal(2, new BigDecimal("10.0"));
    cs.registerOutParameter(3, Types.NUMERIC);  // out parameter b
    cs.execute();
    assertEquals(new BigDecimal("10.5"), cs.getBigDecimal(3));
    cs.close();
  }

  /**
   * Test: Register NUMERIC out param, get as VARCHAR (numeric -> string)
   */
  @Test
  public void testNumericToVarchar() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_numeric_out(?, ?) }");
    cs.registerOutParameter(1, Types.NUMERIC);  // function return value
    cs.setBigDecimal(2, new BigDecimal("10.0"));
    cs.registerOutParameter(3, Types.VARCHAR);  // out parameter b
    cs.execute();
    assertEquals("10.5", cs.getString(3));
    cs.close();
  }

  /**
   * Test: Register NUMERIC out param, get as INTEGER (numeric -> int)
   */
  @Test
  public void testNumericToInt() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_numeric_out(?, ?) }");
    cs.registerOutParameter(1, Types.NUMERIC);  // function return value
    cs.setBigDecimal(2, new BigDecimal("10.0"));
    cs.registerOutParameter(3, Types.INTEGER);  // out parameter b
    cs.execute();
    assertEquals(10, cs.getInt(3));
    cs.close();
  }

  /**
   * Test: Register NUMERIC out param, get as BIGINT (numeric -> bigint)
   */
  @Test
  public void testNumericToBigint() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_numeric_out(?, ?) }");
    cs.registerOutParameter(1, Types.NUMERIC);  // function return value
    cs.setBigDecimal(2, new BigDecimal("10.0"));
    cs.registerOutParameter(3, Types.BIGINT);   // out parameter b
    cs.execute();
    assertEquals(10L, cs.getLong(3));
    cs.close();
  }

  /**
   * Test: Register NUMERIC out param, get as DOUBLE (numeric -> double)
   */
  @Test
  public void testNumericToDouble() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_numeric_out(?, ?) }");
    cs.registerOutParameter(1, Types.NUMERIC);  // function return value
    cs.setBigDecimal(2, new BigDecimal("10.0"));
    cs.registerOutParameter(3, Types.DOUBLE);   // out parameter b
    cs.execute();
    assertEquals(10.5, cs.getDouble(3), 0.001);
    cs.close();
  }

  // ==================== Floating Point Tests ====================

  /**
   * Test: Register DOUBLE out param, get as DOUBLE (normal case)
   */
  @Test
  public void testDoubleToDouble() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_double_out(?, ?) }");
    cs.registerOutParameter(1, Types.DOUBLE);   // function return value
    cs.setDouble(2, 5.5);
    cs.registerOutParameter(3, Types.DOUBLE);   // out parameter b
    cs.execute();
    assertEquals(11.0, cs.getDouble(3), 0.001);
    cs.close();
  }

  /**
   * Test: Register DOUBLE out param, get as VARCHAR (double -> string)
   */
  @Test
  public void testDoubleToVarchar() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_double_out(?, ?) }");
    cs.registerOutParameter(1, Types.DOUBLE);   // function return value
    cs.setDouble(2, 5.5);
    cs.registerOutParameter(3, Types.VARCHAR);  // out parameter b
    cs.execute();
    String result = cs.getString(3);
    assertTrue(result.startsWith("11") || result.equals("11.0"));
    cs.close();
  }

  /**
   * Test: Register REAL out param, get as REAL (normal case)
   */
  @Test
  public void testRealToReal() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_real_out(?, ?) }");
    cs.registerOutParameter(1, Types.REAL);     // function return value
    cs.setFloat(2, 2.0f);
    cs.registerOutParameter(3, Types.REAL);     // out parameter b
    cs.execute();
    assertEquals(6.0f, cs.getFloat(3), 0.001f);
    cs.close();
  }

  /**
   * Test: Register REAL out param, get as VARCHAR (real -> string)
   */
  @Test
  public void testRealToVarchar() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_real_out(?, ?) }");
    cs.registerOutParameter(1, Types.REAL);     // function return value
    cs.setFloat(2, 2.0f);
    cs.registerOutParameter(3, Types.VARCHAR);  // out parameter b
    cs.execute();
    String result = cs.getString(3);
    assertTrue(result.startsWith("6") || result.equals("6.0"));
    cs.close();
  }

  // ==================== String Family Tests ====================

  /**
   * Test: Register VARCHAR out param, get as VARCHAR (normal case)
   */
  @Test
  public void testVarcharToVarchar() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_varchar_out(?, ?) }");
    cs.registerOutParameter(1, Types.VARCHAR);  // function return value
    cs.setString(2, "hello");
    cs.registerOutParameter(3, Types.VARCHAR);  // out parameter b
    cs.execute();
    assertEquals("hello_suffix", cs.getString(3));
    cs.close();
  }

  /**
   * Test: Register VARCHAR out param, get as INTEGER (string -> int)
   */
  @Test
  public void testVarcharToInt() throws SQLException {
    // Create a function that returns numeric string
    Statement stmt = conn.createStatement();
    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_varchar_number_out(a varchar, b out varchar) RETURN varchar IS "
            + "BEGIN b := '12345'; RETURN b; END;");
    stmt.close();

    // Use { ? = call ... } syntax to properly handle function return value + out parameter
    CallableStatement cs = conn.prepareCall("{ ? = call test_varchar_number_out(?, ?) }");
    cs.registerOutParameter(1, Types.VARCHAR);  // function return value
    cs.setString(2, "test");
    cs.registerOutParameter(3, Types.INTEGER);  // out parameter b
    cs.execute();
    assertEquals(12345, cs.getInt(3));
    cs.close();

    stmt = conn.createStatement();
    stmt.execute("DROP FUNCTION IF EXISTS test_varchar_number_out(varchar)");
    stmt.close();
  }

  /**
   * Test: Register VARCHAR out param, get as BIGINT (string -> bigint)
   */
  @Test
  public void testVarcharToBigint() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_varchar_bigint_out(a varchar, b out varchar) RETURN varchar IS "
            + "BEGIN b := '9999999999'; RETURN b; END;");
    stmt.close();

    CallableStatement cs = conn.prepareCall("{ ? = call test_varchar_bigint_out(?, ?) }");
    cs.registerOutParameter(1, Types.VARCHAR);  // function return value
    cs.setString(2, "test");
    cs.registerOutParameter(3, Types.BIGINT);   // out parameter b
    cs.execute();
    assertEquals(9999999999L, cs.getLong(3));
    cs.close();

    stmt = conn.createStatement();
    stmt.execute("DROP FUNCTION IF EXISTS test_varchar_bigint_out(varchar)");
    stmt.close();
  }

  /**
   * Test: Register VARCHAR out param, get as NUMERIC (string -> numeric)
   */
  @Test
  public void testVarcharToNumeric() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_varchar_decimal_out(a varchar, b out varchar) RETURN varchar IS "
            + "BEGIN b := '123.456'; RETURN b; END;");
    stmt.close();

    CallableStatement cs = conn.prepareCall("{ ? = call test_varchar_decimal_out(?, ?) }");
    cs.registerOutParameter(1, Types.VARCHAR);  // function return value
    cs.setString(2, "test");
    cs.registerOutParameter(3, Types.NUMERIC);  // out parameter b
    cs.execute();
    assertEquals(new BigDecimal("123.456"), cs.getBigDecimal(3));
    cs.close();

    stmt = conn.createStatement();
    stmt.execute("DROP FUNCTION IF EXISTS test_varchar_decimal_out(varchar)");
    stmt.close();
  }

  /**
   * Test: Register VARCHAR out param, get as DOUBLE (string -> double)
   */
  @Test
  public void testVarcharToDouble() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_varchar_double_out(a varchar, b out varchar) RETURN varchar IS "
            + "BEGIN b := '3.14159'; RETURN b; END;");
    stmt.close();

    CallableStatement cs = conn.prepareCall("{ ? = call test_varchar_double_out(?, ?) }");
    cs.registerOutParameter(1, Types.VARCHAR);  // function return value
    cs.setString(2, "test");
    cs.registerOutParameter(3, Types.DOUBLE);   // out parameter b
    cs.execute();
    assertEquals(3.14159, cs.getDouble(3), 0.00001);
    cs.close();

    stmt = conn.createStatement();
    stmt.execute("DROP FUNCTION IF EXISTS test_varchar_double_out(varchar)");
    stmt.close();
  }

  // ==================== Boolean Tests ====================

  /**
   * Test: Register BOOLEAN out param, get as BOOLEAN (normal case)
   */
  @Test
  public void testBoolToBool() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_bool_out(?, ?) }");
    cs.registerOutParameter(1, Types.BOOLEAN);  // function return value
    cs.setBoolean(2, true);
    cs.registerOutParameter(3, Types.BOOLEAN);  // out parameter b
    cs.execute();
    assertFalse(cs.getBoolean(3));
    cs.close();
  }

  /**
   * Test: Register BOOLEAN out param, get as VARCHAR (bool -> string)
   */
  @Test
  public void testBoolToVarchar() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_bool_out(?, ?) }");
    cs.registerOutParameter(1, Types.BOOLEAN);  // function return value
    cs.setBoolean(2, true);
    cs.registerOutParameter(3, Types.VARCHAR);  // out parameter b
    cs.execute();
    String result = cs.getString(3);
    // PostgreSQL returns "f" for false, "t" for true
    assertTrue(result.equals("f") || result.equals("false") || result.equals("t") || result.equals("true"));
    cs.close();
  }

  // ==================== Cross-Type Tests ====================

  /**
   * Test: Register INTEGER out param, get as BIGINT (widening conversion)
   */
  @Test
  public void testIntToBigint() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_int_out(?, ?) }");
    cs.registerOutParameter(1, Types.INTEGER);  // function return value
    cs.setInt(2, 100);
    cs.registerOutParameter(3, Types.BIGINT);   // out parameter b
    cs.execute();
    assertEquals(101L, cs.getLong(3));
    cs.close();
  }

  /**
   * Test: Register BIGINT out param, get as INTEGER (narrowing conversion)
   */
  @Test
  public void testBigintToInt() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_bigint_out(?, ?) }");
    cs.registerOutParameter(1, Types.BIGINT);   // function return value
    cs.setLong(2, 100L);
    cs.registerOutParameter(3, Types.INTEGER);  // out parameter b
    cs.execute();
    assertEquals(1000, cs.getInt(3));
    cs.close();
  }

  /**
   * Test: Register INTEGER out param, get as NUMERIC (int -> numeric)
   */
  @Test
  public void testIntToNumeric() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_int_out(?, ?) }");
    cs.registerOutParameter(1, Types.INTEGER);  // function return value
    cs.setInt(2, 100);
    cs.registerOutParameter(3, Types.NUMERIC);  // out parameter b
    cs.execute();
    assertEquals(new BigDecimal("101"), cs.getBigDecimal(3));
    cs.close();
  }

  /**
   * Test: Register INTEGER out param, get as DOUBLE (int -> double)
   */
  @Test
  public void testIntToDouble() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_int_out(?, ?) }");
    cs.registerOutParameter(1, Types.INTEGER);  // function return value
    cs.setInt(2, 100);
    cs.registerOutParameter(3, Types.DOUBLE);   // out parameter b
    cs.execute();
    assertEquals(101.0, cs.getDouble(3), 0.001);
    cs.close();
  }

  /**
   * Test: Register BIGINT out param, get as NUMERIC (bigint -> numeric)
   */
  @Test
  public void testBigintToNumeric() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_bigint_out(?, ?) }");
    cs.registerOutParameter(1, Types.BIGINT);   // function return value
    cs.setLong(2, 100L);
    cs.registerOutParameter(3, Types.NUMERIC);  // out parameter b
    cs.execute();
    assertEquals(new BigDecimal("1000"), cs.getBigDecimal(3));
    cs.close();
  }

  // ==================== Null Value Tests ====================

  /**
   * Test: Null value handling with VARCHAR registration
   */
  @Test
  public void testNullToVarchar() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_null_out(a int, b out varchar) RETURN varchar IS "
            + "BEGIN b := NULL; RETURN b; END;");
    stmt.close();

    CallableStatement cs = conn.prepareCall("{ ? = call test_null_out(?, ?) }");
    cs.registerOutParameter(1, Types.VARCHAR);  // function return value
    cs.setInt(2, 1);
    cs.registerOutParameter(3, Types.VARCHAR);  // out parameter b
    cs.execute();
    assertNull(cs.getString(3));
    assertTrue(cs.wasNull());
    cs.close();

    stmt = conn.createStatement();
    stmt.execute("DROP FUNCTION IF EXISTS test_null_out(int)");
    stmt.close();
  }

  /**
   * Test: Null value handling with INTEGER registration
   */
  @Test
  public void testNullToInt() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_null_int_out(a int, b out int) RETURN int IS "
            + "BEGIN b := NULL; RETURN b; END;");
    stmt.close();

    CallableStatement cs = conn.prepareCall("{ ? = call test_null_int_out(?, ?) }");
    cs.registerOutParameter(1, Types.INTEGER);  // function return value
    cs.setInt(2, 1);
    cs.registerOutParameter(3, Types.INTEGER);  // out parameter b
    cs.execute();
    assertEquals(0, cs.getInt(3));
    assertTrue(cs.wasNull());
    cs.close();

    stmt = conn.createStatement();
    stmt.execute("DROP FUNCTION IF EXISTS test_null_int_out(int)");
    stmt.close();
  }

  // ==================== Oracle-style BEGIN-END Block Tests ====================

  /**
   * Test: Oracle-style begin-end block with out parameter registered as VARCHAR
   */
  @Test
  public void testBeginEndBlockIntToVarchar() throws SQLException {
    // This tests the user's original use case
    Statement stmt = conn.createStatement();
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE test_swallow4(a int, b out int) IS "
            + "BEGIN b := a + 1; END;");
    stmt.close();

    CallableStatement cs = conn.prepareCall("begin test_swallow4(?, ?); end;");
    cs.setInt(1, 11111);
    cs.registerOutParameter(2, Types.VARCHAR);
    cs.execute();
    assertEquals("11112", cs.getString(2));
    cs.close();

    stmt = conn.createStatement();
    stmt.execute("DROP PROCEDURE IF EXISTS test_swallow4");
    stmt.close();
  }

  /**
   * Test: Oracle-style begin-end block with out parameter registered as NUMERIC
   */
  @Test
  public void testBeginEndBlockIntToNumeric() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE test_swallow5(a int, b out int) IS "
            + "BEGIN b := a + 1; END;");
    stmt.close();

    CallableStatement cs = conn.prepareCall("begin test_swallow5(?, ?); end;");
    cs.setInt(1, 100);
    cs.registerOutParameter(2, Types.NUMERIC);
    cs.execute();
    assertEquals(new BigDecimal("101"), cs.getBigDecimal(2));
    cs.close();

    stmt = conn.createStatement();
    stmt.execute("DROP PROCEDURE IF EXISTS test_swallow5");
    stmt.close();
  }

  /**
   * Test: Oracle-style begin-end block with out parameter not assigned (no result set)
   */
  @Test
  public void testBeginEndBlockNoResult() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE test_swallow3(a int, b out int) IS "
            + "BEGIN NULL; END;");
    stmt.close();

    CallableStatement cs = conn.prepareCall("begin test_swallow3(?, ?); end;");
    cs.setInt(1, 1);
    cs.registerOutParameter(2, Types.NUMERIC);
    cs.execute();
    assertEquals(0, cs.getInt(2));
    assertTrue(cs.wasNull());
    cs.close();

    stmt = conn.createStatement();
    stmt.execute("DROP PROCEDURE IF EXISTS test_swallow3");
    stmt.close();
  }

  // ==================== Date/Timestamp OUT parameter Tests ====================

  /**
   * 复现: cs.getDate(n) 对 DATE 类型 OUT 参数抛出
   * ClassCastException: Cannot cast 'java.lang.String' to 'java.sql.Timestamp'
   *
   * <p>根因: 当数据库列类型为 VARCHAR（PolarDB 某些存储过程返回日期为 VARCHAR），
   * 但用户注册的是 TIMESTAMP 时：
   *   convertOutParamValue(String, VARCHAR, TIMESTAMP)
   *     -&gt; isStringType(VARCHAR)=false, isStringType(VARCHAR 为 columnType) ? 不对
   *     -&gt; parseStringToType(str, TIMESTAMP) -&gt; default -&gt; 返回原始 String
   *   callResult[j] = String
   *   getTimestamp(j) -&gt; (Timestamp) String -&gt; ClassCastException
   *
   * <p>同样， getDate() 在 mapDateToTimestamp=true 时内部调 getTimestamp()，也会触发。
   */
  @Test
  public void testGetDateOutParamClassCastException() throws SQLException {
    // DB returns VARCHAR, user registered TIMESTAMP -> String stored in callResult
    // -> getTimestamp() does (Timestamp) String -> ClassCastException
    CallableStatement cs = conn.prepareCall("{ ? = call test_date_as_varchar_out(?, ?) }");
    cs.registerOutParameter(1, Types.INTEGER);
    cs.setInt(2, 1);
    cs.registerOutParameter(3, Types.TIMESTAMP);  // registered as TIMESTAMP but DB returns VARCHAR
    cs.execute();

    // 预期触发: ClassCastException: Cannot cast 'java.lang.String' to 'java.sql.Timestamp'
    // 因为 callResult[2] = "2025-06-15"(String), getTimestamp() 尝试 (Timestamp) "2025-06-15"
    Timestamp ts = cs.getTimestamp(3);
    assertTrue("timestamp should not be null", ts != null);
    assertEquals("2025-06-15 00:00:00.0", ts.toString());
    cs.close();
  }

  /**
   * 正常场景: DATE OUT 参数类型匹配，应返回正确的 Date
   */
  @Test
  public void testGetDateOutParamNormal() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_date_out(?, ?) }");
    cs.registerOutParameter(1, Types.INTEGER);
    cs.setInt(2, 1);
    cs.registerOutParameter(3, Types.DATE);
    cs.execute();

    Date date = cs.getDate(3);
    assertTrue("date should not be null", date != null);
    assertEquals("2025-06-15", date.toString());
    cs.close();
  }

  /**
   * 正常场景: TIMESTAMP OUT 参数类型匹配，应返回正确的 Timestamp
   */
  @Test
  public void testGetTimestampOutParamNormal() throws SQLException {
    CallableStatement cs = conn.prepareCall("{ ? = call test_timestamp_out(?, ?) }");
    cs.registerOutParameter(1, Types.INTEGER);
    cs.setInt(2, 1);
    cs.registerOutParameter(3, Types.TIMESTAMP);
    cs.execute();

    Timestamp ts = cs.getTimestamp(3);
    assertTrue("timestamp should not be null", ts != null);
    assertEquals("2025-06-15 12:30:00.0", ts.toString());
    cs.close();
  }
}
