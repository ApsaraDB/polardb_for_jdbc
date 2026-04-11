/*
 * Portions Copyright (c) 2023, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * Test case for numberStripTrailingZeros property.
 * When enabled (default), getString() on NUMERIC/DECIMAL columns should strip trailing zeros.
 * For example, 911.000 should display as "911", 3.10 as "3.1".
 */
public class NumberStripTrailingZerosTest {

  private Connection connEnabled;
  private Connection connDisabled;

  @Before
  public void setUp() throws Exception {
    // Connection with numberStripTrailingZeros=true (default)
    Properties propsEnabled = new Properties();
    propsEnabled.put("numberStripTrailingZeros", "true");
    connEnabled = TestUtil.openDB(propsEnabled);

    // Connection with numberStripTrailingZeros=false
    Properties propsDisabled = new Properties();
    propsDisabled.put("numberStripTrailingZeros", "false");
    connDisabled = TestUtil.openDB(propsDisabled);

    TestUtil.createTable(connEnabled, "test_num_strip",
        "id int, val numeric(20,5)");

    Statement stmt = connEnabled.createStatement();
    stmt.executeUpdate("INSERT INTO test_num_strip VALUES (1, 911.000)");
    stmt.executeUpdate("INSERT INTO test_num_strip VALUES (2, 3.10)");
    stmt.executeUpdate("INSERT INTO test_num_strip VALUES (3, 100)");
    stmt.executeUpdate("INSERT INTO test_num_strip VALUES (4, 0.50)");
    stmt.executeUpdate("INSERT INTO test_num_strip VALUES (5, 123.45600)");
    stmt.executeUpdate("INSERT INTO test_num_strip VALUES (6, 0.00)");
    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropTable(connEnabled, "test_num_strip");
    TestUtil.closeDB(connEnabled);
    TestUtil.closeDB(connDisabled);
  }

  /**
   * When numberStripTrailingZeros=true, trailing zeros after decimal point should be removed.
   */
  @Test
  public void testStripTrailingZerosEnabled() throws Exception {
    Statement stmt = connEnabled.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT val FROM test_num_strip ORDER BY id");

    // id=1: 911.00000 -> "911"
    assertTrue(rs.next());
    String val1 = rs.getString(1);
    assertNotNull(val1);
    assertEquals("911.000 should display as 911", "911", val1);

    // id=2: 3.10000 -> "3.1"
    assertTrue(rs.next());
    String val2 = rs.getString(1);
    assertNotNull(val2);
    assertEquals("3.10 should display as 3.1", "3.1", val2);

    // id=3: 100.00000 -> "100"
    assertTrue(rs.next());
    String val3 = rs.getString(1);
    assertNotNull(val3);
    assertEquals("100 should display as 100", "100", val3);

    // id=4: 0.50000 -> "0.5"
    assertTrue(rs.next());
    String val4 = rs.getString(1);
    assertNotNull(val4);
    assertEquals("0.50 should display as 0.5", "0.5", val4);

    // id=5: 123.45600 -> "123.456"
    assertTrue(rs.next());
    String val5 = rs.getString(1);
    assertNotNull(val5);
    assertEquals("123.45600 should display as 123.456", "123.456", val5);

    // id=6: 0.00000 -> "0"
    assertTrue(rs.next());
    String val6 = rs.getString(1);
    assertNotNull(val6);
    assertEquals("0.00 should display as 0", "0", val6);

    rs.close();
    stmt.close();
  }

  /**
   * When numberStripTrailingZeros=false, trailing zeros should be preserved as-is from database.
   * Note: The actual format depends on the database's text representation of numeric.
   */
  @Test
  public void testStripTrailingZerosDisabled() throws Exception {
    Statement stmt = connDisabled.createStatement();
    // First, check what the database actually returns for a value with trailing zeros
    ResultSet rs = stmt.executeQuery(
        "SELECT 911.000::numeric(20,5) AS val");

    assertTrue(rs.next());
    String rawVal = rs.getString(1);
    assertNotNull(rawVal);

    // Now query from the table - should get the same raw value (no stripping)
    rs = stmt.executeQuery(
        "SELECT val FROM test_num_strip WHERE id = 1");
    assertTrue(rs.next());
    String val = rs.getString(1);
    assertNotNull(val);
    assertEquals("With strip disabled, value should match raw database format",
        rawVal, val);

    rs.close();
    stmt.close();
  }

  /**
   * Test with unscaled numeric (no fixed decimal places).
   */
  @Test
  public void testStripTrailingZerosWithPlainNumeric() throws Exception {
    TestUtil.createTable(connEnabled, "test_num_strip_plain", "id int, val numeric");
    try {
      Statement stmt = connEnabled.createStatement();
      stmt.executeUpdate("INSERT INTO test_num_strip_plain VALUES (1, 911.000)");
      stmt.executeUpdate("INSERT INTO test_num_strip_plain VALUES (2, 42)");

      ResultSet rs = stmt.executeQuery(
          "SELECT val FROM test_num_strip_plain ORDER BY id");

      // 911.000 -> "911"
      assertTrue(rs.next());
      assertEquals("911", rs.getString(1));

      // 42 -> "42"
      assertTrue(rs.next());
      assertEquals("42", rs.getString(1));

      rs.close();
      stmt.close();
    } finally {
      TestUtil.dropTable(connEnabled, "test_num_strip_plain");
    }
  }

  /**
   * Test that default connection (no explicit setting) also strips trailing zeros,
   * since the default value is true.
   */
  @Test
  public void testDefaultConnectionStripsTrailingZeros() throws Exception {
    try (Connection defaultConn = TestUtil.openDB()) {
      Statement stmt = defaultConn.createStatement();
      ResultSet rs = stmt.executeQuery(
          "SELECT val FROM test_num_strip WHERE id = 1");

      assertTrue(rs.next());
      String val = rs.getString(1);
      assertNotNull(val);
      assertEquals("Default connection should strip trailing zeros", "911", val);

      rs.close();
      stmt.close();
    }
  }
}
