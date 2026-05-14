/*
 * Portions Copyright (c) 2025, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;

/**
 * 复现客户场景:
 *
 * <pre>
 * Stored procedure CIS.create_person(
 *     ...,
 *     o_client_id    IN OUT NUMBER,   -- 第 26 个参数, INOUT
 *     o_return_code  OUT    NUMBER    -- 第 27 个参数, OUT
 * );
 *
 * // 应用代码 (CustomerCreationServiceImpl.addSPParam)
 * call.setInt(26, entity.getClientId());
 * // call.registerOutParameter(26, Types.INTEGER);   // <-- 被注释掉, 漏注册 OUT
 * call.registerOutParameter(27, Types.VARCHAR);
 *
 * call.execute();
 *
 * // 报错:
 * // com.aliyun.polardb2.util.PSQLException:
 * //   A CallableStatement was executed with an invalid number of parameters
 * //   at com.aliyun.polardb2.jdbc.PgCallableStatement.executeWithFlags(...)
 * </pre>
 *
 * <p>根因: 存储过程的 {@code IN OUT} 参数在服务端会作为 OUT 列返回到结果集,
 * 因此 {@code cols} 包含该 INOUT 参数; 而客户端 {@code outParameterCount}
 * 仅统计 {@code registerOutParameter} 注册过的参数, 所以
 * {@code cols > outParameterCount}, 触发
 * {@link com.aliyun.polardb2.jdbc.PgCallableStatement} 中
 * "A CallableStatement was executed with an invalid number of parameters" 校验。
 *
 * <p>本测试目标: <b>仅复现错误</b>, 暂不修复。修复目标可视为: 当 INOUT
 * 参数仅被 {@code setXxx} 设置 (IN) 而未 {@code registerOutParameter}
 * 时, 驱动应将其视作 OUT 兼容地接收, 而非抛 invalid-number 错误。
 */
public class InoutParamMissingRegisterOutTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();
    /*
     * 最小复现: 一个 INOUT NUMBER 参数 + 一个 OUT VARCHAR2 参数。
     * 这样调用方若只 setInt(1) 而漏掉 registerOutParameter(1, INTEGER),
     * cols=2 (INOUT 在服务端按 OUT 返回 + OUT) 而 outParameterCount=1,
     * 即触发 "invalid number of parameters" 校验。
     */
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE inout_missing_register(\n"
            + "  p_inout IN OUT NUMBER,\n"
            + "  p_msg   OUT    VARCHAR2\n"
            + ") IS\n"
            + "BEGIN\n"
            + "  p_inout := p_inout + 100;\n"
            + "  p_msg   := 'inout=' || p_inout;\n"
            + "END;");
    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    if (conn != null) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP PROCEDURE IF EXISTS inout_missing_register");
      } catch (SQLException ignore) {
        // ignore tear-down failures
      }
      conn.close();
    }
  }

  /**
   * 修复后回归测试: 对 INOUT 参数只 setInt (IN), 未 registerOutParameter (OUT),
   * 驱动应容错地执行而不抛 "invalid number of parameters"。
   * 已注册的其它 OUT 参数仍可正常取值。
   */
  @Test
  public void reproduceInoutMissingRegisterOut() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ call inout_missing_register(?, ?) }")) {
      // 第 1 个参数是 INOUT, 应用代码只 setInt 没 registerOutParameter
      cs.setInt(1, 1);
      // 故意不调用: cs.registerOutParameter(1, Types.INTEGER);

      // 第 2 个参数是 OUT, 正常注册
      cs.registerOutParameter(2, Types.VARCHAR);

      // POLAR: 修复后应正常执行, 不再抛 "invalid number of parameters"
      cs.execute();
      String msg = cs.getString(2);
      System.out.println("[DIAGNOSTIC] (after fix) p_msg = " + msg);
      assertNotNull(msg);
      assertEquals("inout=101", msg);
    }
  }

  /**
   * 对照组: 正确 registerOutParameter 后, 应能正常返回 INOUT/OUT 值。
   */
  @Test
  public void controlGroupBothRegistered() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ call inout_missing_register(?, ?) }")) {
      cs.setInt(1, 1);
      cs.registerOutParameter(1, Types.INTEGER);
      cs.registerOutParameter(2, Types.VARCHAR);

      cs.execute();
      int inoutVal = cs.getInt(1);
      String msg = cs.getString(2);
      System.out.println("[DIAGNOSTIC] control p_inout = " + inoutVal
          + ", p_msg = " + msg);
      assertNotNull(msg);
    }
  }
}
