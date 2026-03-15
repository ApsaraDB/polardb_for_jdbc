/*
 * 模拟 DataAccessRepositoryImpl 在 PolarDB 中的运行
 * 参考原始代码中 createStruct / createArrayOf / CallableStatement 的用法
 * 使用 build.local.properties 中的连接信息
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.PGConnection;
import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Ref;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * 模拟 DataAccessRepositoryImpl.executeDynamicSP 在 PolarDB 环境下的完整流程。
 *
 * <p>测试场景：
 * 1. 简单 IN/OUT 参数调用存储过程
 * 2. STRUCT 类型 IN 参数
 * 3. ARRAY of STRUCT 类型 IN 参数 + ARRAY OUT 参数
 */
public class DataAccessSimulationTest {

  private Connection conn;
  private PGConnection pgConn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    conn = TestUtil.openDB(props);
    pgConn = conn.unwrap(PGConnection.class);

    Statement stmt = conn.createStatement();

    // ========== 清理旧对象（如果存在） ==========
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

    // ========== 包1: 简单 IN/OUT 参数 ==========
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

    // ========== 包2: STRUCT IN 参数 ==========
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
    stmt.execute("CREATE OR REPLACE TYPE NB_RIDER_ARRAY AS TABLE OF NB_RIDER_TYPE");
    stmt.execute("CREATE OR REPLACE TYPE NB_RESULT_ARRAY AS TABLE OF NB_RESULT_TYPE");

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

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    if (conn == null) {
      return;
    }
    Statement stmt = conn.createStatement();
    stmt.execute("DROP PACKAGE IF EXISTS NB_RIDER_PKG");
    stmt.execute("DROP PACKAGE IF EXISTS NB_UNDERWRITING_PKG");
    stmt.execute("DROP PACKAGE IF EXISTS NB_POLICY_PKG");
    stmt.execute("DROP TYPE IF EXISTS NB_RESULT_ARRAY");
    stmt.execute("DROP TYPE IF EXISTS NB_RIDER_ARRAY");
    stmt.execute("DROP TYPE IF EXISTS NB_RESULT_TYPE");
    stmt.execute("DROP TYPE IF EXISTS NB_RIDER_TYPE");
    stmt.execute("DROP TYPE IF EXISTS NB_POLICY_TYPE");
    stmt.close();
    conn.close();
  }

  // ================================================================
  // 测试1: 简单 IN/OUT 参数 — 模拟 executeDynamicSP 基本路径
  // ================================================================
  @Test
  public void testSimpleInOutParams() throws Exception {
    System.out.println("=== 测试1: 简单 IN/OUT 参数 ===");

    CallableStatement cs = conn.prepareCall(
        "{ call NB_POLICY_PKG.PROCESS_POLICY(?, ?, ?, ?) }");

    cs.setString(1, "POL001");
    cs.setBigDecimal(2, new java.math.BigDecimal("1000.50"));

    cs.registerOutParameter(3, Types.VARCHAR);
    cs.registerOutParameter(4, Types.NUMERIC);

    cs.execute();

    String result = cs.getString(3);
    int statusCode = cs.getInt(4);

    System.out.println("  p_result      = " + result);
    System.out.println("  p_status_code = " + statusCode);

    assertNotNull("result should not be null", result);
    assertTrue("result should contain policy no", result.contains("POL001"));
    assertTrue("status code should be 0", statusCode == 0);

    cs.close();
  }

  // ================================================================
  // 测试2: STRUCT IN 参数 — 模拟 executeDynamicSP 中 createStruct 路径
  // ================================================================
  @Test
  public void testStructInParam() throws Exception {
    System.out.println("=== 测试2: STRUCT IN 参数 ===");

    Object[] attributes = new Object[]{
        "POL001", "John Doe", "2025-01-01", new java.math.BigDecimal("1000.50")
    };
    Struct structObject = conn.createStruct("NB_POLICY_TYPE", attributes);

    CallableStatement cs = conn.prepareCall(
        "{ call NB_UNDERWRITING_PKG.SUBMIT_POLICY(?, ?) }");

    cs.setObject(1, structObject);
    cs.registerOutParameter(2, Types.VARCHAR);

    cs.execute();

    String resultCode = cs.getString(2);
    System.out.println("  p_result_code = " + resultCode);

    assertNotNull("result code should not be null", resultCode);
    assertTrue("result should contain OK", resultCode.contains("OK-"));

    cs.close();
  }

  // ================================================================
  // 测试3: ARRAY of STRUCT — 模拟 executeDynamicSP 中 createArrayOf 路径
  // ================================================================
  @Test
  public void testArrayOfStructInOutParam() throws Exception {
    System.out.println("=== 测试3: ARRAY of STRUCT IN/OUT 参数 ===");

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

    cs.setObject(1, riderArray);
    cs.setString(2, "POL001");
    cs.registerOutParameter(3, Types.ARRAY);
    cs.registerOutParameter(4, Types.VARCHAR);

    cs.execute();

    String status = cs.getString(4);
    System.out.println("  p_status = " + status);

    Object outResult = cs.getObject(3);
    if (outResult instanceof Array) {
      Object[] objArray = (Object[]) ((Array) outResult).getArray();
      List<Object> objList = new ArrayList<>();
      for (int i = 0; i < objArray.length; i++) {
        if (!Objects.isNull(objArray[i])) {
          if (objArray[i] instanceof Struct) {
            Object[] attrs = ((Struct) objArray[i]).getAttributes();
            System.out.println("  result[" + i + "] = rider_code=" + attrs[0]
                + ", status=" + attrs[1] + ", message=" + attrs[2]);
            objList.add(attrs);
          } else {
            objList.add(objArray[i].toString());
          }
        }
      }
      assertTrue("should have 2 results", objList.size() == 2);
    }

    assertNotNull("status should not be null", status);
    assertTrue("status should be ALL_SUCCESS", "ALL_SUCCESS".equals(status));

    cs.close();
  }

  // ================================================================
  // 测试4: 完整模拟 executeDynamicSP 的结果处理逻辑
  // 包括 Array / Struct / Ref 的 instanceof 分支
  // ================================================================
  @Test
  public void testResultMapProcessing() throws Exception {
    System.out.println("=== 测试4: 模拟 executeDynamicSP 结果处理 ===");

    CallableStatement cs = conn.prepareCall(
        "{ call NB_POLICY_PKG.PROCESS_POLICY(?, ?, ?, ?) }");
    cs.setString(1, "POL999");
    cs.setBigDecimal(2, new java.math.BigDecimal("5000.00"));
    cs.registerOutParameter(3, Types.VARCHAR);
    cs.registerOutParameter(4, Types.NUMERIC);
    cs.execute();

    Map<String, Object> tempResultMap = new HashMap<>();
    tempResultMap.put("p_result", cs.getString(3));
    tempResultMap.put("p_status_code", cs.getBigDecimal(4));

    Map<String, Object> resultMap = new HashMap<>();
    for (Map.Entry<String, Object> entry : tempResultMap.entrySet()) {
      if (entry.getValue() instanceof Array) {
        try {
          Object[] objArray = (Object[]) ((Array) entry.getValue()).getArray();
          List<Object> objList = new ArrayList<>();
          for (int i = 0; i < objArray.length; i++) {
            if (!Objects.isNull(objArray[i])) {
              if (objArray[i] instanceof Struct) {
                objList.add(((Struct) objArray[i]).getAttributes());
              } else {
                objList.add(objArray[i].toString());
              }
            }
          }
          resultMap.put(entry.getKey(), objList);
        } catch (SQLException e) {
          resultMap.put(entry.getKey(), null);
          e.printStackTrace();
        }
      } else if (entry.getValue() instanceof Ref) {
        try {
          Struct structObj = (Struct) ((Ref) entry.getValue()).getObject();
          resultMap.put(entry.getKey(), structObj.getAttributes());
        } catch (SQLException e) {
          resultMap.put(entry.getKey(), null);
          e.printStackTrace();
        }
      } else if (entry.getValue() instanceof Struct) {
        try {
          resultMap.put(entry.getKey(), ((Struct) entry.getValue()).getAttributes());
        } catch (SQLException e) {
          resultMap.put(entry.getKey(), null);
          e.printStackTrace();
        }
      } else {
        resultMap.put(entry.getKey(), entry.getValue());
      }
    }

    for (Map.Entry<String, Object> entry : resultMap.entrySet()) {
      System.out.println("  " + entry.getKey() + " = " + entry.getValue());
    }

    assertNotNull("resultMap should not be null", resultMap);
    assertTrue("should have p_result", resultMap.containsKey("p_result"));
    assertTrue("should have p_status_code", resultMap.containsKey("p_status_code"));

    cs.close();
  }
}
