package com.aliyun.polardb2.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.PGProperty;
import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Test case for bigintAsNumeric property.
 * When enabled, getObject() on BIGINT columns should return BigDecimal instead of Long.
 */
public class BigintAsNumericTest {

  private Connection con;
  private Connection conWithBigintAsNumeric;

  @Before
  public void setUp() throws Exception {
    // Normal connection
    con = TestUtil.openDB();

    // Connection with bigintAsNumeric enabled
    Properties props = new Properties();
    PGProperty.BIGINT_AS_NUMERIC.set(props, true);
    conWithBigintAsNumeric = TestUtil.openDB(props);

    // Create test table
    Statement stmt = con.createStatement();
    TestUtil.createTable(con, "test_bigint_numeric",
        "id serial primary key, bigint_value bigint, int_value integer");

    // Insert test data
    stmt.executeUpdate("INSERT INTO test_bigint_numeric (bigint_value, int_value) VALUES (123456789, 100)");
    stmt.executeUpdate("INSERT INTO test_bigint_numeric (bigint_value, int_value) VALUES (9223372036854775807, 200)"); // Long.MAX_VALUE
    stmt.executeUpdate("INSERT INTO test_bigint_numeric (bigint_value, int_value) VALUES (-9223372036854775808, 300)"); // Long.MIN_VALUE

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropTable(con, "test_bigint_numeric");
    TestUtil.closeDB(con);
    TestUtil.closeDB(conWithBigintAsNumeric);
  }

  /**
   * Test normal behavior - bigint returns Long
   */
  @Test
  public void testBigintAsLongDefault() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT bigint_value FROM test_bigint_numeric WHERE id = 1");

    assertTrue(rs.next());
    Object result = rs.getObject(1);

    // Default behavior: should return Long
    assertNotNull(result);
    assertTrue("Default behavior should return Long", result instanceof Long);
    assertEquals(123456789L, ((Long) result).longValue());

    rs.close();
    stmt.close();
  }

  /**
   * Test bigintAsNumeric enabled - bigint returns BigDecimal
   */
  @Test
  public void testBigintAsBigDecimalWhenEnabled() throws SQLException {
    Statement stmt = conWithBigintAsNumeric.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT bigint_value FROM test_bigint_numeric WHERE id = 1");

    assertTrue(rs.next());
    Object result = rs.getObject(1);

    // With bigintAsNumeric enabled: should return BigDecimal
    assertNotNull(result);
    assertTrue("With bigintAsNumeric enabled, should return BigDecimal", result instanceof BigDecimal);
    assertEquals(new BigDecimal("123456789"), (BigDecimal) result);

    rs.close();
    stmt.close();
  }

  /**
   * Test COUNT(*) scenario - the main use case
   */
  @Test
  public void testCountWithBigintAsNumeric() throws SQLException {
    PreparedStatement prep = conWithBigintAsNumeric.prepareStatement("SELECT COUNT(*) FROM test_bigint_numeric");
    prep.execute();
    ResultSet res = prep.getResultSet();

    assertTrue(res.next());
    Object r = res.getObject(1);

    // Should be BigDecimal now
    assertNotNull(r);
    assertTrue("COUNT(*) should return BigDecimal when bigintAsNumeric is enabled", r instanceof BigDecimal);

    // Can cast directly to BigDecimal
    BigDecimal b = (BigDecimal) r;
    assertEquals(new BigDecimal("3"), b);

    res.close();
    prep.close();
  }

  /**
   * Test with Long.MAX_VALUE
   */
  @Test
  public void testLongMaxValue() throws SQLException {
    Statement stmt = conWithBigintAsNumeric.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT bigint_value FROM test_bigint_numeric WHERE id = 2");

    assertTrue(rs.next());
    Object result = rs.getObject(1);

    assertNotNull(result);
    assertTrue(result instanceof BigDecimal);
    assertEquals(new BigDecimal("9223372036854775807"), (BigDecimal) result);

    rs.close();
    stmt.close();
  }

  /**
   * Test with Long.MIN_VALUE
   */
  @Test
  public void testLongMinValue() throws SQLException {
    Statement stmt = conWithBigintAsNumeric.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT bigint_value FROM test_bigint_numeric WHERE id = 3");

    assertTrue(rs.next());
    Object result = rs.getObject(1);

    assertNotNull(result);
    assertTrue(result instanceof BigDecimal);
    assertEquals(new BigDecimal("-9223372036854775808"), (BigDecimal) result);

    rs.close();
    stmt.close();
  }

  /**
   * Test that INTEGER columns are not affected
   */
  @Test
  public void testIntegerNotAffected() throws SQLException {
    Statement stmt = conWithBigintAsNumeric.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT int_value FROM test_bigint_numeric WHERE id = 1");

    assertTrue(rs.next());
    Object result = rs.getObject(1);

    // INTEGER should still return Integer, not BigDecimal
    assertNotNull(result);
    assertTrue("INTEGER columns should still return Integer", result instanceof Integer);
    assertEquals(100, ((Integer) result).intValue());

    rs.close();
    stmt.close();
  }

  /**
   * Test getLong() still works normally
   */
  @Test
  public void testGetLongStillWorks() throws SQLException {
    Statement stmt = conWithBigintAsNumeric.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT bigint_value FROM test_bigint_numeric WHERE id = 1");

    assertTrue(rs.next());

    // getLong() should still work
    long value = rs.getLong(1);
    assertEquals(123456789L, value);

    rs.close();
    stmt.close();
  }

  /**
   * Test getBigDecimal() still works normally
   */
  @Test
  public void testGetBigDecimalStillWorks() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT bigint_value FROM test_bigint_numeric WHERE id = 1");

    assertTrue(rs.next());

    // getBigDecimal() should work on both connections
    BigDecimal value = rs.getBigDecimal(1);
    assertEquals(new BigDecimal("123456789"), value);

    rs.close();
    stmt.close();
  }

  /**
   * Test prepared statement with bigintAsNumeric
   */
  @Test
  public void testPreparedStatementWithBigintAsNumeric() throws SQLException {
    PreparedStatement pstmt = conWithBigintAsNumeric.prepareStatement(
        "SELECT bigint_value FROM test_bigint_numeric WHERE id = ?");
    pstmt.setInt(1, 1);

    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    Object result = rs.getObject(1);
    assertNotNull(result);
    assertTrue(result instanceof BigDecimal);
    assertEquals(new BigDecimal("123456789"), (BigDecimal) result);

    rs.close();
    pstmt.close();
  }
}
