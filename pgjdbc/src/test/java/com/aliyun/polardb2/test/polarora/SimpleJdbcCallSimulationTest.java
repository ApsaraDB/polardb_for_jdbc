/*
 * 模拟 PolarDBSimulationApp + DataAccessRepositoryImpl 中
 * SimpleJdbcCall 路径在 PolarDB 中的运行。
 *
 * 核心区别：使用 setObject(idx, value, Types.STRUCT) / setObject(idx, value, Types.ARRAY)
 * 来模拟 Spring SimpleJdbcCall 绑定参数的方式，而非直接 setString/setObject。
 *
 * 额外增加 STRUCT OUT 和 ARRAY OUT 作为出参的专项测试。
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.PGConnection;
import com.aliyun.polardb2.test.TestUtil;
import com.aliyun.polardb2.util.PGobject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * 模拟 SimpleJdbcCall + DataAccessRepositoryImpl 的完整流程。
 *
 * <p>测试场景：
 * 1. 简单 IN/OUT 参数 — setObject(idx, value, Types.VARCHAR/NUMERIC)
 * 2. STRUCT IN 参数 — setObject(idx, struct, Types.STRUCT)
 * 3. ARRAY of STRUCT IN + ARRAY OUT — setObject(idx, array, Types.ARRAY)
 * 4. STRUCT 作为 OUT 参数
 * 5. ARRAY of STRUCT 作为 OUT 参数（专项）
 */
public class SimpleJdbcCallSimulationTest {

  private Connection conn;
  private PGConnection pgConn;

  /**
   * 解析 PostgreSQL 复合类型字面量，如 (val1,"val2",val3)。
   */
  private static String[] parseCompositeLiteral(String literal) {
    String inner = literal.substring(1, literal.length() - 1);
    List<String> fields = new ArrayList<String>();
    StringBuilder sb = new StringBuilder();
    boolean inQuote = false;
    for (int i = 0; i < inner.length(); i++) {
      char c = inner.charAt(i);
      if (c == '"') {
        inQuote = !inQuote;
      } else if (c == ',' && !inQuote) {
        fields.add(sb.toString());
        sb.setLength(0);
      } else {
        sb.append(c);
      }
    }
    fields.add(sb.toString());
    return fields.toArray(new String[0]);
  }

  /**
   * 从数组元素中提取属性数组。
   * 支持 Struct（直接 getAttributes）和 PGobject（解析复合字面量）。
   */
  private static Object[] extractAttributes(Object element) throws Exception {
    if (element instanceof Struct) {
      return ((Struct) element).getAttributes();
    } else if (element instanceof PGobject) {
      String val = ((PGobject) element).getValue();
      return parseCompositeLiteral(val);
    } else {
      return new Object[]{element.toString()};
    }
  }

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    conn = TestUtil.openDB(props);
    pgConn = conn.unwrap(PGConnection.class);

    Statement stmt = conn.createStatement();

    // ========== 清理旧对象 ==========
    try {
      stmt.execute("DROP PACKAGE IF EXISTS NB_RIDER_PKG");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP PACKAGE IF EXISTS NB_UNDERWRITING_PKG");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP PACKAGE IF EXISTS NB_POLICY_PKG");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP PACKAGE IF EXISTS NB_STRUCT_OUT_PKG");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP PACKAGE IF EXISTS NB_ARRAY_OUT_PKG");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS NB_RESULT_ARRAY");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS NB_RIDER_ARRAY");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS NB_RESULT_TYPE");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS NB_RIDER_TYPE");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS NB_POLICY_TYPE");
    } catch (Exception ignored) {
    }

    // ========== 创建类型 ==========
    stmt.execute(
        "CREATE OR REPLACE TYPE NB_POLICY_TYPE AS (\n"
        + "  policy_no    VARCHAR2(20),\n"
        + "  holder_name  VARCHAR2(100),\n"
        + "  start_date   VARCHAR2(20),\n"
        + "  amount       NUMBER(15,2)\n"
        + ")");

    stmt.execute(
        "CREATE OR REPLACE TYPE NB_RIDER_TYPE AS (\n"
        + "  rider_code   VARCHAR2(20),\n"
        + "  rider_name   VARCHAR2(100),\n"
        + "  premium      NUMBER(15,2)\n"
        + ")");

    stmt.execute(
        "CREATE OR REPLACE TYPE NB_RESULT_TYPE AS (\n"
        + "  rider_code   VARCHAR2(20),\n"
        + "  status       VARCHAR2(20),\n"
        + "  message      VARCHAR2(200)\n"
        + ")");

    stmt.execute("CREATE OR REPLACE TYPE NB_RIDER_ARRAY AS TABLE OF NB_RIDER_TYPE");
    stmt.execute("CREATE OR REPLACE TYPE NB_RESULT_ARRAY AS TABLE OF NB_RESULT_TYPE");

    // ========== 包1: 简单 IN/OUT ==========
    stmt.execute(
        "CREATE OR REPLACE PACKAGE NB_POLICY_PKG AS\n"
        + "  PROCEDURE PROCESS_POLICY(\n"
        + "    p_policy_no   IN  VARCHAR2,\n"
        + "    p_amount      IN  NUMBER,\n"
        + "    p_result      OUT VARCHAR2,\n"
        + "    p_status_code OUT NUMBER\n"
        + "  );\n"
        + "END NB_POLICY_PKG;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY NB_POLICY_PKG AS\n"
        + "  PROCEDURE PROCESS_POLICY(\n"
        + "    p_policy_no   IN  VARCHAR2,\n"
        + "    p_amount      IN  NUMBER,\n"
        + "    p_result      OUT VARCHAR2,\n"
        + "    p_status_code OUT NUMBER\n"
        + "  ) IS\n"
        + "  BEGIN\n"
        + "    p_result := 'Policy ' || p_policy_no || ' processed, amount=' || p_amount;\n"
        + "    p_status_code := 0;\n"
        + "  END;\n"
        + "END NB_POLICY_PKG;");

    // ========== 包2: STRUCT IN ==========
    stmt.execute(
        "CREATE OR REPLACE PACKAGE NB_UNDERWRITING_PKG AS\n"
        + "  PROCEDURE SUBMIT_POLICY(\n"
        + "    p_policy_info IN  NB_POLICY_TYPE,\n"
        + "    p_result_code OUT VARCHAR2\n"
        + "  );\n"
        + "END NB_UNDERWRITING_PKG;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY NB_UNDERWRITING_PKG AS\n"
        + "  PROCEDURE SUBMIT_POLICY(\n"
        + "    p_policy_info IN  NB_POLICY_TYPE,\n"
        + "    p_result_code OUT VARCHAR2\n"
        + "  ) IS\n"
        + "  BEGIN\n"
        + "    p_result_code := 'OK-' || p_policy_info.policy_no || '-' || p_policy_info.holder_name;\n"
        + "  END;\n"
        + "END NB_UNDERWRITING_PKG;");

    // ========== 包3: ARRAY of STRUCT IN + OUT ==========
    stmt.execute(
        "CREATE OR REPLACE PACKAGE NB_RIDER_PKG AS\n"
        + "  PROCEDURE ADD_RIDERS(\n"
        + "    p_riders    IN  NB_RIDER_ARRAY,\n"
        + "    p_policy_no IN  VARCHAR2,\n"
        + "    p_results   OUT NB_RESULT_ARRAY,\n"
        + "    p_status    OUT VARCHAR2\n"
        + "  );\n"
        + "END NB_RIDER_PKG;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY NB_RIDER_PKG AS\n"
        + "  PROCEDURE ADD_RIDERS(\n"
        + "    p_riders    IN  NB_RIDER_ARRAY,\n"
        + "    p_policy_no IN  VARCHAR2,\n"
        + "    p_results   OUT NB_RESULT_ARRAY,\n"
        + "    p_status    OUT VARCHAR2\n"
        + "  ) IS\n"
        + "  BEGIN\n"
        + "    p_results := NB_RESULT_ARRAY();\n"
        + "    FOR i IN 1..p_riders.COUNT LOOP\n"
        + "      p_results.EXTEND;\n"
        + "      p_results(i).rider_code := p_riders(i).rider_code;\n"
        + "      p_results(i).status     := 'SUCCESS';\n"
        + "      p_results(i).message    := 'Rider added to ' || p_policy_no;\n"
        + "    END LOOP;\n"
        + "    p_status := 'ALL_SUCCESS';\n"
        + "  END;\n"
        + "END NB_RIDER_PKG;");

    // ========== 包4: STRUCT 作为 OUT 参数 ==========
    stmt.execute(
        "CREATE OR REPLACE PACKAGE NB_STRUCT_OUT_PKG AS\n"
        + "  PROCEDURE GET_POLICY(\n"
        + "    p_policy_no   IN  VARCHAR2,\n"
        + "    p_policy_info OUT NB_POLICY_TYPE\n"
        + "  );\n"
        + "END NB_STRUCT_OUT_PKG;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY NB_STRUCT_OUT_PKG AS\n"
        + "  PROCEDURE GET_POLICY(\n"
        + "    p_policy_no   IN  VARCHAR2,\n"
        + "    p_policy_info OUT NB_POLICY_TYPE\n"
        + "  ) IS\n"
        + "  BEGIN\n"
        + "    p_policy_info.policy_no   := p_policy_no;\n"
        + "    p_policy_info.holder_name := 'Test Holder';\n"
        + "    p_policy_info.start_date  := '2025-06-01';\n"
        + "    p_policy_info.amount      := 9999.99;\n"
        + "  END;\n"
        + "END NB_STRUCT_OUT_PKG;");

    // ========== 包5: ARRAY of STRUCT 作为 OUT 参数（专项） ==========
    stmt.execute(
        "CREATE OR REPLACE PACKAGE NB_ARRAY_OUT_PKG AS\n"
        + "  PROCEDURE LIST_RIDERS(\n"
        + "    p_policy_no IN  VARCHAR2,\n"
        + "    p_riders    OUT NB_RIDER_ARRAY,\n"
        + "    p_count     OUT NUMBER\n"
        + "  );\n"
        + "END NB_ARRAY_OUT_PKG;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY NB_ARRAY_OUT_PKG AS\n"
        + "  PROCEDURE LIST_RIDERS(\n"
        + "    p_policy_no IN  VARCHAR2,\n"
        + "    p_riders    OUT NB_RIDER_ARRAY,\n"
        + "    p_count     OUT NUMBER\n"
        + "  ) IS\n"
        + "  BEGIN\n"
        + "    p_riders := NB_RIDER_ARRAY();\n"
        + "    p_riders.EXTEND;\n"
        + "    p_riders(1).rider_code := 'R001';\n"
        + "    p_riders(1).rider_name := 'Critical Illness';\n"
        + "    p_riders(1).premium    := 500.00;\n"
        + "    p_riders.EXTEND;\n"
        + "    p_riders(2).rider_code := 'R002';\n"
        + "    p_riders(2).rider_name := 'Accident';\n"
        + "    p_riders(2).premium    := 300.00;\n"
        + "    p_riders.EXTEND;\n"
        + "    p_riders(3).rider_code := 'R003';\n"
        + "    p_riders(3).rider_name := 'Hospital';\n"
        + "    p_riders(3).premium    := 200.00;\n"
        + "    p_count := 3;\n"
        + "  END;\n"
        + "END NB_ARRAY_OUT_PKG;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    if (conn == null) {
      return;
    }
    Statement stmt = conn.createStatement();
    try {
      stmt.execute("DROP PACKAGE IF EXISTS NB_ARRAY_OUT_PKG");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP PACKAGE IF EXISTS NB_STRUCT_OUT_PKG");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP PACKAGE IF EXISTS NB_RIDER_PKG");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP PACKAGE IF EXISTS NB_UNDERWRITING_PKG");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP PACKAGE IF EXISTS NB_POLICY_PKG");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS NB_RESULT_ARRAY");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS NB_RIDER_ARRAY");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS NB_RESULT_TYPE");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS NB_RIDER_TYPE");
    } catch (Exception ignored) {
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS NB_POLICY_TYPE");
    } catch (Exception ignored) {
    }
    stmt.close();
    conn.close();
  }

  // ================================================================
  // 测试1: 简单 IN/OUT — 模拟 SimpleJdbcCall 使用 setObject(idx, val, type)
  // 对应 PolarDBSimulationApp.testSimpleSP()
  // ================================================================
  @Test
  public void testSimpleInOutWithSetObject() throws Exception {
    System.out.println("=== 测试1: SimpleJdbcCall 模拟 — 简单 IN/OUT ===");

    CallableStatement cs = conn.prepareCall(
        "{ call NB_POLICY_PKG.PROCESS_POLICY(?, ?, ?, ?) }");

    cs.setObject(1, "POL001", Types.VARCHAR);
    cs.setObject(2, new java.math.BigDecimal("1000.50"), Types.NUMERIC);

    cs.registerOutParameter(3, Types.VARCHAR);
    cs.registerOutParameter(4, Types.NUMERIC);

    cs.execute();

    String result = cs.getString(3);
    int statusCode = cs.getInt(4);

    System.out.println("  p_result      = " + result);
    System.out.println("  p_status_code = " + statusCode);

    assertNotNull("result should not be null", result);
    assertTrue("result should contain POL001", result.contains("POL001"));
    assertTrue("result should contain amount", result.contains("1000.5"));
    assertEquals("status code should be 0", 0, statusCode);

    cs.close();
  }

  // ================================================================
  // 测试2: STRUCT IN — 模拟 SimpleJdbcCall 使用 setObject(idx, struct, Types.STRUCT)
  // 对应 PolarDBSimulationApp.testStructSP()
  // 这是驱动修复的关键路径
  // ================================================================
  @Test
  public void testStructInWithSetObjectTypesSTRUCT() throws Exception {
    System.out.println("=== 测试2: SimpleJdbcCall 模拟 — STRUCT IN (setObject + Types.STRUCT) ===");

    Object[] attributes = new Object[]{
        "POL001", "John Doe", "2025-01-01", new java.math.BigDecimal("1000.50")
    };
    Struct structObject = conn.createStruct("NB_POLICY_TYPE", attributes);

    CallableStatement cs = conn.prepareCall(
        "{ call NB_UNDERWRITING_PKG.SUBMIT_POLICY(?, ?) }");

    cs.setObject(1, structObject, Types.STRUCT);
    cs.registerOutParameter(2, Types.VARCHAR);

    cs.execute();

    String resultCode = cs.getString(2);
    System.out.println("  p_result_code = " + resultCode);

    assertNotNull("result code should not be null", resultCode);
    assertTrue("result should start with OK-", resultCode.startsWith("OK-"));
    assertTrue("result should contain POL001", resultCode.contains("POL001"));
    assertTrue("result should contain John Doe", resultCode.contains("John Doe"));

    cs.close();
  }

  // ================================================================
  // 测试3: ARRAY of STRUCT IN + OUT — 模拟 SimpleJdbcCall 使用
  //        setObject(idx, array, Types.ARRAY)
  // 对应 PolarDBSimulationApp.testArrayOfStructSP()
  // ================================================================
  @Test
  public void testArrayOfStructInOutWithSetObjectTypesARRAY() throws Exception {
    System.out.println("=== 测试3: SimpleJdbcCall 模拟 — ARRAY of STRUCT IN/OUT ===");

    Object[][] attributesOfArray = new Object[][]{
        {"RIDER001", "Critical Illness", new java.math.BigDecimal("500.00")},
        {"RIDER002", "Accident", new java.math.BigDecimal("300.00")}
    };

    Struct[] structArr = new Struct[attributesOfArray.length];
    for (int i = 0; i < attributesOfArray.length; i++) {
      structArr[i] = conn.createStruct("NB_RIDER_TYPE", attributesOfArray[i]);
    }

    Array riderArray = pgConn.createArrayOf("NB_RIDER_ARRAY", structArr);

    CallableStatement cs = conn.prepareCall(
        "{ call NB_RIDER_PKG.ADD_RIDERS(?, ?, ?, ?) }");

    cs.setObject(1, riderArray, Types.ARRAY);
    cs.setObject(2, "POL001", Types.VARCHAR);
    cs.registerOutParameter(3, Types.ARRAY);
    cs.registerOutParameter(4, Types.VARCHAR);

    cs.execute();

    String status = cs.getString(4);
    System.out.println("  p_status = " + status);
    assertEquals("status should be ALL_SUCCESS", "ALL_SUCCESS", status);

    Object outResult = cs.getObject(3);
    assertTrue("OUT param should be Array", outResult instanceof Array);

    Object[] objArray = (Object[]) ((Array) outResult).getArray();
    List<Object[]> resultList = new ArrayList<Object[]>();
    for (int i = 0; i < objArray.length; i++) {
      if (!Objects.isNull(objArray[i])) {
        Object[] attrs = extractAttributes(objArray[i]);
        System.out.println("  result[" + i + "] = rider_code=" + attrs[0]
            + ", status=" + attrs[1] + ", message=" + attrs[2]);
        resultList.add(attrs);
      }
    }
    assertEquals("should have 2 results", 2, resultList.size());
    assertEquals("first rider code", "RIDER001", String.valueOf(resultList.get(0)[0]));
    assertEquals("first status", "SUCCESS", String.valueOf(resultList.get(0)[1]));
    assertEquals("second rider code", "RIDER002", String.valueOf(resultList.get(1)[0]));

    cs.close();
  }

  // ================================================================
  // 测试4: STRUCT 作为 OUT 参数
  // ================================================================
  @Test
  public void testStructOutParam() throws Exception {
    System.out.println("=== 测试4: STRUCT 作为 OUT 参数 ===");

    CallableStatement cs = conn.prepareCall(
        "{ call NB_STRUCT_OUT_PKG.GET_POLICY(?, ?) }");

    cs.setObject(1, "POL888", Types.VARCHAR);
    cs.registerOutParameter(2, Types.STRUCT);

    cs.execute();

    Object outObj = cs.getObject(2);
    System.out.println("  OUT object type: " + (outObj == null ? "null" : outObj.getClass().getName()));
    assertNotNull("OUT struct should not be null", outObj);

    Object[] attrs = extractAttributes(outObj);
    System.out.println("  policy_no   = " + attrs[0]);
    System.out.println("  holder_name = " + attrs[1]);
    System.out.println("  start_date  = " + attrs[2]);
    System.out.println("  amount      = " + attrs[3]);

    assertEquals("policy_no should match", "POL888", String.valueOf(attrs[0]));
    assertEquals("holder_name should match", "Test Holder", String.valueOf(attrs[1]));
    assertEquals("start_date should match", "2025-06-01", String.valueOf(attrs[2]));
    assertNotNull("amount should not be null", attrs[3]);

    cs.close();
  }

  // ================================================================
  // 测试5: ARRAY of STRUCT 作为 OUT 参数（专项测试）
  // ================================================================
  @Test
  public void testArrayOfStructOutParam() throws Exception {
    System.out.println("=== 测试5: ARRAY of STRUCT 作为 OUT 参数 ===");

    CallableStatement cs = conn.prepareCall(
        "{ call NB_ARRAY_OUT_PKG.LIST_RIDERS(?, ?, ?) }");

    cs.setObject(1, "POL999", Types.VARCHAR);
    cs.registerOutParameter(2, Types.ARRAY);
    cs.registerOutParameter(3, Types.NUMERIC);

    cs.execute();

    int count = cs.getInt(3);
    System.out.println("  p_count = " + count);
    assertEquals("count should be 3", 3, count);

    Object outResult = cs.getObject(2);
    assertNotNull("OUT array should not be null", outResult);
    assertTrue("OUT param should be Array", outResult instanceof Array);

    Object[] objArray = (Object[]) ((Array) outResult).getArray();
    List<Object[]> riderList = new ArrayList<Object[]>();
    for (int i = 0; i < objArray.length; i++) {
      if (!Objects.isNull(objArray[i])) {
        Object[] attrs = extractAttributes(objArray[i]);
        System.out.println("  rider[" + i + "] = code=" + attrs[0]
            + ", name=" + attrs[1] + ", premium=" + attrs[2]);
        riderList.add(attrs);
      }
    }

    assertEquals("should have 3 riders", 3, riderList.size());
    assertEquals("first rider code", "R001", String.valueOf(riderList.get(0)[0]));
    assertEquals("first rider name", "Critical Illness", String.valueOf(riderList.get(0)[1]));
    assertEquals("second rider code", "R002", String.valueOf(riderList.get(1)[0]));
    assertEquals("third rider code", "R003", String.valueOf(riderList.get(2)[0]));

    cs.close();
  }
}
