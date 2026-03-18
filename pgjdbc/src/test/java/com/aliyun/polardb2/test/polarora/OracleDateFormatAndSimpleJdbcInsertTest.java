/*
 * 复现两个问题:
 * 图1: SimpleJdbcInsert 调用场景，列名包含关键字或日期字段值为 Oracle NLS 格式
 * 图2: 日期字段值为 Oracle NLS DD-Mon-YYYY 格式 (如 "06-Mar-2026") 被 JDBC 驱动解析时报错
 *      Bad value for type timestamp/date/time: 06-Mar-2026
 *
 * 根本原因: TimestampUtils.parseBackendTimestamp 只能解析 yyyy-mm-dd 格式，
 *           不支持 Oracle NLS 格式 DD-Mon-YYYY。
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
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 复现并验证 Oracle NLS 日期格式 DD-Mon-YYYY 的 JDBC 支持，
 * 以及 SimpleJdbcInsert 调用含日期字段的批量插入场景。
 *
 * <p>对应错误:
 * com.aliyun.polardb2.util.PSQLException:
 *   Bad value for type timestamp/date/time: 06-Mar-2026
 */
public class OracleDateFormatAndSimpleJdbcInsertTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();
    try {
      stmt.execute("DROP TABLE IF EXISTS vcas_nb_policys");
    } catch (Exception ignored) {
    }

    // 模拟图1/图2中的 VCAS_NB_POLICYS 表，包含 timestamp/date 类型列
    stmt.execute(
        "CREATE TABLE vcas_nb_policys ("
        + "  pol_no         VARCHAR2(20),"
        + "  trxn_seq_no    NUMBER,"
        + "  pol_eff_dt     DATE,"
        + "  pdf_eff_dt     TIMESTAMP,"
        + "  owner_type     VARCHAR2(10),"
        + "  db_opt         VARCHAR2(4)"
        + ")");
    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    Statement stmt = conn.createStatement();
    try {
      stmt.execute("DROP TABLE IF EXISTS vcas_nb_policys");
    } catch (Exception ignored) {
    }
    stmt.close();
    conn.close();
  }

  // ================================================================
  // 测试1: 复现图2的核心问题
  // setObject(idx, "06-Mar-2026", Types.DATE) 会调用
  // TimestampUtils.toDate -> toTimestamp -> parseBackendTimestamp
  // 原来的 parseBackendTimestamp 无法解析 DD-Mon-YYYY 格式，抛出:
  // Bad value for type timestamp/date/time: 06-Mar-2026
  // ================================================================
  @Test
  public void testOracleNlsDateFormatSetObjectAsDate() throws SQLException {
    System.out.println("=== 测试1: Oracle NLS 日期格式 setObject(Types.DATE) ===");

    PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO vcas_nb_policys (pol_no, pol_eff_dt) VALUES (?, ?)");

    ps.setString(1, "POL001");
    // 图2中 POL_EFF_DT: "06-Mar-2026" — Oracle NLS DD-Mon-YYYY 格式
    // 之前这里会抛出: Bad value for type timestamp/date/time: 06-Mar-2026
    ps.setObject(2, "06-Mar-2026", Types.DATE);
    ps.executeUpdate();
    ps.close();

    // 验证插入的数据正确
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT pol_no, pol_eff_dt FROM vcas_nb_policys WHERE pol_no = 'POL001'");
    assertTrue("Should have a row", rs.next());
    assertEquals("POL001", rs.getString("pol_no"));
    Date polEffDt = rs.getDate("pol_eff_dt");
    assertNotNull("pol_eff_dt should not be null", polEffDt);
    System.out.println("  pol_eff_dt = " + polEffDt);
    // 验证年月日正确: 2026-03-06
    assertEquals("Year should be 2026", 2026, polEffDt.toLocalDate().getYear());
    assertEquals("Month should be 3", 3, polEffDt.toLocalDate().getMonthValue());
    assertEquals("Day should be 6", 6, polEffDt.toLocalDate().getDayOfMonth());
    rs.close();
    stmt.close();
  }

  // ================================================================
  // 测试2: Oracle NLS 格式作为 timestamp 类型
  // setObject(idx, "06-Mar-2026", Types.TIMESTAMP)
  // ================================================================
  @Test
  public void testOracleNlsDateFormatSetObjectAsTimestamp() throws SQLException {
    System.out.println("=== 测试2: Oracle NLS 日期格式 setObject(Types.TIMESTAMP) ===");

    PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO vcas_nb_policys (pol_no, pdf_eff_dt) VALUES (?, ?)");

    ps.setString(1, "POL002");
    // 同样的 Oracle NLS 格式，传入 TIMESTAMP 类型
    ps.setObject(2, "06-Mar-2026", Types.TIMESTAMP);
    ps.executeUpdate();
    ps.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT pol_no, pdf_eff_dt FROM vcas_nb_policys WHERE pol_no = 'POL002'");
    assertTrue("Should have a row", rs.next());
    Timestamp pdfEffDt = rs.getTimestamp("pdf_eff_dt");
    assertNotNull("pdf_eff_dt should not be null", pdfEffDt);
    System.out.println("  pdf_eff_dt = " + pdfEffDt);
    assertEquals("Year should be 2026", 2026, pdfEffDt.toLocalDateTime().getYear());
    assertEquals("Month should be 3", 3, pdfEffDt.toLocalDateTime().getMonthValue());
    assertEquals("Day should be 6", 6, pdfEffDt.toLocalDateTime().getDayOfMonth());
    rs.close();
    stmt.close();
  }

  // ================================================================
  // 测试3: 各种 Oracle NLS 日期格式变体
  // ================================================================
  @Test
  public void testOracleNlsDateFormatVariants() throws SQLException {
    System.out.println("=== 测试3: Oracle NLS 日期格式变体 ===");

    String[][] testCases = {
        // {Oracle格式输入, 期望的年, 期望的月, 期望的日}
        {"06-Mar-2026", "2026", "3", "6"},
        {"01-JAN-2025", "2025", "1", "1"},
        {"31-DEC-2024", "2024", "12", "31"},
        {"15-feb-2023", "2023", "2", "15"},
        {"20-APR-2026", "2026", "4", "20"},
    };

    for (String[] tc : testCases) {
      PreparedStatement ps = conn.prepareStatement(
          "INSERT INTO vcas_nb_policys (pol_no, pol_eff_dt) VALUES (?, ?)");
      ps.setString(1, "VARIANT-" + tc[0]);
      ps.setObject(2, tc[0], Types.DATE);
      ps.executeUpdate();
      ps.close();

      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery(
          "SELECT pol_eff_dt FROM vcas_nb_policys WHERE pol_no = 'VARIANT-" + tc[0] + "'");
      assertTrue("Should have a row for " + tc[0], rs.next());
      Date d = rs.getDate("pol_eff_dt");
      assertNotNull("date should not be null for " + tc[0], d);
      System.out.println("  " + tc[0] + " -> " + d);
      assertEquals("Year mismatch for " + tc[0], Integer.parseInt(tc[1]), d.toLocalDate().getYear());
      assertEquals("Month mismatch for " + tc[0], Integer.parseInt(tc[2]), d.toLocalDate().getMonthValue());
      assertEquals("Day mismatch for " + tc[0], Integer.parseInt(tc[3]), d.toLocalDate().getDayOfMonth());
      rs.close();
      stmt.close();
    }
  }

  // ================================================================
  // 测试4: 模拟图1的 SimpleJdbcInsert 场景
  // 构造 param Map 包含 Oracle NLS 格式日期字段，模拟逐行插入
  // 对应图1中 sJdbcInsert.execute(param) 调用链路
  // ================================================================
  @Test
  public void testSimpleJdbcInsertSimulationWithOracleDate() throws SQLException {
    System.out.println("=== 测试4: 模拟 SimpleJdbcInsert 含 Oracle NLS 日期字段 ===");

    // 模拟 casDataDTO.getParams() 中的每一行数据
    // 图1: 列名从 casDataDTO.getParams().getFirst().keySet() 获取
    // 包含 POL_EFF_DT 字段值为 "06-Mar-2026"（Oracle NLS格式）
    Map<String, Object>[] rows = new Map[]{
        buildRow("CAS001", 1001, "06-Mar-2026", null, null, "4"),
        buildRow("CAS002", 1002, "15-JAN-2026", "01-Feb-2026", null, "4"),
        buildRow("CAS003", 1003, null,           null,          null, "4"),
    };

    // 模拟 SimpleJdbcInsert.usingColumns(...).execute(param) 的底层 SQL
    String sql = "INSERT INTO vcas_nb_policys "
        + "(pol_no, trxn_seq_no, pol_eff_dt, pdf_eff_dt, owner_type, db_opt) "
        + "VALUES (?, ?, ?, ?, ?, ?)";

    for (Map<String, Object> row : rows) {
      PreparedStatement ps = conn.prepareStatement(sql);
      ps.setObject(1, row.get("POL_NO"), Types.VARCHAR);
      ps.setObject(2, row.get("TRXN_SEQ_NO"), Types.NUMERIC);
      // POL_EFF_DT: Oracle NLS 格式字符串，传入 DATE 类型
      ps.setObject(3, row.get("POL_EFF_DT"), Types.DATE);
      // PDF_EFF_DT: Oracle NLS 格式字符串，传入 TIMESTAMP 类型
      ps.setObject(4, row.get("PDF_EFF_DT"), Types.TIMESTAMP);
      ps.setObject(5, row.get("OWNER_TYPE"), Types.VARCHAR);
      ps.setObject(6, row.get("DB_OPT"), Types.VARCHAR);
      ps.executeUpdate();
      ps.close();
      System.out.println("  Inserted: " + row.get("POL_NO"));
    }

    // 验证全部3行插入成功
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT COUNT(*) FROM vcas_nb_policys WHERE db_opt = '4'");
    assertTrue(rs.next());
    assertEquals("Should have inserted 3 rows", 3, rs.getInt(1));
    rs.close();

    // 验证日期字段
    rs = stmt.executeQuery(
        "SELECT pol_no, pol_eff_dt FROM vcas_nb_policys WHERE pol_no = 'CAS001'");
    assertTrue(rs.next());
    Date d = rs.getDate("pol_eff_dt");
    assertNotNull("pol_eff_dt should not be null", d);
    System.out.println("  CAS001 pol_eff_dt = " + d);
    assertEquals(2026, d.toLocalDate().getYear());
    assertEquals(3, d.toLocalDate().getMonthValue());
    assertEquals(6, d.toLocalDate().getDayOfMonth());

    rs.close();
    stmt.close();
  }

  // ================================================================
  // 测试5: ISO 标准日期格式仍然正常工作（回归测试）
  // ================================================================
  @Test
  public void testIsoDateFormatStillWorks() throws SQLException {
    System.out.println("=== 测试5: ISO 标准日期格式回归测试 ===");

    PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO vcas_nb_policys (pol_no, pol_eff_dt, pdf_eff_dt) VALUES (?, ?, ?)");
    ps.setString(1, "ISO001");
    ps.setObject(2, "2026-03-06", Types.DATE);
    ps.setObject(3, "2026-03-06 10:30:00", Types.TIMESTAMP);
    ps.executeUpdate();
    ps.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT pol_no, pol_eff_dt, pdf_eff_dt FROM vcas_nb_policys WHERE pol_no = 'ISO001'");
    assertTrue(rs.next());
    Date d = rs.getDate("pol_eff_dt");
    assertNotNull(d);
    assertEquals(2026, d.toLocalDate().getYear());
    assertEquals(3, d.toLocalDate().getMonthValue());
    assertEquals(6, d.toLocalDate().getDayOfMonth());

    Timestamp ts = rs.getTimestamp("pdf_eff_dt");
    assertNotNull(ts);
    assertEquals(10, ts.toLocalDateTime().getHour());
    System.out.println("  pol_eff_dt = " + d + ", pdf_eff_dt = " + ts);

    rs.close();
    stmt.close();
  }

  private Map<String, Object> buildRow(String polNo, int trxnSeqNo,
      Object polEffDt, Object pdfEffDt, Object ownerType, String dbOpt) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("POL_NO", polNo);
    row.put("TRXN_SEQ_NO", trxnSeqNo);
    row.put("POL_EFF_DT", polEffDt);
    row.put("PDF_EFF_DT", pdfEffDt);
    row.put("OWNER_TYPE", ownerType);
    row.put("DB_OPT", dbOpt);
    return row;
  }
}
