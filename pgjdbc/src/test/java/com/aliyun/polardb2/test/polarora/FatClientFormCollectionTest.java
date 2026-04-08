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
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.PGConnection;
import com.aliyun.polardb2.test.TestUtil;

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
 * 测试使用 begin...end 块调用带有集合类型（TABLE OF 复合类型）IN 参数的存储过程。
 *
 * <p>场景：模拟业务系统通过 JDBC 向 PolarDB 存储过程传递
 * tab_fat_client_form（TABLE OF rec_fat_client_form）类型集合参数。
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

    // 创建存储过程：接收集合，统计记录数并返回
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE proc_count_fat_client_form("
            + "  p_forms   IN  tab_fat_client_form,"
            + "  p_count   INOUT numeric,"
            + "  p_status  OUT varchar,"
            + "  p_forms2   OUT  tab_fat_client_form"
            + ") IS "
            + "BEGIN "
            + "  p_count  := p_forms.COUNT; "
            + "  p_status := 'OK-' || TO_CHAR(p_forms.COUNT); "
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

      System.out.println("  p_count  = " + count);
      System.out.println("  p_status = " + status);
      // System.out.println("  p_forms2 = " + (outForms != null ? "Array" : "null"));

      assertEquals("应返回3条记录", 3, count);
      assertNotNull("status不应为null", status);
      assertTrue("status应以OK-开头", status.startsWith("OK-"));
      assertTrue("status应包含记录数3", status.contains("3"));
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

      System.out.println("  p_count  = " + count);
      System.out.println("  p_status = " + status);
      System.out.println("  p_forms2 = " + (outForms != null ? "Array" : "null"));

      assertEquals("应返回1条记录", 1, count);
      assertTrue("status应以OK-开头", status.startsWith("OK-"));
    }
  }
}
