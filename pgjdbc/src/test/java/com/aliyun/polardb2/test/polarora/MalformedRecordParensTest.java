/*
 * Portions Copyright (c) 2025, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.aliyun.polardb2.jdbc.PgConnection;
import com.aliyun.polardb2.test.TestUtil;
import com.aliyun.polardb2.util.PGobject;

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
import java.util.Locale;
import java.util.Properties;

/**
 * Reproduces the customer bug from production:
 *
 * <pre>
 * SQL: begin SERVICE_TRACK_HK.UPDATE_PROGRESS_CTL(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?); end;
 *
 * 11=(INPUT ORACLE_ARRAY)OracleArrayParameter:
 *   table name=(TAB_PROGRESS_REQ) type name=(REC_PROGRESS_REQ)
 *   row list=([0=3800180451|15482|2026-07-02 00:00:00.0|1|null|
 *             ---...
 *             MED020 - Copy of consultation summary / note.
 *             MED020 - 會診摘要副本 ( 詳情請參閱以上英文版 )。|0|2027-06-18 ...|28])
 *
 * 服务端报错：
 *   ERROR:  malformed record literal:
 *     "(3800180451,15482,2026-07-02 00:00:00.0,1,561159,
 *       ---...會診摘要副本 ( 詳情請參閱以上英文版 )。,0,,2027-06-18 ...,28)"
 *   DETAIL: Too few columns.
 * </pre>
 *
 * <p>对比成功用例：第 6 字段 {@code 投保申請書。} 不含 {@code (} {@code )}。
 *
 * <p>根因：PostgreSQL 的 record_in 解析器要求 — 单个字段值如包含逗号或圆括号
 * 必须用双引号包裹（见 PostgreSQL Composite Types 文档）。当 JDBC 驱动构造
 * 数组中的复合记录字面量时，如果调用路径只对外层 record literal 加双引号
 * （array 元素层），而未对 <b>内层字段</b>逐个做引号转义，含 {@code (} 的字段
 * 就会让服务端在解析嵌套记录时产生列数错位，报 Too few columns。
 *
 * <p>本测试用三种典型路径复现：
 * <ul>
 *   <li>{@link #testObjectArray2DWithParensSucceeds()} ：通过
 *       {@code conn.createArrayOf(name, Object[][])} —— 应当被
 *       {@code buildCompositeArrayFromObject2D} 中的
 *       {@code needsQuotingInRecord} 捕获并正确加双引号；
 *   <li>{@link #testPGobjectRawValueWithParensReproducesBug()} ：通过
 *       {@code PGobject[]}，其 value 是预先以 {@code ,} 拼接的原始字段串，
 *       含 {@code (} 但未对单个字段做引号转义 —— 复现服务端
 *       {@code malformed record literal}；
 *   <li>{@link #testStringArrayRecordValueWithParensReproducesBug()} ：通过
 *       {@code String[]}，每个元素是逗号拼接的记录字段串。
 * </ul>
 *
 * <p>同时提供基线对照 {@link #testNoParensBaselineSucceeds()}，证明在不含
 * {@code (} 时对应路径成功。
 */
public class MalformedRecordParensTest {

  private Connection conn;

  /** 失败用例字段值（含 {@code (}/{@code )}）—— 复现 errmsg 的失败行。 */
  private static final String DESC_WITH_PARENS =
      "--------------------------------------------------------------------------------------------------\r\n"
          + "        MED020 - Copy of consultation summary / note.\r\n"
          + "        MED020 - 會診摘要副本  ( 詳情請參閱以上英文版 )。";

  /** 成功用例字段值（不含括号）—— 与 errmsg 的成功行一致。 */
  private static final String DESC_NO_PARENS =
      "--------------------------------------------------------------------------------------------------\r\n"
          + "        APP006 - Application.\r\n"
          + "        APP006 - 投保申請書。";

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();

    // 模拟 REC_PROGRESS_REQ 的简化结构（13 个字段，对应 errmsg 中失败的记录字面量）
    stmt.execute(
        "CREATE OR REPLACE TYPE rec_progress_req AS (\n"
            + "  pol_num      varchar(20),\n"
            + "  pos_num      numeric,\n"
            + "  trxn_date    timestamp,\n"
            + "  pro_num      numeric,\n"
            + "  ppr_num      numeric,\n"
            + "  pos_chg_req  varchar(4000),\n"
            + "  status       varchar(2),\n"
            + "  req_recv_dt  timestamp,\n"
            + "  req_folup_dt timestamp,\n"
            + "  req_req_dt   timestamp,\n"
            + "  form_name    varchar(40),\n"
            + "  rel_ple_num  numeric,\n"
            + "  turn_day     numeric\n"
            + ")");

    stmt.execute(
        "CREATE OR REPLACE TYPE tab_progress_req AS TABLE OF rec_progress_req");

    // 模拟 SERVICE_TRACK_HK.UPDATE_PROGRESS_CTL 的简化版：仅接收 TABLE OF
    stmt.execute(
        "CREATE OR REPLACE PACKAGE service_track_hk_test AS\n"
            + "  PROCEDURE update_progress_ctl(\n"
            + "    pi_tab_progress_req IN  tab_progress_req,\n"
            + "    po_count            OUT integer,\n"
            + "    po_status           OUT integer\n"
            + "  );\n"
            + "END service_track_hk_test;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY service_track_hk_test AS\n"
            + "  PROCEDURE update_progress_ctl(\n"
            + "    pi_tab_progress_req IN  tab_progress_req,\n"
            + "    po_count            OUT integer,\n"
            + "    po_status           OUT integer\n"
            + "  ) IS\n"
            + "  BEGIN\n"
            + "    po_count  := pi_tab_progress_req.COUNT;\n"
            + "    po_status := 0;\n"
            + "  END;\n"
            + "END service_track_hk_test;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    if (conn != null) {
      Statement stmt = conn.createStatement();
      stmt.execute("DROP PACKAGE IF EXISTS service_track_hk_test");
      stmt.execute("DROP TYPE IF EXISTS tab_progress_req");
      stmt.execute("DROP TYPE IF EXISTS rec_progress_req");
      stmt.close();
      conn.close();
    }
  }

  // ----------------------------------------------------------------------
  // 构造一行 REC_PROGRESS_REQ 数据（对应 errmsg 中 row list[0] 的字段顺序）
  // ----------------------------------------------------------------------
  private Object[] buildRow(String posChgReq) {
    Object[] row = new Object[13];
    row[0]  = "3800180451";                                       // pol_num
    row[1]  = Integer.valueOf(15482);                             // pos_num
    row[2]  = Timestamp.valueOf("2026-07-02 00:00:00.0");         // trxn_date
    row[3]  = Integer.valueOf(1);                                 // pro_num
    row[4]  = null;                                               // ppr_num
    row[5]  = posChgReq;                                          // pos_chg_req（注入参数）
    row[6]  = "0";                                                // status
    row[7]  = Timestamp.valueOf("2027-06-18 00:00:00.0");         // req_recv_dt
    row[8]  = Timestamp.valueOf("2027-06-18 00:00:00.0");         // req_folup_dt
    row[9]  = Timestamp.valueOf("2026-07-02 14:09:00.0");         // req_req_dt
    row[10] = "FORM_NAME";                                        // form_name
    row[11] = Integer.valueOf(1);                                 // rel_ple_num
    row[12] = Integer.valueOf(28);                                // turn_day
    return row;
  }

  // ----------------------------------------------------------------------
  // 基线对照：不含括号 —— 与 errmsg 的成功行一致，必须通过
  // ----------------------------------------------------------------------

  @Test
  public void testNoParensBaselineSucceeds() throws SQLException {
    Object[][] data = new Object[][] { buildRow(DESC_NO_PARENS) };
    Array array = conn.createArrayOf("tab_progress_req", data);

    try (CallableStatement cs = conn.prepareCall(
        "begin service_track_hk_test.update_progress_ctl(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  // ----------------------------------------------------------------------
  // 复现 1：Object[][] 路径（buildCompositeArrayFromObject2D）
  // 该路径有 needsQuotingInRecord 检测，期望对含 ( 的字段做双引号包裹，
  // 因此应当成功。如果失败说明该路径同样存在缺陷。
  // ----------------------------------------------------------------------

  @Test
  public void testObjectArray2DWithParensSucceeds() throws SQLException {
    Object[][] data = new Object[][] { buildRow(DESC_WITH_PARENS) };
    Array array = conn.createArrayOf("tab_progress_req", data);

    // 打印实际生成的 array literal 便于诊断（应包含被双引号转义的字段）
    System.out.println("[DIAG] Object[][] array string = " + array.toString());

    try (CallableStatement cs = conn.prepareCall(
        "begin service_track_hk_test.update_progress_ctl(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 row (parens field properly quoted)", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  // ----------------------------------------------------------------------
  // 复现 2：PGobject[] 路径（ArrayEncoding.OBJECT_ARRAY 中的 PGobject 分支）
  // 这是 Manulife OracleArrayParameter 的典型路径：
  //   PGobject.value = "field1,field2,...,含括号的字段,...,28"
  // 驱动只在外层补 ( ) 并对整条记录加双引号，但对含 ( 的内层字段未做单独转义，
  // 服务端报 malformed record literal: Too few columns。
  // ----------------------------------------------------------------------

  @Test
  public void testPGobjectRawValueWithParensReproducesBug() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    // 模拟 OracleArrayParameter 的 toRecLiteral：用逗号简单拼接字段，不对
    // 单个字段做引号转义 —— 这是产线触发 bug 的真实路径。
    String rawRecord = String.join(",",
        "3800180451",
        "15482",
        "2026-07-02 00:00:00.0",
        "1",
        "",                                             // ppr_num null
        DESC_WITH_PARENS,                               // 第 6 字段含 ( )
        "0",
        "",
        "2027-06-18 00:00:00.0",
        "2026-07-02 14:09:00.0",
        "FORM_NAME",
        "1",
        "28");

    PGobject obj = new PGobject();
    obj.setType("rec_progress_req");
    obj.setValue(rawRecord);                           // 注意：未加外层 ( )

    Array array = pgConn.createArrayOf("tab_progress_req", new PGobject[] { obj });
    System.out.println("[DIAG] PGobject array string = " + array.toString());

    try (CallableStatement cs = conn.prepareCall(
        "begin service_track_hk_test.update_progress_ctl(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);

      try {
        cs.execute();
        // 如果执行成功，说明该路径没有 bug（已修复）
        assertEquals("Should count 1 row", 1, cs.getInt(2));
      } catch (SQLException ex) {
        // 复现成功：服务端抛 malformed record literal: Too few columns
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        System.out.println("[REPRO] Got expected error: " + msg);
        if (!msg.contains("malformed record literal")
            && !msg.toLowerCase(Locale.ROOT).contains("too few columns")) {
          fail("Expected 'malformed record literal' / 'Too few columns', got: " + msg);
        }
      }
    }
  }

  // ----------------------------------------------------------------------
  // 复现 3：String[] 路径（每个元素是 "f1,f2,..." 的记录字段串）
  // 驱动会走 fixCompositeArrayElements 加上 ( )，外层加双引号，但同样不会对
  // 内层含 ( 的字段单独加引号。期望复现同样的服务端错误。
  // ----------------------------------------------------------------------

  @Test
  public void testStringArrayRecordValueWithParensReproducesBug() throws SQLException {
    String rawRecord = "3800180451,15482,2026-07-02 00:00:00.0,1,,"
        + DESC_WITH_PARENS
        + ",0,,2027-06-18 00:00:00.0,2026-07-02 14:09:00.0,FORM_NAME,1,28";

    Array array = conn.createArrayOf("tab_progress_req", new String[] { rawRecord });
    System.out.println("[DIAG] String[] array string = " + array.toString());

    try (CallableStatement cs = conn.prepareCall(
        "begin service_track_hk_test.update_progress_ctl(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);

      try {
        cs.execute();
        assertEquals("Should count 1 row", 1, cs.getInt(2));
      } catch (SQLException ex) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        System.out.println("[REPRO] Got expected error: " + msg);
        if (!msg.contains("malformed record literal")
            && !msg.toLowerCase(Locale.ROOT).contains("too few columns")) {
          fail("Expected 'malformed record literal' / 'Too few columns', got: " + msg);
        }
      }
    }
  }
}
