/*
 * Portions Copyright (c) 2025, Alibaba Group Holding Limited
 *
 * 测试场景：使用 begin ... end; 形式调用存储过程，
 * 存储过程接收集合类型参数 tab_fat_client_form（TABLE OF rec_fat_client_form）。
 *
 * 参数类型定义:
 *   CREATE TYPE rec_fat_client_form AS (
 *     client_id  numeric(9,0),
 *     form_id    character varying(50),
 *     q_id       numeric(9,0),
 *     answer     character varying(300),
 *     a_date     date
 *   );
 *   CREATE TYPE tab_fat_client_form AS TABLE OF rec_fat_client_form;
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.PGConnection;
import com.aliyun.polardb2.jdbc.PostgresStructConverter;
import com.aliyun.polardb2.test.TestUtil;
import com.aliyun.polardb2.util.PGobject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.Properties;

/**
 * 测试使用 begin...end 块调用带有集合类型（TABLE OF 复合类型）IN/OUT 参数的存储过程。
 *
 * <p>场景：模拟业务系统通过 JDBC 向 PolarDB 存储过程传递
 * tab_fat_client_form（TABLE OF rec_fat_client_form）类型集合参数，
 * 并验证 OUT 集合类型、OUT 复合类型参数能正确解析，字段内容符合预期。
 */
public class FatClientFormCollectionTest {

  private Connection conn;
  private PGConnection pgConn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);
    pgConn = conn.unwrap(PGConnection.class);

    Statement stmt = conn.createStatement();

    // 清理旧对象（如有）
    try {
      stmt.execute("DROP PROCEDURE IF EXISTS proc_save_fat_client_form");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP PROCEDURE IF EXISTS proc_count_fat_client_form");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP PROCEDURE IF EXISTS proc_first_fat_client_form");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS tab_fat_client_form");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS \"cis\".\"rec_fat_client_form\"");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP SCHEMA IF EXISTS cis CASCADE");
    } catch (Exception ignored) {
    }

    // 创建 schema cis
    stmt.execute("CREATE SCHEMA IF NOT EXISTS cis");

    // 创建复合类型 cis.rec_fat_client_form
    stmt.execute(
        "CREATE TYPE \"cis\".\"rec_fat_client_form\" AS ("
            + "  client_id  numeric(9,0),"
            + "  form_id    character varying(50),"
            + "  q_id       numeric(9,0),"
            + "  answer     character varying(300),"
            + "  a_date     date"
            + ")");

    // 创建集合类型 tab_fat_client_form
    stmt.execute(
        "CREATE TYPE tab_fat_client_form AS TABLE OF \"cis\".\"rec_fat_client_form\"");

    // 创建存储过程：接收集合，统计记录数，对每个元素的字段做计算后回传
    // 计算规则：client_id += 9000，answer 转大写，a_date + 1天（NULL 保持 NULL）
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE proc_count_fat_client_form("
            + "  p_forms   IN  tab_fat_client_form,"
            + "  p_count   INOUT numeric,"
            + "  p_status  OUT varchar,"
            + "  p_forms2   OUT  tab_fat_client_form"
            + ") IS "
            + "  v_row  \"cis\".\"rec_fat_client_form\"; "
            + "  v_result tab_fat_client_form := tab_fat_client_form(); "
            + "BEGIN "
            + "  p_count  := p_forms.COUNT; "
            + "  p_status := 'OK-' || TO_CHAR(p_forms.COUNT); "
            + "  FOR i IN 1..p_forms.COUNT LOOP "
            + "    v_row.client_id := p_forms(i).client_id + 9000; "
            + "    v_row.form_id   := p_forms(i).form_id; "
            + "    v_row.q_id      := p_forms(i).q_id; "
            + "    v_row.answer    := UPPER(p_forms(i).answer); "
            + "    IF p_forms(i).a_date IS NOT NULL THEN "
            + "      v_row.a_date := p_forms(i).a_date + 1; "
            + "    ELSE "
            + "      v_row.a_date := NULL; "
            + "    END IF; "
            + "    v_result.EXTEND; "
            + "    v_result(v_result.LAST) := v_row; "
            + "  END LOOP; "
            + "  p_forms2 := v_result; "
            + "END;");

    // 创建存储过程：接收集合，取第一个元素做计算后返回（OUT 复合类型参数）
    // 计算规则：client_id += 9000，answer 转大写，a_date + 1天
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE proc_first_fat_client_form("
            + "  p_forms  IN  tab_fat_client_form,"
            + "  p_first  OUT \"cis\".\"rec_fat_client_form\""
            + ") IS "
            + "BEGIN "
            + "  p_first.client_id := p_forms(1).client_id + 9000; "
            + "  p_first.form_id   := p_forms(1).form_id; "
            + "  p_first.q_id      := p_forms(1).q_id; "
            + "  p_first.answer    := UPPER(p_forms(1).answer); "
            + "  IF p_forms(1).a_date IS NOT NULL THEN "
            + "    p_first.a_date := p_forms(1).a_date + 1; "
            + "  ELSE "
            + "    p_first.a_date := NULL; "
            + "  END IF; "
            + "END;");

    // 创建存储过程：接收集合，仅做保存（无出参，模拟写入场景）
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE proc_save_fat_client_form("
            + "  p_forms IN tab_fat_client_form"
            + ") IS "
            + "BEGIN "
            + "  NULL; "
            + "END;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    Statement stmt = conn.createStatement();
    try {
      stmt.execute("DROP PROCEDURE IF EXISTS proc_count_fat_client_form");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP PROCEDURE IF EXISTS proc_first_fat_client_form");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP PROCEDURE IF EXISTS proc_save_fat_client_form");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS tab_fat_client_form");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP SCHEMA IF EXISTS cis CASCADE");
    } catch (Exception ignored) {
    }
    stmt.close();
    conn.close();
  }

  /**
   * 测试使用 begin...end 块调用存储过程，传入包含多条记录的 tab_fat_client_form 集合。
   *
   * <p>调用形式: begin proc_count_fat_client_form(?, ?, ?, ?); end;
   * 注意：存储过程有4个参数，其中第4个是集合类型的 OUT 参数。
   */
  @Test
  public void testBeginEndCallWithCollectionParam() throws Exception {
    // 构造集合元素：rec_fat_client_form
    // 字段: client_id, form_id, q_id, answer, a_date
    Object[] row1 = new Object[]{
        new BigDecimal("1001"),        // client_id
        "FORM-001",                    // form_id
        new BigDecimal("10"),          // q_id
        "Yes",                         // answer
        Date.valueOf("2025-01-15")     // a_date
    };

    Object[] row2 = new Object[]{
        new BigDecimal("1002"),        // client_id
        "FORM-001",                    // form_id
        new BigDecimal("11"),          // q_id
        "No",                          // answer
        Date.valueOf("2025-01-16")     // a_date
    };

    Object[] row3 = new Object[]{
        new BigDecimal("1003"),        // client_id
        "FORM-002",                    // form_id
        new BigDecimal("20"),          // q_id
        "N/A",                         // answer
        null                           // a_date — NULL 日期
    };

    // 创建 Struct 对象，类型名为 cis.rec_fat_client_form
    Struct struct1 = conn.createStruct("cis.rec_fat_client_form", row1);
    Struct struct2 = conn.createStruct("cis.rec_fat_client_form", row2);
    Struct struct3 = conn.createStruct("cis.rec_fat_client_form", row3);

    // 创建 TABLE OF 数组，类型名为 tab_fat_client_form
    Array formsArray = pgConn.createArrayOf("tab_fat_client_form",
        new Struct[]{struct1, struct2, struct3});

    // 使用 begin...end 块调用存储过程
    // 存储过程签名: (p_forms IN, p_count INOUT, p_status OUT, p_forms2 OUT)
    try (CallableStatement cs = conn.prepareCall(
        "begin proc_count_fat_client_form(?, ?, ?, ?); end;")) {

      cs.setObject(1, formsArray, Types.ARRAY);   // p_forms IN
      cs.setObject(2, 0, Types.NUMERIC);          // p_count IN
      cs.registerOutParameter(2, Types.NUMERIC);  // p_count OUT
      // cs.registerOutParameter(3, Types.VARCHAR);  // p_status OUT
      // 集合类型 OUT 参数必须指定类型名，否则会被推断为 text
      // cs.registerOutParameter(4, Types.ARRAY, "tab_fat_client_form");  // p_forms2 OUT

      cs.execute();

      int count = cs.getInt(2);
      String status = cs.getString(3);
      // Array outForms = cs.getArray(4);

      assertEquals("应返回3条记录", 3, count);
      assertNotNull("status不应为null", status);
      assertTrue("status应以OK-开头", status.startsWith("OK-"));
      assertTrue("status应包含记录数3", status.contains("3"));
    }
  }

  /**
   * 复现线上报错：ClassCastException: class java.lang.String cannot be cast to class java.sql.Array
   * 并验证修复后 OUT 集合类型参数能成功解析，且 Array 中的元素数量和内容符合预期。
   *
   * <p>场景：begin...end 块调用存储过程，存储过程有集合类型的 OUT 参数（tab_fat_client_form）。
   * 修复前：内核对 DO block 返回集合类型时 columnType=Types.OTHER，value 为 String，
   * getArray() 强转时抛出 ClassCastException。
   * 修复后：驱动将 String 包装为 PgArray，getArray() 可正常调用，并能遍历元素。
   *
   * <p>错误栈：
   * java.lang.ClassCastException: class java.lang.String cannot be cast to class java.sql.Array
   *   at PgCallableStatement.getArray(PgCallableStatement.java:908)
   */
  @Test
  public void testOutArrayParamCanBeRetrieved() throws Exception {
    Object[] row1 = new Object[]{
        new BigDecimal("1001"),
        "FORM-001",
        new BigDecimal("10"),
        "Yes",
        Date.valueOf("2025-01-15")
    };
    Struct struct1 = conn.createStruct("cis.rec_fat_client_form", row1);
    Array formsArray = pgConn.createArrayOf("tab_fat_client_form", new Struct[]{struct1});

    // begin...end 块，注册集合类型 OUT 参数（第4个参数）
    try (CallableStatement cs = conn.prepareCall(
        "begin proc_count_fat_client_form(?, ?, ?, ?); end;")) {

      cs.setObject(1, formsArray, Types.ARRAY);
      cs.setObject(2, 0, Types.NUMERIC);
      cs.registerOutParameter(2, Types.NUMERIC);
      cs.registerOutParameter(3, Types.VARCHAR);
      // 注册集合类型 OUT 参数，指定类型名
      cs.registerOutParameter(4, Types.ARRAY, "tab_fat_client_form");

      cs.execute();

      int count = cs.getInt(2);
      assertEquals("应返回1条记录", 1, count);

      String status = cs.getString(3);
      assertNotNull("p_status 不应为 null", status);
      assertTrue("status应以OK-开头", status.startsWith("OK-"));

      // 验证 OUT 集合类型参数不再抛出 ClassCastException，且可正常读取
      Array outForms = cs.getArray(4);
      assertNotNull("p_forms2 OUT 集合不应为 null", outForms);

      // 验证能正常展开为 Object 数组
      Object[] elements = (Object[]) outForms.getArray();
      assertNotNull("展开后元素数组不应为 null", elements);
      assertEquals("OUT 集合元素数量应与 IN 一致", 1, elements.length);
    }
  }

  /**
   * 测试 OUT 集合类型参数（多行）：验证存储过程对每行做计算（client_id+9000/answer大写/a_date+1）后，
   * 返回的集合元素数量和每个元素的关键字段值均符合预期。
   *
   * <p>计算规则（proc_count_fat_client_form）：
   * <ul>
   *   <li>client_id += 9000</li>
   *   <li>answer = UPPER(answer)</li>
   *   <li>a_date = a_date + 1（NULL 保持 NULL）</li>
   * </ul>
   */
  @Test
  public void testOutArrayParamMultiRowContentVerification() throws Exception {
    Object[] row1 = new Object[]{
        new BigDecimal("2001"), "FORM-A", new BigDecimal("1"), "answer-1",
        Date.valueOf("2025-03-01")
    };
    Object[] row2 = new Object[]{
        new BigDecimal("2002"), "FORM-B", new BigDecimal("2"), "answer-2",
        Date.valueOf("2025-03-02")
    };
    Object[] row3 = new Object[]{
        new BigDecimal("2003"), "FORM-C", new BigDecimal("3"), "answer-3",
        null   // NULL 日期，OUT 时也应保持 NULL
    };

    Struct s1 = conn.createStruct("cis.rec_fat_client_form", row1);
    Struct s2 = conn.createStruct("cis.rec_fat_client_form", row2);
    Struct s3 = conn.createStruct("cis.rec_fat_client_form", row3);
    Array formsArray = pgConn.createArrayOf("tab_fat_client_form", new Struct[]{s1, s2, s3});

    try (CallableStatement cs = conn.prepareCall(
        "begin proc_count_fat_client_form(?, ?, ?, ?); end;")) {

      cs.setObject(1, formsArray, Types.ARRAY);
      cs.setObject(2, 0, Types.NUMERIC);
      cs.registerOutParameter(2, Types.NUMERIC);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.registerOutParameter(4, Types.ARRAY, "tab_fat_client_form");

      cs.execute();

      assertEquals("p_count 应为3", 3, cs.getInt(2));
      assertTrue("p_status 应包含3", cs.getString(3).contains("3"));

      // 验证 OUT 集合返回3个元素
      Array outForms = cs.getArray(4);
      assertNotNull("p_forms2 不应为 null", outForms);
      Object[] elements = (Object[]) outForms.getArray();
      assertNotNull("元素数组不为 null", elements);
      assertEquals("OUT 集合应含3条记录", 3, elements.length);

      // 验证每个元素都是 PGobject（复合类型的文本表示）
      for (Object element : elements) {
        assertNotNull("每个元素不应为 null", element);
        assertTrue("每个元素应为 PGobject", element instanceof PGobject);
      }

      // 验证第1个元素字段值：client_id=2001+9000=11001，answer=ANSWER-1，a_date=2025-03-02
      Object[] fields1 = PostgresStructConverter.parsePostgresStruct(elements[0].toString());
      assertNotNull("第1个元素字段不为 null", fields1);
      assertEquals("第1条 client_id 应为 11001", "11001", fields1[0]);
      assertEquals("第1条 form_id 应为 FORM-A", "FORM-A", fields1[1]);
      assertEquals("第1条 answer 应转大写为 ANSWER-1", "ANSWER-1", fields1[3]);
      // a_date 在 PG 复合类型文本中可能带时间后缀（如 "2025-03-02 00:00:00"），用 startsWith 校验
      assertTrue("第1条 a_date 应+1天为 2025-03-02",
          String.valueOf(fields1[4]).startsWith("2025-03-02"));

      // 验证第2个元素字段值：client_id=2002+9000=11002，answer=ANSWER-2，a_date=2025-03-03
      Object[] fields2 = PostgresStructConverter.parsePostgresStruct(elements[1].toString());
      assertNotNull("第2个元素字段不为 null", fields2);
      assertEquals("第2条 client_id 应为 11002", "11002", fields2[0]);
      assertEquals("第2条 answer 应转大写为 ANSWER-2", "ANSWER-2", fields2[3]);
      assertTrue("第2条 a_date 应+1天为 2025-03-03",
          String.valueOf(fields2[4]).startsWith("2025-03-03"));

      // 验证第3个元素字段值：client_id=2003+9000=11003，answer=ANSWER-3，a_date=NULL
      Object[] fields3 = PostgresStructConverter.parsePostgresStruct(elements[2].toString());
      assertNotNull("第3个元素字段不为 null", fields3);
      assertEquals("第3条 client_id 应为 11003", "11003", fields3[0]);
      assertEquals("第3条 answer 应转大写为 ANSWER-3", "ANSWER-3", fields3[3]);
      assertNull("第3条 a_date 应为 NULL（入参为 NULL）", fields3[4]);
    }
  }

  /**
   * 测试 OUT 复合类型参数（单个 rec_fat_client_form）：
   * 调用 proc_first_fat_client_form，取集合第一个元素并做计算后作为 OUT 复合类型参数返回，
   * 验证驱动将 DO block String 结果包装为 PGobject，并逐字段校验计算结果。
   *
   * <p>计算规则：client_id += 9000，answer = UPPER(answer)，a_date += 1天。
   */
  @Test
  public void testOutCompositeTypeParamFieldVerification() throws Exception {
    // 传入两条记录，存储过程只取第一条做计算
    Object[] row1 = new Object[]{
        new BigDecimal("3001"), "FORM-X", new BigDecimal("99"), "answerX",
        Date.valueOf("2025-07-01")
    };
    Object[] row2 = new Object[]{
        new BigDecimal("3002"), "FORM-Y", new BigDecimal("100"), "answerY",
        Date.valueOf("2025-07-02")
    };

    Struct s1 = conn.createStruct("cis.rec_fat_client_form", row1);
    Struct s2 = conn.createStruct("cis.rec_fat_client_form", row2);
    Array formsArray = pgConn.createArrayOf("tab_fat_client_form", new Struct[]{s1, s2});

    try (CallableStatement cs = conn.prepareCall(
        "begin proc_first_fat_client_form(?, ?); end;")) {

      cs.setObject(1, formsArray, Types.ARRAY);
      // p_first OUT 是单个复合类型 cis.rec_fat_client_form，注册为 Types.STRUCT
      cs.registerOutParameter(2, Types.STRUCT, "cis.rec_fat_client_form");

      cs.execute();

      // OUT 复合类型参数在 DO block 中返回 Types.OTHER + String
      // 驱动应将其包装为 PGobject，不再返回原始 String
      Object outFirst = cs.getObject(2);
      assertNotNull("p_first OUT 复合类型不应为 null", outFirst);
      assertTrue("p_first 应为 PGobject", outFirst instanceof PGobject);

      // 使用 PostgresStructConverter 解析字段，逐一验证计算结果
      // 预期：client_id=3001+9000=12001，form_id=FORM-X，q_id=99，answer=ANSWERX，a_date=2025-07-02
      Object[] fields = PostgresStructConverter.parsePostgresStruct(outFirst.toString());
      assertNotNull("解析后字段数组不应为 null", fields);
      assertEquals("rec_fat_client_form 应有5个字段", 5, fields.length);

      assertEquals("client_id 应为 12001（3001+9000）", "12001", fields[0]);
      assertEquals("form_id 应为 FORM-X", "FORM-X", fields[1]);
      assertEquals("q_id 应为 99", "99", fields[2]);
      assertEquals("answer 应转大写为 ANSWERX", "ANSWERX", fields[3]);
      // a_date 在 PG 复合类型文本中可能带时间后缀（如 "2025-07-02 00:00:00"），用 startsWith 校验
      assertTrue("a_date 应+1天为 2025-07-02",
          String.valueOf(fields[4]).startsWith("2025-07-02"));
    }
  }

  /**
   * 测试 OUT 复合类型参数，当第一个元素的 a_date 为 NULL 时，OUT 复合类型中该字段也为 NULL。
   */
  @Test
  public void testOutCompositeTypeParamWithNullDate() throws Exception {
    Object[] rowWithNull = new Object[]{
        new BigDecimal("5001"), "FORM-Z", new BigDecimal("0"), "nulldate",
        null   // a_date = NULL
    };

    Struct s = conn.createStruct("cis.rec_fat_client_form", rowWithNull);
    Array formsArray = pgConn.createArrayOf("tab_fat_client_form", new Struct[]{s});

    try (CallableStatement cs = conn.prepareCall(
        "begin proc_first_fat_client_form(?, ?); end;")) {

      cs.setObject(1, formsArray, Types.ARRAY);
      cs.registerOutParameter(2, Types.STRUCT, "cis.rec_fat_client_form");

      cs.execute();

      Object outFirst = cs.getObject(2);
      assertNotNull("p_first 不应为 null", outFirst);
      assertTrue("p_first 应为 PGobject", outFirst instanceof PGobject);

      Object[] fields = PostgresStructConverter.parsePostgresStruct(outFirst.toString());
      assertNotNull("字段数组不为 null", fields);
      assertEquals("client_id 应为 14001（5001+9000）", "14001", fields[0]);
      assertEquals("answer 应转大写为 NULLDATE", "NULLDATE", fields[3]);
      assertNull("a_date 应为 NULL", fields[4]);
    }
  }

  /**
   * 测试 OUT 集合类型参数含 NULL 日期字段时能正确解析（不崩溃）。
   *
   * <p>第3条记录的 a_date 为 NULL，验证 PgArray 解析不因 NULL 字段而异常。
   */
  @Test
  public void testOutArrayParamWithNullDateField() throws Exception {
    Object[] rowWithNull = new Object[]{
        new BigDecimal("4001"), "FORM-NULL", new BigDecimal("0"), "N/A",
        null   // a_date = NULL
    };

    Struct s = conn.createStruct("cis.rec_fat_client_form", rowWithNull);
    Array formsArray = pgConn.createArrayOf("tab_fat_client_form", new Struct[]{s});

    try (CallableStatement cs = conn.prepareCall(
        "begin proc_count_fat_client_form(?, ?, ?, ?); end;")) {

      cs.setObject(1, formsArray, Types.ARRAY);
      cs.setObject(2, 0, Types.NUMERIC);
      cs.registerOutParameter(2, Types.NUMERIC);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.registerOutParameter(4, Types.ARRAY, "tab_fat_client_form");

      cs.execute();

      assertEquals("p_count 应为1", 1, cs.getInt(2));

      // 含 NULL 字段的集合 OUT 参数也应能正常返回
      Array outForms = cs.getArray(4);
      assertNotNull("含 NULL 字段的 OUT 集合不应为 null", outForms);
      Object[] elements = (Object[]) outForms.getArray();
      assertNotNull("元素数组不为 null", elements);
      assertEquals("应有1个元素", 1, elements.length);

      // 元素的文本值应可读取（不崩溃）
      assertNotNull("元素不应为 null", elements[0]);
      String strVal = elements[0].toString();
      // NULL 字段在 PG 文本格式中表示为空位（逗号之间无值）
      assertNotNull("元素文本值不应为 null", strVal);
    }
  }

  /**
   * 测试使用 begin...end 块调用存储过程，传入单条记录的集合。
   */
  @Test
  public void testBeginEndCallWithSingleElementCollection() throws Exception {
    Object[] row = new Object[]{
        new BigDecimal("2001"),        // client_id
        "FORM-100",                    // form_id
        new BigDecimal("5"),           // q_id
        "Agree",                       // answer
        Date.valueOf("2025-06-01")     // a_date
    };

    Struct struct = conn.createStruct("cis.rec_fat_client_form", row);
    Array formsArray = pgConn.createArrayOf("tab_fat_client_form", new Struct[]{struct});

    // 存储过程签名: (p_forms IN, p_count INOUT, p_status OUT, p_forms2 OUT)
    try (CallableStatement cs = conn.prepareCall(
        "begin proc_count_fat_client_form(?, ?, ?, ?); end;")) {

      cs.setObject(1, formsArray, Types.ARRAY);
      cs.setObject(2, 0, Types.NUMERIC);
      cs.registerOutParameter(2, Types.NUMERIC);
      cs.registerOutParameter(3, Types.VARCHAR);
      // 集合类型 OUT 参数必须指定类型名，否则会被推断为 text
      cs.registerOutParameter(4, Types.ARRAY, "tab_fat_client_form");

      cs.execute();

      int count = cs.getInt(2);
      String status = cs.getString(3);
      Array outForms = cs.getArray(4);

      assertEquals("应返回1条记录", 1, count);
      assertTrue("status应以OK-开头", status.startsWith("OK-"));
      assertNotNull("p_forms2 不应为 null", outForms);
    }
  }
}
