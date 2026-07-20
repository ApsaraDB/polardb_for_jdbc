/*
 * Portions Copyright (c) 2026, Alibaba Group Holding Limited
 *
 * Reproduces the customer (CAS / ClaimsManagerDAO_HK) issue where a TABLE OF
 * OBJECT TYPE OUT parameter returns each composite field as java.lang.String,
 * so casting a NUMBER field to BigDecimal fails with:
 *   Cannot cast 'java.lang.String' to 'java.math.BigDecimal'
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.Properties;

/**
 * Reproduces the field-type mismatch when reading a TABLE OF composite OUT
 * parameter. The DB type REC_CLM_BNFT_SUR_DTLS has NUMBER fields (e.g.
 * SURG_GRP_NO at index 5, CLM_SUR_AMT at index 10). The customer code does:
 * <pre>
 *   Struct st = (Struct) clmBnftSurgItemArray[i];
 *   Object[] attributes = st.getAttributes();
 *   ((BigDecimal) attributes[5]).intValue();   // SURG_GRP_NO NUMBER(3)
 * </pre>
 * On Oracle ojdbc, attributes[5] is a BigDecimal. On PolarDB it comes back as
 * String, so the cast throws ClassCastException.
 */
public class TableOfCompositeFieldTypeTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    try (Statement stmt = conn.createStatement()) {
      dropAll(stmt);

      // Faithful reproduction of CAS."REC_CLM_BNFT_SUR_DTLS" (21 fields).
      stmt.execute("CREATE OR REPLACE TYPE rec_clm_bnft_sur_dtls AS OBJECT (\n"
          + "  clm_num               VARCHAR2(8),\n"
          + "  clm_typ               VARCHAR2(2),\n"
          + "  clm_cat               VARCHAR2(10),\n"
          + "  clm_vers              VARCHAR2(2),\n"
          + "  pol_num               VARCHAR2(10),\n"
          + "  surg_grp_no           NUMBER(3),\n"
          + "  clm_sur_item          VARCHAR2(10),\n"
          + "  clm_sur_code          VARCHAR2(10),\n"
          + "  clm_sur_desc          VARCHAR2(50),\n"
          + "  clm_sur_cat           VARCHAR2(1),\n"
          + "  clm_sur_amt           NUMBER(11,2),\n"
          + "  clm_sur_bnft_amt      NUMBER(11,2),\n"
          + "  clm_sur_bnft_ext_amt  NUMBER(11,2),\n"
          + "  clm_sur_bnft_rel_amt  NUMBER(11,2),\n"
          + "  clm_sur_amt_crcy      VARCHAR2(2),\n"
          + "  clm_sur_exchg_rt      NUMBER(18,8),\n"
          + "  clm_sur_pol_crcy      VARCHAR2(2),\n"
          + "  lst_upd_by            VARCHAR2(40),\n"
          + "  lst_upd_dtime         DATE,\n"
          + "  clm_sur_excluded_amt  NUMBER(11,2)\n"
          + ")");

      stmt.execute("CREATE OR REPLACE TYPE tab_clm_bnft_sur_dtls "
          + "AS TABLE OF rec_clm_bnft_sur_dtls");

      // Procedure returns a one-row collection via an OUT parameter.
      stmt.execute("CREATE OR REPLACE PROCEDURE get_bnft_sur_dtls("
          + "  p_out OUT tab_clm_bnft_sur_dtls) IS\n"
          + "BEGIN\n"
          + "  p_out := tab_clm_bnft_sur_dtls();\n"
          + "  p_out.extend;\n"
          + "  p_out(1) := rec_clm_bnft_sur_dtls(\n"
          + "    'CLM00001','01','CAT','01','POL0000001',\n"
          + "    3,'ITEM','CODE','DESC','C',\n"
          + "    100.50, 90.00, 10.00, 5.00, 'HK',\n"
          + "    7.85000000, 'HK', 'user1', SYSDATE, 1.00);\n"
          + "END;");
    }
  }

  @After
  public void tearDown() throws Exception {
    if (conn != null) {
      try (Statement stmt = conn.createStatement()) {
        dropAll(stmt);
      } catch (SQLException ignore) {
        // ignore
      }
      conn.close();
    }
  }

  private void dropAll(Statement stmt) {
    try {
      stmt.execute("DROP PROCEDURE IF EXISTS get_bnft_sur_dtls");
    } catch (SQLException ignore) {
      // ignore
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS tab_clm_bnft_sur_dtls");
    } catch (SQLException ignore) {
      // ignore
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS rec_clm_bnft_sur_dtls");
    } catch (SQLException ignore) {
      // ignore
    }
  }

  /**
   * After the fix, composite fields are restored to their proper Java types
   * (NUMBER -> BigDecimal, DATE -> Timestamp, VARCHAR -> String), so the
   * customer's access pattern ((BigDecimal) attributes[i]) works without
   * ClassCastException.
   */
  @Test
  public void testNumberFieldReturnedAsBigDecimal() throws SQLException {
    try (CallableStatement cs = conn.prepareCall("{ call get_bnft_sur_dtls(?) }")) {
      cs.registerOutParameter(1, Types.ARRAY, "tab_clm_bnft_sur_dtls");
      cs.execute();

      Array array = cs.getArray(1);
      assertNotNull("OUT array should not be null", array);

      Object[] rows = (Object[]) array.getArray();
      assertTrue("Expected at least one row", rows.length >= 1);

      Struct st = (Struct) rows[0];
      Object[] attributes = st.getAttributes();

      for (int i = 0; i < attributes.length; i++) {
        Object a = attributes[i];
        System.out.println("[diag] attributes[" + i + "] = " + a
            + " (" + (a == null ? "null" : a.getClass().getName()) + ")");
      }

      // VARCHAR fields remain String
      assertTrue("CLM_NUM should be String", attributes[0] instanceof String);
      assertEquals("CLM00001", attributes[0]);
      assertTrue("CLM_SUR_AMT_CRCY should be String", attributes[14] instanceof String);

      // NUMBER fields become BigDecimal (customer's exact cast)
      assertTrue("SURG_GRP_NO should be BigDecimal, got "
              + attributes[5].getClass().getName(),
          attributes[5] instanceof BigDecimal);
      assertEquals(3, ((BigDecimal) attributes[5]).intValue());

      assertTrue("CLM_SUR_AMT should be BigDecimal", attributes[10] instanceof BigDecimal);
      assertEquals(0, new BigDecimal("100.50").compareTo((BigDecimal) attributes[10]));

      assertTrue("CLM_SUR_EXCHG_RT should be BigDecimal", attributes[15] instanceof BigDecimal);
      assertEquals(0, new BigDecimal("7.85").compareTo((BigDecimal) attributes[15]));

      // DATE field becomes Timestamp (Oracle DATE carries time; matches (Timestamp) cast)
      assertTrue("LST_UPD_DTIME should be Timestamp, got "
              + (attributes[18] == null ? "null" : attributes[18].getClass().getName()),
          attributes[18] instanceof java.sql.Timestamp);
    }
  }

  /**
   * Full replay of the customer's getBnftSurDtlBaseResultList access pattern to
   * ensure no ClassCastException occurs across all fields.
   */
  @Test
  public void testCustomerAccessPatternNoCastError() throws SQLException {
    try (CallableStatement cs = conn.prepareCall("{ call get_bnft_sur_dtls(?) }")) {
      cs.registerOutParameter(1, Types.ARRAY, "tab_clm_bnft_sur_dtls");
      cs.execute();

      Object[] rows = (Object[]) cs.getArray(1).getArray();
      for (Object rowObj : rows) {
        Struct st = (Struct) rowObj;
        Object[] a = st.getAttributes();

        // Mirror ClaimsManagerDAO_HK.getBnftSurDtlBaseResultList exactly
        String clmNum = a[0] != null ? (String) a[0] : null;
        String surgGrpNo = a[5] != null ? Integer.toString(((BigDecimal) a[5]).intValue()) : null;
        Double clmSurgAmt = a[10] != null ? ((BigDecimal) a[10]).doubleValue() : null;
        Double clmSurgBnftAmt = a[11] != null ? ((BigDecimal) a[11]).doubleValue() : null;
        Double clmSurgExchgRt = a[15] != null ? ((BigDecimal) a[15]).doubleValue() : null;
        String amtCrcy = a[14] != null ? (String) a[14] : null;

        assertEquals("CLM00001", clmNum);
        assertEquals("3", surgGrpNo);
        assertEquals(Double.valueOf(100.50), clmSurgAmt);
        assertEquals(Double.valueOf(90.00), clmSurgBnftAmt);
        assertEquals(Double.valueOf(7.85), clmSurgExchgRt);
        assertEquals("HK", amtCrcy);
      }
    }
  }
}
