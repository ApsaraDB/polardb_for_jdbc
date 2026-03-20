/*
 * Portions Copyright (c) 2025, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;

import com.aliyun.polardb2.jdbc.PgStruct;
import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Properties;

/**
 * Tests for composite type with null Date field encoding issue.
 *
 * <p>Reproduces: ERROR: invalid input syntax for type date: "NULL"
 * When passing PgStruct array with null Date fields to stored procedure.
 */
public class CompositeTypeNullDateTest {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();

    // Create a composite type with date field (similar to quot_pol_info)
    stmt.execute(
        "CREATE OR REPLACE TYPE test_pol_info AS ("
            + "action_code VARCHAR(10),"
            + "apply_dt DATE,"
            + "pol_eff_dt DATE,"
            + "mode_prem NUMERIC(11,2)"
            + ")");

    // Create a table of that type
    stmt.execute(
        "CREATE OR REPLACE TYPE test_tbl_pol_info AS TABLE OF test_pol_info");

    // Create a simple stored procedure that accepts single composite type
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE test_proc_single_pol_info("
            + "p_data IN test_pol_info"
            + ") IS "
            + "BEGIN "
            + "  NULL; "
            + "END;");

    // Create a simple stored procedure that accepts the table type
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE test_proc_pol_info("
            + "p_data IN test_tbl_pol_info"
            + ") IS "
            + "BEGIN "
            + "  NULL; "
            + "END;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("DROP PROCEDURE IF EXISTS test_proc_single_pol_info");
    stmt.execute("DROP PROCEDURE IF EXISTS test_proc_pol_info");
    stmt.execute("DROP TYPE IF EXISTS test_tbl_pol_info");
    stmt.execute("DROP TYPE IF EXISTS test_pol_info");
    stmt.close();
    conn.close();
  }

  /**
   * Tests that composite type with null DATE fields works correctly.
   *
   * <p>When a composite type has a DATE field with null value, PostgresStructConverter
   * must encode it as an empty field (nothing between commas), not the word NULL.
   * PolarDB interprets NULL word inside a composite literal as a string, causing:
   * ERROR: invalid input syntax for type date: "NULL"
   */
  @Test
  public void testCompositeTypeWithNullDateField() throws SQLException {
    // Create PgStruct with null Date fields
    Object[] attributes = new Object[] {
        "NFORPU",           // action_code VARCHAR
        null,               // apply_dt DATE - NULL!
        null,               // pol_eff_dt DATE - NULL!
        null                // mode_prem NUMERIC - NULL!
    };

    PgStruct struct = new PgStruct("test_pol_info", attributes);

    // Verify fix: null fields must be empty (not 'NULL' word)
    assertEquals("(\"NFORPU\",,,)", struct.toString());

    // Use setObject which internally calls setStruct for PgStruct
    // After fix, this should NOT throw:
    // ERROR: invalid input syntax for type date: "NULL"
    CallableStatement cs = conn.prepareCall("{ call test_proc_single_pol_info(?) }");
    cs.setObject(1, struct);
    cs.execute();
    cs.close();
  }

  /**
   * Test with mixed null and non-null date values.
   */
  @Test
  public void testCompositeTypeWithMixedDateFields() throws SQLException {
    // First struct: all nulls
    Object[] attrs1 = new Object[] {
        "TEST1",
        null,                       // null date
        null,                       // null date
        null                        // null numeric
    };

    // Second struct: with values
    Object[] attrs2 = new Object[] {
        "TEST2",
        Date.valueOf("2025-06-15"), // non-null date
        Date.valueOf("2025-07-01"), // non-null date
        new java.math.BigDecimal("1234.56")
    };

    PgStruct struct1 = new PgStruct("test_pol_info", attrs1);
    PgStruct struct2 = new PgStruct("test_pol_info", attrs2);

    Struct[] structs = new Struct[] { struct1, struct2 };
    Array array = conn.createArrayOf("test_pol_info", structs);

    CallableStatement cs = conn.prepareCall("{ call test_proc_pol_info(?) }");
    cs.setArray(1, array);
    cs.execute();
    cs.close();
  }
}
