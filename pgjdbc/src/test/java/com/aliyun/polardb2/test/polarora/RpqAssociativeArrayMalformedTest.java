/*
 * Portions Copyright (c) 2025, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertNotNull;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.Properties;

/**
 * 复现客户场景:
 *
 * <pre>
 * Environment: DEV (HKPED)
 * call GET_EXTERNAL.STORE_MC_RPQ_RESULT(?, ?, ?)
 *
 * ERROR: malformed associative array literal:
 *   "{"(2870926555,\"2026-05-12 00:00:00\",\"2026-10-02 00:00:00\",NB,NB,55,55,5,Y,RPQ100029)"}"
 * Where: unnamed portal parameter $1 = '...'
 * </pre>
 *
 * <p>第一个入参类型是包内 RECORD 数组:
 * <pre>
 *   TYPE rec_parm_fna_rpq_hist IS RECORD(
 *     pol_num                   ...,
 *     fna_rpq_eff_dt            DATE,
 *     trxn_dt                   DATE,
 *     created_by                VARCHAR2,
 *     last_update_by            VARCHAR2,
 *     rpq_score                 NUMBER,
 *     rpq_score_calc            NUMBER,
 *     rpq_risk_lvl              NUMBER,
 *     derivative_fund_knowledge VARCHAR2,
 *     ref_no                    VARCHAR2);
 *
 *   TYPE tbl_parm_fna_rpq_hist IS TABLE OF rec_parm_fna_rpq_hist
 *     INDEX BY INTEGER;   -- associative array (typcategory='L')
 * </pre>
 *
 * <p>问题根因: PolarDB 服务端不接受任何文本字面量(literal)形式的 associative array
 * (INDEX BY 类型, pg_type.typcategory='L'), 包括 JSON / hstore / "key=>value" /
 * 标准 PG 数组 {...} 等所有变体, 都会报 "malformed associative array literal"
 * 或 "type xxx is not an associative array type"。
 *
 * <p>当前驱动通过 {@link Connection#createArrayOf(String, Object[])} 把
 * Struct 元素拼成 {@code {"(v1,v2,...)"}} 这种标准 PG 数组字面量绑定为 IN 参数,
 * 服务器无法识别为 associative array, 因而抛错。
 */
public class RpqAssociativeArrayMalformedTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();

    // ---- Package GET_EXTERNAL: 定义 RECORD + TABLE OF ... INDEX BY INTEGER ----
    stmt.execute(
        "CREATE OR REPLACE PACKAGE get_external AS\n"
            + "  TYPE rec_parm_fna_rpq_hist IS RECORD (\n"
            + "    pol_num                   VARCHAR2(20),\n"
            + "    fna_rpq_eff_dt            DATE,\n"
            + "    trxn_dt                   DATE,\n"
            + "    created_by                VARCHAR2(20),\n"
            + "    last_update_by            VARCHAR2(20),\n"
            + "    rpq_score                 NUMBER,\n"
            + "    rpq_score_calc            NUMBER,\n"
            + "    rpq_risk_lvl              NUMBER,\n"
            + "    derivative_fund_knowledge VARCHAR2(1),\n"
            + "    ref_no                    VARCHAR2(20)\n"
            + "  );\n"
            + "  TYPE tbl_parm_fna_rpq_hist IS TABLE OF rec_parm_fna_rpq_hist\n"
            + "    INDEX BY BINARY_INTEGER;\n"
            + "\n"
            + "  TYPE rec_parm_ilas_quest IS RECORD (\n"
            + "    pol_num VARCHAR2(20),\n"
            + "    seq_no  NUMBER,\n"
            + "    answer  VARCHAR2(10)\n"
            + "  );\n"
            + "  TYPE tbl_parm_ilas_quest IS TABLE OF rec_parm_ilas_quest\n"
            + "    INDEX BY BINARY_INTEGER;\n"
            + "\n"
            + "  PROCEDURE store_mc_rpq_result(\n"
            + "    p_hist  IN  tbl_parm_fna_rpq_hist,\n"
            + "    p_quest IN  tbl_parm_ilas_quest,\n"
            + "    p_msg   OUT VARCHAR2\n"
            + "  );\n"
            + "END get_external;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY get_external AS\n"
            + "  PROCEDURE store_mc_rpq_result(\n"
            + "    p_hist  IN  tbl_parm_fna_rpq_hist,\n"
            + "    p_quest IN  tbl_parm_ilas_quest,\n"
            + "    p_msg   OUT VARCHAR2\n"
            + "  ) IS\n"
            + "  BEGIN\n"
            + "    p_msg := 'OK hist=' || p_hist.COUNT || ' quest=' || p_quest.COUNT;\n"
            + "  END;\n"
            + "END get_external;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    if (conn != null) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP PACKAGE IF EXISTS get_external");
      } catch (SQLException ignore) {
        // ignore tear-down failures
      }
      conn.close();
    }
  }

  /**
   * 对应 RpqQuestionnaireServiceImpl.wrapperIlasHist + storeRPQRecord 的最小复现。
   *
   * <p>预期: 当前驱动按标准 PG 数组字面量编码 ({"(...)"}) 绑定到
   * INDEX BY INTEGER 的 associative array IN 参数时, 服务器报错
   * <code>malformed associative array literal</code>。
   */
  @Test
  public void reproduceMalformedAssociativeArrayLiteral() throws SQLException {
    // === 1) 构造 wrapperIlasHist 中的一行记录, 字段顺序与生产代码一致 ===
    // 注意: 生产代码把日期手工拼成 '"2026-05-12 00:00:00"' 这种带双引号的字符串
    // (这是另一个隐患, 但为了真实复现错误现场, 保留同样写法)
    Object[] record = new Object[]{
        "2870926555",                                     // pol_num
        "\"2026-05-12 00:00:00\"",                       // fna_rpq_eff_dt
        "\"2026-10-02 00:00:00\"",                       // trxn_dt
        "NB",                                              // created_by
        "NB",                                              // last_update_by
        "55",                                              // rpq_score
        "55",                                              // rpq_score_calc
        "5",                                               // rpq_risk_lvl
        "Y",                                               // derivative_fund_knowledge
        "RPQ100029"                                        // ref_no
    };

    // === 2) PolarProcHelper.buildArrayElements: createStruct(REC_PARM_FNA_RPQ_HIST, ...) ===
    Struct histStruct = conn.createStruct("rec_parm_fna_rpq_hist", record);

    // === 3) PolarProcHelper.createArray: createArrayOf(TBL_PARM_FNA_RPQ_HIST, ...) ===
    Array histArray = conn.createArrayOf("tbl_parm_fna_rpq_hist",
        new Object[]{histStruct});
    System.out.println("[DIAGNOSTIC] hist array literal = " + histArray);

    // === 4) wrapperIlasQuest: 13 条 (pol_num, seq_no, answer) 记录 ===
    Object[][] questRecords = new Object[][]{
        {"2870926555", "201", "D"},
        {"2870926555", "202", "C"},
        {"2870926555", "203", "D"},
        {"2870926555", "204", "A"},
        {"2870926555", "204", "B"},
        {"2870926555", "205", "A"},
        {"2870926555", "205", "B"},
        {"2870926555", "206", "A"},
        {"2870926555", "207", "A"},
        {"2870926555", "208", "A"},
        {"2870926555", "209", "B"},
        {"2870926555", "210", "B"},
        {"2870926555", "335", "Y"},
    };
    Struct[] questStructs = new Struct[questRecords.length];
    for (int i = 0; i < questRecords.length; i++) {
      questStructs[i] = conn.createStruct("rec_parm_ilas_quest", questRecords[i]);
    }
    Array questArray = conn.createArrayOf("tbl_parm_ilas_quest", questStructs);
    System.out.println("[DIAGNOSTIC] quest array literal = " + questArray);

    // === 5) storeRPQRecord: { call GET_EXTERNAL.STORE_MC_RPQ_RESULT(?, ?, ?) } ===
    try (CallableStatement cs = conn.prepareCall(
        "{ call get_external.store_mc_rpq_result(?, ?, ?) }")) {
      cs.setArray(1, histArray);
      cs.setArray(2, questArray);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();
      String msg = cs.getString(3);
      System.out.println("[DIAGNOSTIC] OUT p_msg = " + msg);
      assertNotNull(msg);
    }
  }
}
