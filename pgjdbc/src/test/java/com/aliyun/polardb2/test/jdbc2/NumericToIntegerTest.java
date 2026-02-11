package com.aliyun.polardb2.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Test case for conversion from NUMERIC type to Integer.class using getObject method.
 * This test reproduces the issue: "conversion to class java.lang.Integer from numeric not supported"
 */
public class NumericToIntegerTest {

  private Connection con;

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
    Statement stmt = con.createStatement();

    // Create table with NUMERIC(2,0) type - equivalent to NUMBER(2,0) in Oracle
    TestUtil.createTable(con, "test_numeric_to_int", "id serial primary key, value numeric(2,0)");

    // Insert test data
    stmt.executeUpdate("INSERT INTO test_numeric_to_int (value) VALUES (10)");
    stmt.executeUpdate("INSERT INTO test_numeric_to_int (value) VALUES (99)");
    stmt.executeUpdate("INSERT INTO test_numeric_to_int (value) VALUES (-5)");
    stmt.executeUpdate("INSERT INTO test_numeric_to_int (value) VALUES (NULL)");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropTable(con, "test_numeric_to_int");
    TestUtil.closeDB(con);
  }

  @Test
  public void testGetObjectWithIntegerClass() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT id, value FROM test_numeric_to_int ORDER BY id");

    // First row: value = 10
    assertTrue(rs.next());
    Integer value1 = rs.getObject("value", Integer.class);
    assertNotNull(value1);
    assertEquals(Integer.valueOf(10), value1);

    // Second row: value = 99
    assertTrue(rs.next());
    Integer value2 = rs.getObject("value", Integer.class);
    assertNotNull(value2);
    assertEquals(Integer.valueOf(99), value2);

    // Third row: value = -5
    assertTrue(rs.next());
    Integer value3 = rs.getObject("value", Integer.class);
    assertNotNull(value3);
    assertEquals(Integer.valueOf(-5), value3);

    // Fourth row: value = NULL
    assertTrue(rs.next());
    Integer value4 = rs.getObject("value", Integer.class);
    assertNull(value4);

    rs.close();
    stmt.close();
  }

  @Test
  public void testGetObjectWithIntegerClassByIndex() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT value FROM test_numeric_to_int WHERE value = 10");

    assertTrue(rs.next());
    Integer value = rs.getObject(1, Integer.class);
    assertNotNull(value);
    assertEquals(Integer.valueOf(10), value);

    rs.close();
    stmt.close();
  }

  @Test
  public void testGetObjectWithShortClass() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT value FROM test_numeric_to_int WHERE value = 10");

    assertTrue(rs.next());
    Short value = rs.getObject(1, Short.class);
    assertNotNull(value);
    assertEquals(Short.valueOf((short)10), value);

    rs.close();
    stmt.close();
  }

  @Test
  public void testGetObjectWithLongClass() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT value FROM test_numeric_to_int WHERE value = 10");

    assertTrue(rs.next());
    Long value = rs.getObject(1, Long.class);
    assertNotNull(value);
    assertEquals(Long.valueOf(10L), value);

    rs.close();
    stmt.close();
  }

  @Test
  public void testPreparedStatementWithNumericType() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "SELECT value FROM test_numeric_to_int WHERE value = ?");
    pstmt.setInt(1, 10);

    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    Integer value = rs.getObject(1, Integer.class);
    assertNotNull(value);
    assertEquals(Integer.valueOf(10), value);

    rs.close();
    pstmt.close();
  }

  @Test
  public void testDifferentNumericPrecisions() throws SQLException {
    Statement stmt = con.createStatement();

    // Test NUMERIC(10,0) - should also work
    TestUtil.createTable(con, "test_numeric_10", "value numeric(10,0)");
    stmt.executeUpdate("INSERT INTO test_numeric_10 VALUES (1234567890)");

    ResultSet rs = stmt.executeQuery("SELECT value FROM test_numeric_10");
    assertTrue(rs.next());

    Integer value = rs.getObject(1, Integer.class);
    assertNotNull(value);
    assertEquals(Integer.valueOf(1234567890), value);

    rs.close();
    TestUtil.dropTable(con, "test_numeric_10");
    stmt.close();
  }
}
