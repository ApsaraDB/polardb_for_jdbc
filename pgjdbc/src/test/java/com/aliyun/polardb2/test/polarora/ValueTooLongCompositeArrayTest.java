/*
 * Portions Copyright (c) 2025, Alibaba Group Holding Limited
 *
 * Reproduce bug: "value too long for type character varying(8 byte)"
 *
 * Root cause: OracleArrayParameter.getOracleArray(conn) builds Object[][] and
 * calls conn.createArrayOf(tableName, data). The ArrayEncoding sees Object[][]
 * and returns TwoDimensionPrimitiveArrayEncoder(OBJECT_ARRAY), which serialises
 * the data as a 2-D array literal  {{"HHF01790","HH",...}}  instead of the
 * correct 1-D composite-record array  {"(HHF01790,HH,...)"}.
 *
 * PostgreSQL/PolarDB then parses the inner '{' as part of the first field value,
 * producing "{HHF01790" (9 bytes) for clm_num character varying(8) → ERROR.
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
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Properties;

/**
 * Reproduces the production error:
 * <pre>
 * ERROR: value too long for type character varying(8 byte)
 *   Actual byte length: 9, string length: 9, string content: "{HHF01790".
 *   while processing column "clm_num"
 *   unnamed portal parameter $9 = '...'
 * </pre>
 *
 * <p>The 9th parameter in the original SQL is an OracleArrayParameter whose
 * getOracleArray(conn) method creates Object[][] and delegates to
 * conn.createArrayOf("TAB_CLM_DTLS_PAYOUT", data).
 */
public class ValueTooLongCompositeArrayTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();

    // ---- Create the exact composite type from production ----
    stmt.execute(
        "CREATE OR REPLACE TYPE rec_clm_dtls_payout AS (\n"
            + "  clm_num character varying(8),\n"
            + "  clm_typ character varying(2),\n"
            + "  clm_beneficary_nm character varying(101),\n"
            + "  clm_beneficary_rel character varying(3),\n"
            + "  clm_split_percent numeric(5,2),\n"
            + "  clm_amt_crcy character varying(2),\n"
            + "  clm_amt numeric(11,2),\n"
            + "  clm_lst_clm_amt numeric(11,2),\n"
            + "  clm_bal_amt numeric(11,2),\n"
            + "  clm_bnft_amt numeric(11,2),\n"
            + "  clm_pol_crcy character varying(2),\n"
            + "  clm_bnft_amt_pol_crcy numeric(11,2),\n"
            + "  clm_bnft_payo_crcy character varying(2),\n"
            + "  clm_bnft_amt_payo_crcy numeric(11,2),\n"
            + "  clm_exchg_rt_pol numeric(18,8),\n"
            + "  clm_exchg_rt_payo numeric(18,8),\n"
            + "  clm_bnft_arrange character varying(10),\n"
            + "  clm_exchg_rt_med numeric(18,8),\n"
            + "  clm_med_adj_amt numeric(11,2),\n"
            + "  clm_loan_amt numeric(11,2),\n"
            + "  clm_db_amt numeric(11,2),\n"
            + "  clm_med_reserve_amt numeric(11,2),\n"
            + "  clm_bal_amt_pol_crcy numeric(11,2),\n"
            + "  clm_bal_amt_payo_crcy numeric(11,2),\n"
            + "  pol_num character varying(10),\n"
            + "  clm_bnft_channel character varying(30),\n"
            + "  clm_approve_amt numeric(11,2),\n"
            + "  clm_external_crcy character varying(2),\n"
            + "  clm_external_amt numeric(11,2),\n"
            + "  clm_external_amt_pol_crcy numeric(11,2),\n"
            + "  clm_exchg_rt_ext_amt numeric(18,8),\n"
            + "  clm_relclm_amt_pol_crcy numeric(11,2),\n"
            + "  clm_ddb_amt_pol_crcy numeric(11,2),\n"
            + "  clm_lst_clm_amt_pol_crcy numeric(11,2),\n"
            + "  clm_adj_rb_amt_pol_crcy numeric(11,2),\n"
            + "  clm_adj_limit_amt_pol_crcy numeric(11,2),\n"
            + "  clm_utl_ddb_amt_pol_crcy numeric(11,2),\n"
            + "  clm_xtra_med_reserve_amt numeric(11,2),\n"
            + "  clm_ddb_credit_amt_pol_crcy numeric(11,2),\n"
            + "  clm_health_bonus numeric(11,2),\n"
            + "  clm_divid_adj_pol_crcy numeric(11,2),\n"
            + "  clm_tpa_external_crcy character varying(2),\n"
            + "  clm_tpa_external_amt numeric(11,2),\n"
            + "  clm_tpa_external_amt_pol_crcy numeric(11,2),\n"
            + "  clm_tpa_exchg_rate_ext_amt numeric(18,8),\n"
            + "  clm_tpa_lst_clm_amt_pol_crcy numeric(11,2),\n"
            + "  clm_tpa_bal_amt_pol_crcy numeric(11,2),\n"
            + "  clm_tpa_bnft_payo_crcy character varying(2),\n"
            + "  clm_tpa_exchg_rt_payo numeric(18,8),\n"
            + "  clm_tpa_bal_amt_payo_crcy numeric(11,2),\n"
            + "  clm_sf_amt_pol_crcy numeric(11,2),\n"
            + "  clm_adj_sf_amt_pol_crcy numeric(11,2),\n"
            + "  clm_sfl_repaid_amt_pol_crcy numeric(11,2),\n"
            + "  clm_adj_sf_repaid_amt_pol_crcy numeric(11,2),\n"
            + "  clm_lst_sfl_rep_amt_pol_crcy numeric(11,2),\n"
            + "  clm_sfl_bal_amt_pol_crcy numeric(11,2),\n"
            + "  clm_co_insurance_amt_pol_crcy numeric(11,2),\n"
            + "  clm_ori_pre_approved_crcy character varying(2),\n"
            + "  clm_ori_pre_approved_amt numeric(11,2),\n"
            + "  clm_external_com_crcy character varying(2),\n"
            + "  clm_external_com_amt numeric(11,2),\n"
            + "  clm_external_com_amt_pol_crcy numeric(11,2),\n"
            + "  clm_exchg_com_rt_ext_amt numeric(18,8),\n"
            + "  clm_external_glh_crcy character varying(2),\n"
            + "  clm_external_glh_amt numeric(11,2),\n"
            + "  clm_external_glh_amt_pol_crcy numeric(11,2),\n"
            + "  clm_exchg_glh_rt_ext_amt numeric(18,8),\n"
            + "  clm_reimb_disc_rt_pol_amt numeric(11,2),\n"
            + "  clm_last_health_bonus_pol_crcy numeric(11,2),\n"
            + "  clm_levy_amt numeric(11,2),\n"
            + "  clm_mmb_pct character varying(3),\n"
            + "  clm_calc_vers character varying(10),\n"
            + "  clm_rm_adj_fct numeric(11,2),\n"
            + "  lst_upd_by character varying(40),\n"
            + "  lst_upd_dtime date,\n"
            + "  clm_instal_amt_pol_crcy numeric(11,2),\n"
            + "  clm_instal_percent numeric(5,2),\n"
            + "  clm_excluded_amt numeric(11,2),\n"
            + "  clm_ci_bnft_adj_amt_pol_crcy numeric(11,2),\n"
            + "  clm_db_os_loan_reg_income numeric(11,2),\n"
            + "  clm_ci_exchg_rt_med numeric(18,8),\n"
            + "  clm_face_amt_after_tiab numeric(11,2),\n"
            + "  clm_excs_terr_limit numeric(11,2)\n"
            + ")");

    // TABLE OF type
    stmt.execute(
        "CREATE OR REPLACE TYPE tab_clm_dtls_payout AS TABLE OF rec_clm_dtls_payout");

    // Simple procedure that accepts the TABLE OF parameter
    stmt.execute(
        "CREATE OR REPLACE PACKAGE payout_test_pkg AS\n"
            + "  PROCEDURE accept_payout(\n"
            + "    p_items  IN  tab_clm_dtls_payout,\n"
            + "    p_count  OUT INTEGER,\n"
            + "    p_status OUT INTEGER\n"
            + "  );\n"
            + "END payout_test_pkg;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY payout_test_pkg AS\n"
            + "  PROCEDURE accept_payout(\n"
            + "    p_items  IN  tab_clm_dtls_payout,\n"
            + "    p_count  OUT INTEGER,\n"
            + "    p_status OUT INTEGER\n"
            + "  ) IS\n"
            + "  BEGIN\n"
            + "    p_count  := p_items.COUNT;\n"
            + "    p_status := 0;\n"
            + "  END;\n"
            + "END payout_test_pkg;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("DROP PACKAGE IF EXISTS payout_test_pkg");
    stmt.execute("DROP TYPE IF EXISTS tab_clm_dtls_payout");
    stmt.execute("DROP TYPE IF EXISTS rec_clm_dtls_payout");
    stmt.close();
    conn.close();
  }

  /**
   * Build the exact Object[] that the production code (genPayoutDtlsObject +
   * OracleArrayParameter.addObjectArray) constructs for the 9th parameter.
   *
   * <p>Data taken from the production debug session:
   * <pre>
   * rowList[0] = {Object[83]}
   *   0 = "HHF01790"         (String)
   *   1 = "HH"               (String)
   *   2 = "IS RATE TEST PU TWENTY" (String)
   *   3 = "BOW"              (String)
   *   4 = {Float}  100.0
   *   5 = "04"               (String)
   *   6 = {Double} 5000.0
   *   7 = {Double} 0.0
   *   ...
   * </pre>
   *
   * <p>The types (String/Float/Double/null/Timestamp) match the debug screenshot
   * exactly — this is essential because ArrayEncoding.OBJECT_ARRAY.appendArray
   * dispatches on element runtime type.
   */
  private Object[] buildPayoutRow() {
    Object[] row = new Object[83];

    // clm_num varchar(8)            — exactly 8 chars, triggers the bug
    row[0] = "HHF01790";
    // clm_typ varchar(2)
    row[1] = "HH";
    // clm_beneficary_nm varchar(101)
    row[2] = "IS RATE TEST PU TWENTY";
    // clm_beneficary_rel varchar(3)
    row[3] = "BOW";
    // clm_split_percent numeric(5,2) — Float in production (Float@22103)
    row[4] = Float.valueOf(100.0f);
    // clm_amt_crcy varchar(2)
    row[5] = "04";
    // clm_amt numeric(11,2) — Double in production
    row[6] = Double.valueOf(5000.0);
    // clm_lst_clm_amt
    row[7] = Double.valueOf(0.0);
    // clm_bal_amt
    row[8] = Double.valueOf(5000.0);
    // clm_bnft_amt
    row[9] = Double.valueOf(5000.0);
    // clm_pol_crcy varchar(2)
    row[10] = "04";
    // clm_bnft_amt_pol_crcy
    row[11] = Double.valueOf(5000.0);
    // clm_bnft_payo_crcy varchar(2)
    row[12] = "04";
    // clm_bnft_amt_payo_crcy
    row[13] = Double.valueOf(5000.0);
    // clm_exchg_rt_pol
    row[14] = Double.valueOf(1.0);
    // clm_exchg_rt_payo
    row[15] = Double.valueOf(1.0);
    // clm_bnft_arrange varchar(10)
    row[16] = "CQ";
    // clm_exchg_rt_med
    row[17] = Double.valueOf(1.0);
    // clm_med_adj_amt
    row[18] = Double.valueOf(0.0);
    // clm_loan_amt
    row[19] = null;
    // clm_db_amt
    row[20] = null;
    // clm_med_reserve_amt
    row[21] = null;
    // clm_bal_amt_pol_crcy
    row[22] = Double.valueOf(0.0);
    // clm_bal_amt_payo_crcy
    row[23] = Double.valueOf(0.0);
    // pol_num varchar(10)
    row[24] = "2874914870";
    // clm_bnft_channel varchar(30)
    row[25] = "T";
    // clm_approve_amt
    row[26] = null;
    // clm_external_crcy varchar(2) — empty string in production
    row[27] = "";
    // clm_external_amt
    row[28] = Double.valueOf(0.0);
    // clm_external_amt_pol_crcy
    row[29] = Double.valueOf(0.0);
    // clm_exchg_rt_ext_amt
    row[30] = Double.valueOf(0.0);
    // clm_relclm_amt_pol_crcy
    row[31] = Double.valueOf(0.0);
    // clm_ddb_amt_pol_crcy
    row[32] = Double.valueOf(5000.0);
    // clm_lst_clm_amt_pol_crcy
    row[33] = Double.valueOf(0.0);
    // clm_adj_rb_amt_pol_crcy
    row[34] = Double.valueOf(0.0);
    // clm_adj_limit_amt_pol_crcy
    row[35] = Double.valueOf(0.0);
    // clm_utl_ddb_amt_pol_crcy
    row[36] = Double.valueOf(5000.0);
    // clm_xtra_med_reserve_amt
    row[37] = null;
    // clm_ddb_credit_amt_pol_crcy
    row[38] = Double.valueOf(0.0);
    // clm_health_bonus
    row[39] = Double.valueOf(0.0);
    // clm_divid_adj_pol_crcy
    row[40] = Double.valueOf(0.0);
    // clm_tpa_external_crcy varchar(2)
    row[41] = "04";
    // clm_tpa_external_amt
    row[42] = Double.valueOf(6000.0);
    // clm_tpa_external_amt_pol_crcy
    row[43] = Double.valueOf(6000.0);
    // clm_tpa_exchg_rate_ext_amt
    row[44] = Double.valueOf(1.0);
    // clm_tpa_lst_clm_amt_pol_crcy
    row[45] = Double.valueOf(0.0);
    // clm_tpa_bal_amt_pol_crcy
    row[46] = Double.valueOf(6000.0);
    // clm_tpa_bnft_payo_crcy varchar(2)
    row[47] = "04";
    // clm_tpa_exchg_rt_payo
    row[48] = Double.valueOf(1.0);
    // clm_tpa_bal_amt_payo_crcy
    row[49] = Double.valueOf(6000.0);
    // clm_sf_amt_pol_crcy
    row[50] = Double.valueOf(6000.0);
    // clm_adj_sf_amt_pol_crcy
    row[51] = Double.valueOf(6000.0);
    // clm_sfl_repaid_amt_pol_crcy
    row[52] = Double.valueOf(0.0);
    // clm_adj_sf_repaid_amt_pol_crcy
    row[53] = Double.valueOf(0.0);
    // clm_lst_sfl_rep_amt_pol_crcy
    row[54] = Double.valueOf(0.0);
    // clm_sfl_bal_amt_pol_crcy
    row[55] = Double.valueOf(0.0);
    // clm_co_insurance_amt_pol_crcy
    row[56] = Double.valueOf(0.0);
    // clm_ori_pre_approved_crcy varchar(2) — empty string
    row[57] = "";
    // clm_ori_pre_approved_amt
    row[58] = null;
    // clm_external_com_crcy varchar(2) — empty string
    row[59] = "";
    // clm_external_com_amt
    row[60] = Double.valueOf(0.0);
    // clm_external_com_amt_pol_crcy
    row[61] = Double.valueOf(0.0);
    // clm_exchg_com_rt_ext_amt
    row[62] = Double.valueOf(0.0);
    // clm_external_glh_crcy varchar(2) — empty string
    row[63] = "";
    // clm_external_glh_amt
    row[64] = Double.valueOf(0.0);
    // clm_external_glh_amt_pol_crcy
    row[65] = Double.valueOf(0.0);
    // clm_exchg_glh_rt_ext_amt
    row[66] = Double.valueOf(0.0);
    // clm_reimb_disc_rt_pol_amt
    row[67] = Double.valueOf(0.0);
    // clm_last_health_bonus_pol_crcy
    row[68] = Double.valueOf(0.0);
    // clm_levy_amt
    row[69] = null;
    // clm_mmb_pct varchar(3)
    row[70] = null;
    // clm_calc_vers varchar(10)
    row[71] = null;
    // clm_rm_adj_fct
    row[72] = null;
    // lst_upd_by varchar(40)
    row[73] = "WINNIE_TANG";
    // lst_upd_dtime date — Timestamp in production
    row[74] = Timestamp.valueOf("2024-12-17 14:45:28.0");
    // clm_instal_amt_pol_crcy
    row[75] = null;
    // clm_instal_percent
    row[76] = null;
    // clm_excluded_amt
    row[77] = Double.valueOf(0.0);
    // clm_ci_bnft_adj_amt_pol_crcy
    row[78] = null;
    // clm_db_os_loan_reg_income
    row[79] = Double.valueOf(0.0);
    // clm_ci_exchg_rt_med
    row[80] = null;
    // clm_face_amt_after_tiab
    row[81] = null;
    // clm_excs_terr_limit
    row[82] = null;

    return row;
  }

  /**
   * Reproduce the exact production error by simulating the OracleArrayParameter
   * code path:
   *
   * <pre>
   * // ClaimsManagerDAO_HK.java line 7549-7566:
   * OracleArrayParameter oPayoutDtlsArray =
   *     new OracleArrayParameter("TAB_CLM_DTLS_PAYOUT", "REC_CLM_DTLS_PAYOUT");
   * Object[] clmPayoutDtlsObject = genPayoutDtlsObject(...);
   * oPayoutDtlsArray.addObjectArray(clmPayoutDtlsObject);
   * ...
   * // Later, OracleArrayParameter.getOracleArray(conn) does:
   * Object[][] data = new Object[rowList.size()][];
   * data[0] = (Object[]) rowList.get(0);
   * return conn.createArrayOf(arrayTableName, data);  // &lt;-- BUG HERE
   * </pre>
   *
   * <p>Before fix: ArrayEncoding treats Object[][] as a 2-D array, producing
   * {{"HHF01790","HH",...}} instead of {"(HHF01790,HH,...)"}.
   * PostgreSQL sees "{HHF01790" (9 bytes) for clm_num varchar(8) → error.
   *
   * <p>After fix: PgConnection.createArrayOf detects Object[][] with STRUCT
   * element type and converts each inner Object[] to a properly formatted
   * record literal (val1,val2,...). The call should succeed.
   */
  @Test
  public void testValueTooLongForVarchar8() throws SQLException {
    Object[] row = buildPayoutRow();

    // ---- Simulate OracleArrayParameter.getOracleArray(conn) ----
    // OracleArrayParameter stores rows in ArrayList, then builds Object[][]
    Object[][] data = new Object[1][];
    data[0] = row;

    // This is the exact call OracleArrayParameter.getOracleArray makes:
    //   return conn.createArrayOf(arrayTableName, data);
    // where arrayTableName = "TAB_CLM_DTLS_PAYOUT"
    Array array = conn.createArrayOf("tab_clm_dtls_payout", data);

    // Now use it in a CallableStatement, matching the production SQL pattern:
    //   begin CLAIM_BODY.UPDATE_IND_CLAIM_REC(?, ?, ..., ?, ...); end;
    // We use a simplified procedure that just accepts the array parameter.
    try (CallableStatement cs = conn.prepareCall(
        "begin payout_test_pkg.accept_payout(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      // After fix: the call should succeed
      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  // =====================================================================
  // Tests for null value handling in Object[][] composite arrays
  // =====================================================================

  /**
   * Test that null field values within a row are handled correctly.
   *
   * <p>In PostgreSQL composite literals, null values are represented by empty
   * content between commas: (val1,,val3) means the second field is NULL.
   */
  @Test
  public void testNullFieldValuesInRow() throws SQLException {
    // Build a row with several null field values
    Object[] row = new Object[83];
    row[0] = "CLM001";      // clm_num - not null
    row[1] = "HH";          // clm_typ - not null
    row[2] = null;          // clm_beneficary_nm - NULL
    row[3] = null;          // clm_beneficary_rel - NULL
    row[4] = Float.valueOf(100.0f);  // clm_split_percent
    row[5] = null;          // clm_amt_crcy - NULL
    row[6] = Double.valueOf(5000.0); // clm_amt
    // Rest of fields are null
    for (int i = 7; i < 83; i++) {
      row[i] = null;
    }

    Object[][] data = new Object[1][];
    data[0] = row;

    Array array = conn.createArrayOf("tab_clm_dtls_payout", data);

    try (CallableStatement cs = conn.prepareCall(
        "begin payout_test_pkg.accept_payout(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  /**
   * Test that a completely null row (inner Object[] is null) is handled.
   *
   * <p>A null row should be encoded as NULL in the array literal.
   */
  @Test
  public void testNullRowInArray() throws SQLException {
    Object[] row1 = new Object[83];
    row1[0] = "CLM001";
    row1[1] = "HH";
    for (int i = 2; i < 83; i++) {
      row1[i] = null;
    }

    // Second row is completely null
    Object[][] data = new Object[2][];
    data[0] = row1;
    data[1] = null;  // Entire row is NULL

    Array array = conn.createArrayOf("tab_clm_dtls_payout", data);

    try (CallableStatement cs = conn.prepareCall(
        "begin payout_test_pkg.accept_payout(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      // Array contains 2 elements: one valid row and one NULL
      assertEquals("Should count 2 elements (including NULL)", 2, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  /**
   * Test that an empty array (zero rows) is handled correctly.
   */
  @Test
  public void testEmptyArray() throws SQLException {
    Object[][] data = new Object[0][];

    Array array = conn.createArrayOf("tab_clm_dtls_payout", data);

    try (CallableStatement cs = conn.prepareCall(
        "begin payout_test_pkg.accept_payout(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 0 rows", 0, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  /**
   * Test multiple rows with various null patterns.
   */
  @Test
  public void testMultipleRowsWithMixedNulls() throws SQLException {
    // Row 1: all fields populated
    Object[] row1 = new Object[83];
    row1[0] = "CLM001";
    row1[1] = "HH";
    row1[2] = "John Doe";
    row1[3] = "BOW";
    row1[4] = Float.valueOf(50.0f);
    row1[5] = "04";
    row1[6] = Double.valueOf(1000.0);
    for (int i = 7; i < 83; i++) {
      row1[i] = null;
    }

    // Row 2: first field null (edge case)
    Object[] row2 = new Object[83];
    row2[0] = null;         // clm_num is NULL
    row2[1] = "HK";
    row2[2] = "Jane Doe";
    for (int i = 3; i < 83; i++) {
      row2[i] = null;
    }

    // Row 3: all fields null
    Object[] row3 = new Object[83];
    for (int i = 0; i < 83; i++) {
      row3[i] = null;
    }

    Object[][] data = new Object[3][];
    data[0] = row1;
    data[1] = row2;
    data[2] = row3;

    Array array = conn.createArrayOf("tab_clm_dtls_payout", data);

    try (CallableStatement cs = conn.prepareCall(
        "begin payout_test_pkg.accept_payout(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 3 rows", 3, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  /**
   * Test that empty strings are preserved (not treated as null).
   */
  @Test
  public void testEmptyStringFieldValues() throws SQLException {
    Object[] row = new Object[83];
    row[0] = "CLM001";      // clm_num - normal value
    row[1] = "";            // clm_typ - empty string (should NOT be null)
    row[2] = "";            // clm_beneficary_nm - empty string
    row[3] = "BOW";         // normal value
    row[4] = Float.valueOf(100.0f);
    row[5] = "";            // clm_amt_crcy - empty string
    row[6] = Double.valueOf(5000.0);
    for (int i = 7; i < 83; i++) {
      row[i] = null;
    }

    Object[][] data = new Object[1][];
    data[0] = row;

    Array array = conn.createArrayOf("tab_clm_dtls_payout", data);

    try (CallableStatement cs = conn.prepareCall(
        "begin payout_test_pkg.accept_payout(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  /**
   * Test special characters in field values that require escaping.
   */
  @Test
  public void testSpecialCharactersInFieldValues() throws SQLException {
    Object[] row = new Object[83];
    row[0] = "CLM001";
    row[1] = "HH";
    row[2] = "Name with, comma";        // contains comma
    row[3] = "A\"B";                     // contains quote
    row[4] = Float.valueOf(100.0f);
    row[5] = "04";
    row[6] = Double.valueOf(5000.0);
    for (int i = 7; i < 73; i++) {
      row[i] = null;
    }
    row[73] = "User (test)";             // contains parentheses
    row[74] = null;                      // lst_upd_dtime
    for (int i = 75; i < 83; i++) {
      row[i] = null;
    }

    Object[][] data = new Object[1][];
    data[0] = row;

    Array array = conn.createArrayOf("tab_clm_dtls_payout", data);

    try (CallableStatement cs = conn.prepareCall(
        "begin payout_test_pkg.accept_payout(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }
}
