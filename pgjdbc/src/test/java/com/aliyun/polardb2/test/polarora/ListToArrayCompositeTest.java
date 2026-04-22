/*
 * Portions Copyright (c) 2025, Alibaba Group Holding Limited
 *
 * Reproduce bug: "invalid input syntax for type numeric: 102.0}"
 *
 * Root cause: When List<Object[]>.toArray() produces Object[] (not Object[][]),
 * isObjectArray2D returns false because the compile-time component type is Object,
 * not Object[].  OBJECT_ARRAY.appendArray then wraps each inner Object[] with
 * braces {val1,val2,...} instead of record literal parentheses (val1,val2,...).
 *
 * PostgreSQL then sees "102.0}" (including the closing brace) as a numeric value
 * for the last field of a record, causing:
 *   ERROR: invalid input syntax for type numeric: "102.0}"
 *   Where: while processing column "clm_deduct_amt_med_crcy"
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Reproduces the production error from ClaimCreationServiceImpl.createPaClaim:
 * <pre>
 * com.aliyun.polardb2.util.PSQLException: ERROR: invalid input syntax for type numeric: "102.0}"
 *   Where: while processing column "clm_deduct_amt_med_crcy"
 *   unnamed portal parameter $49 = '...'
 * </pre>
 *
 * <p>The production code calls:
 * <pre>
 *   List&lt;Object[]&gt; rows = genbnftDtlsArrList(dto.getBenefitDetails());
 *   Object[] data = rows.toArray();   // produces Object[], NOT Object[][]
 *   Array sqlArray = conn.createArrayOf("TAB_PRE_CLM_BNFT_DTLS", data);
 * </pre>
 *
 * <p>List.toArray() returns Object[] with Object[] elements inside. The driver's
 * isObjectArray2D check only detects statically-typed Object[][] and misses this
 * case. OBJECT_ARRAY wraps inner arrays with braces, producing
 * {{"HS","5000.0",...,"102.0"},{"RB",...}} instead of the correct
 * {"(HS,5000.0,...,102.0)","(RB,...)"}.
 */
public class ListToArrayCompositeTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();

    // Simplified composite type matching TAB_PRE_CLM_BNFT_DTLS structure
    stmt.execute(
        "CREATE OR REPLACE TYPE rec_bnft_dtls AS (\n"
            + "  bnft_code character varying(10),\n"
            + "  clm_amt numeric(11,2),\n"
            + "  clm_approved_amt numeric(11,2),\n"
            + "  clm_start_dt character varying(30),\n"
            + "  clm_end_dt character varying(30),\n"
            + "  clm_days numeric(5,2),\n"
            + "  clm_crcy character varying(2),\n"
            + "  clm_pct character varying(10),\n"
            + "  clm_excess character varying(10),\n"
            + "  clm_exchg_rt numeric(18,11),\n"
            + "  clm_limit numeric(11,2),\n"
            + "  clm_ind character varying(1),\n"
            + "  clm_co_ins numeric(11,2),\n"
            + "  clm_deduct_amt_med_crcy numeric(11,2)\n"
            + ")");

    stmt.execute(
        "CREATE OR REPLACE TYPE tab_bnft_dtls AS TABLE OF rec_bnft_dtls");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE bnft_test_pkg AS\n"
            + "  PROCEDURE accept_bnft(\n"
            + "    p_items  IN  tab_bnft_dtls,\n"
            + "    p_count  OUT INTEGER,\n"
            + "    p_status OUT INTEGER\n"
            + "  );\n"
            + "END bnft_test_pkg;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY bnft_test_pkg AS\n"
            + "  PROCEDURE accept_bnft(\n"
            + "    p_items  IN  tab_bnft_dtls,\n"
            + "    p_count  OUT INTEGER,\n"
            + "    p_status OUT INTEGER\n"
            + "  ) IS\n"
            + "  BEGIN\n"
            + "    p_count  := p_items.COUNT;\n"
            + "    p_status := 0;\n"
            + "  END;\n"
            + "END bnft_test_pkg;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("DROP PACKAGE IF EXISTS bnft_test_pkg");
    stmt.execute("DROP TYPE IF EXISTS tab_bnft_dtls");
    stmt.execute("DROP TYPE IF EXISTS rec_bnft_dtls");
    stmt.close();
    conn.close();
  }

  /**
   * Build a single benefit details row matching the production data:
   * <pre>
   * "HS","5000.0","5100.0","2025-08-21 00:00:00.000","2025-08-22 00:00:00.000",
   * "30.0","04","80","0","641.30518431111","101.0","1","100.0","102.0"
   * </pre>
   */
  private Object[] buildBnftRow(String code, String amt, String approvedAmt,
                                  String startDt, String endDt, String days,
                                  String crcy, String pct, String excess,
                                  String exchgRt, String limit, String ind,
                                  String coIns, String deductAmt) {
    Object[] row = new Object[14];
    row[0] = code;
    row[1] = amt;
    row[2] = approvedAmt;
    row[3] = startDt;
    row[4] = endDt;
    row[5] = days;
    row[6] = crcy;
    row[7] = pct;
    row[8] = excess;
    row[9] = exchgRt;
    row[10] = limit;
    row[11] = ind;
    row[12] = coIns;
    row[13] = deductAmt;  // This is the field that gets "102.0}" when braces leak
    return row;
  }

  /**
   * Reproduce the exact production scenario:
   * List&lt;Object[]&gt;.toArray() produces Object[] (not Object[][]),
   * causing numeric field to receive "102.0}" instead of "102.0".
   *
   * <p>Before fix: ERROR: invalid input syntax for type numeric: "102.0}"
   *
   * <p>After fix: Call succeeds, p_count = 5 (matching 5 benefit rows)
   */
  @Test
  public void testListToArrayCompositeEncoding() throws SQLException {
    // Simulate genbnftDtlsArrList() building rows in an ArrayList
    List<Object[]> rowList = new ArrayList<>();

    rowList.add(buildBnftRow("HS", "5000.0", "5100.0",
        "2025-08-21 00:00:00.000", "2025-08-22 00:00:00.000",
        "30.0", "04", "80", "0", "641.30518431111", "101.0", "1", "100.0", "102.0"));

    rowList.add(buildBnftRow("RB", "2000.0", "1075.9308",
        null, null, "1.0", "04", null, null, "138.00000000000003", null, "0", "0.0", null));

    rowList.add(buildBnftRow("UF", "5000.0", "5000.0",
        null, null, null, "04", "12.5", null, "641.30518431111", null, "0", "0.0", null));

    rowList.add(buildBnftRow("OT", "5875.0", "2238.647504",
        null, null, null, "04", null, null, "287.1312500320653", null, "0", "0.0", null));

    rowList.add(buildBnftRow("MP", null, "4560.421696",
        null, null, null, "04", null, null, "584.924415257933", null, "0", null, null));

    // KEY: List.toArray() returns Object[], NOT Object[][]
    // This is the root cause — isObjectArray2D fails for Object[]
    Object[] data = rowList.toArray();

    // Verify that the Java type is Object[], not Object[][]
    assertEquals("toArray() should produce Object[], not Object[][]",
        Object[].class, data.getClass());

    // This is the exact call from ClaimCreationServiceImpl:
    //   Array sqlArray2 = oracleConn.createArrayOf("TAB_PRE_CLM_BNFT_DTLS", data2);
    Array array = conn.createArrayOf("tab_bnft_dtls", data);

    try (CallableStatement cs = conn.prepareCall(
        "begin bnft_test_pkg.accept_bnft(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 5 rows", 5, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  /**
   * Test single row with List.toArray() — even one row triggers the bug
   * because the closing brace leaks into the last numeric field.
   */
  @Test
  public void testSingleRowListToArray() throws SQLException {
    List<Object[]> rowList = new ArrayList<>();
    rowList.add(buildBnftRow("HS", "5000.0", "5100.0",
        "2025-08-21 00:00:00.000", "2025-08-22 00:00:00.000",
        "30.0", "04", "80", "0", "641.30518431111", "101.0", "1", "100.0", "102.0"));

    Object[] data = rowList.toArray();
    Array array = conn.createArrayOf("tab_bnft_dtls", data);

    try (CallableStatement cs = conn.prepareCall(
        "begin bnft_test_pkg.accept_bnft(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  /**
   * Verify that the original Object[][] path still works after the fix.
   * This ensures we haven't broken the existing isObjectArray2D logic.
   */
  @Test
  public void testObject2DArrayStillWorks() throws SQLException {
    Object[][] data = new Object[2][];
    data[0] = buildBnftRow("HS", "5000.0", "5100.0",
        "2025-08-21 00:00:00.000", "2025-08-22 00:00:00.000",
        "30.0", "04", "80", "0", "641.30518431111", "101.0", "1", "100.0", "102.0");
    data[1] = buildBnftRow("RB", "2000.0", "1075.9308",
        null, null, "1.0", "04", null, null, "138.0", null, "0", "0.0", null);

    Array array = conn.createArrayOf("tab_bnft_dtls", data);

    try (CallableStatement cs = conn.prepareCall(
        "begin bnft_test_pkg.accept_bnft(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 2 rows", 2, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  /**
   * Test with null rows in the list — some elements of the Object[] are null.
   */
  @Test
  public void testListToArrayWithNullRow() throws SQLException {
    List<Object[]> rowList = new ArrayList<>();
    rowList.add(buildBnftRow("HS", "5000.0", "5100.0",
        null, null, null, "04", null, null, null, null, "1", null, null));
    rowList.add(null);  // null row

    Object[] data = rowList.toArray();
    Array array = conn.createArrayOf("tab_bnft_dtls", data);

    try (CallableStatement cs = conn.prepareCall(
        "begin bnft_test_pkg.accept_bnft(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      // null row is still counted by .COUNT in PL/SQL TABLE OF
      assertEquals("Should count 2 rows (including null)", 2, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }
}
