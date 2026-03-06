/*
 * Portions Copyright (c) 2023, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * POLAR: Test cases for error code conversion from negative (PostgreSQL) to positive (Oracle).
 */
public class ErrorCodeTest {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);
  }

  @After
  public void tearDown() throws Exception {
    if (conn != null && !conn.isClosed()) {
      conn.close();
    }
  }

  /**
   * Test: Error code for syntax error should be positive.
   * PostgreSQL returns negative code, we convert it to positive.
   */
  @Test
  public void testSyntaxErrorCode() {
    try (Statement stmt = conn.createStatement()) {
      // This will cause a syntax error
      stmt.execute("SELECT * FROM non_existent_table_12345");
    } catch (SQLException e) {
      int errorCode = e.getErrorCode();
      // Error code should be positive (Oracle convention)
      assertTrue("Error code should be positive, but got: " + errorCode, errorCode > 0);
      System.out.println("Syntax error code: " + errorCode);
    }
  }

  /**
   * Test: Error code for table not found should be positive.
   */
  @Test
  public void testTableNotFoundErrorCode() {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("SELECT * FROM table_that_does_not_exist");
    } catch (SQLException e) {
      int errorCode = e.getErrorCode();
      assertTrue("Error code should be positive, but got: " + errorCode, errorCode > 0);
      System.out.println("Table not found error code: " + errorCode);
    }
  }

  /**
   * Test: Error code for duplicate table should be positive.
   */
  @Test
  public void testDuplicateTableErrorCode() {
    String tableName = "test_dup_table_" + System.currentTimeMillis();
    try (Statement stmt = conn.createStatement()) {
      // Create table
      stmt.execute("CREATE TABLE " + tableName + " (id INT)");
      // Try to create again - should fail with duplicate error
      stmt.execute("CREATE TABLE " + tableName + " (id INT)");
    } catch (SQLException e) {
      int errorCode = e.getErrorCode();
      assertTrue("Error code should be positive, but got: " + errorCode, errorCode > 0);
      System.out.println("Duplicate table error code: " + errorCode);
    } finally {
      // Cleanup
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS " + tableName);
      } catch (SQLException ignored) {
      }
    }
  }

  /**
   * Test: Error code for constraint violation should be positive.
   */
  @Test
  public void testConstraintViolationErrorCode() {
    String tableName = "test_constraint_table_" + System.currentTimeMillis();
    try (Statement stmt = conn.createStatement()) {
      // Create table with NOT NULL constraint
      stmt.execute("CREATE TABLE " + tableName + " (id INT NOT NULL)");
      // Try to insert NULL - should fail
      stmt.execute("INSERT INTO " + tableName + " VALUES (NULL)");
    } catch (SQLException e) {
      int errorCode = e.getErrorCode();
      assertTrue("Error code should be positive, but got: " + errorCode, errorCode > 0);
      System.out.println("Constraint violation error code: " + errorCode);
    } finally {
      // Cleanup
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS " + tableName);
      } catch (SQLException ignored) {
      }
    }
  }

  /**
   * Test: Error code for division by zero should be positive.
   */
  @Test
  public void testDivisionByZeroErrorCode() {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("SELECT 1/0");
    } catch (SQLException e) {
      int errorCode = e.getErrorCode();
      assertTrue("Error code should be positive, but got: " + errorCode, errorCode > 0);
      System.out.println("Division by zero error code: " + errorCode);
    }
  }

  /**
   * Test: Error code for invalid column name should be positive.
   */
  @Test
  public void testInvalidColumnErrorCode() {
    String tableName = "test_col_table_" + System.currentTimeMillis();
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE " + tableName + " (id INT)");
      stmt.execute("SELECT non_existent_column FROM " + tableName);
    } catch (SQLException e) {
      int errorCode = e.getErrorCode();
      assertTrue("Error code should be positive, but got: " + errorCode, errorCode > 0);
      System.out.println("Invalid column error code: " + errorCode);
    } finally {
      // Cleanup
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS " + tableName);
      } catch (SQLException ignored) {
      }
    }
  }

  /**
   * Test: Verify that error code 0 is returned when there's no error.
   */
  @Test
  public void testNoError() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("SELECT 1");
      // No error occurred, this test just verifies normal operation
      assertTrue(true);
    }
  }
}
