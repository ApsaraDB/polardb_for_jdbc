/*
 * Portions Copyright (c) 2023, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.Types;
import java.util.Properties;

public class CallFunction {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);
    TestUtil.execute(conn, "Create Or Replace Function Test_Jdbc_Func (\n"
        + "    piStoreGid  In      INT,      \n"
        + "    poErr_Msg   Out     Varchar2  \n"
        + "  ) Return Number\n"
        + "  Is \n"
        + "  Begin \n"
        + "    poErr_Msg := '1';\n"
        + "    return 1;\n"
        + "  End;");

    TestUtil.execute(conn, "create Or Replace procedure abc1(a int, b int) as\n"
        + "begin\n"
        + "\traise notice '11';\n"
        + "end;");

    TestUtil.execute(conn, "create Or Replace function abc2(a int, b int) return int as\n"
        + "begin\n"
        + "return a + b;\n"
        + "end;");

    TestUtil.execute(conn, "create Or Replace procedure abc3(a int, b out int) as\n"
        + "begin\n"
        + "b=3;\n"
        + "raise notice '1';\n"
        + "end;");

    TestUtil.execute(conn, "CREATE OR REPLACE Procedure Test_Proc (\n"
        + "  piBillTo         In          Int,\n"
        + "  piOcrDate        In          Date,\n"
        + "  piFilDate        In          Date,\n"
        + "  piChkDate        In          Date,\n"
        + "  piIvcEndDate     In          Date\n"
        + ") Is\n"
        + "Begin\n"
        + "  Null;\n"
        + "End;\n");
    TestUtil.execute(conn, "CREATE OR REPLACE PROCEDURE test_in_out_procedure (a IN number, b IN OUT number, c OUT number) IS\n"
        + "BEGIN\n"
        + "\tb := a + b;\n"
        + "\tc := b + 1;\n"
        + "END;");
    TestUtil.execute(conn, "CREATE OR REPLACE FUNCTION test_in_out_function (a IN number, b IN OUT number, c OUT number) RETURN number AS\n"
        + "BEGIN\n"
        + "\tb := a + b;\n"
        + "\tc := b + 1;\n"
        + "\n"
        + "\tRETURN c + 1;\n"
        + "END;");
    TestUtil.execute(conn, "CREATE OR REPLACE FUNCTION test_in_out_function2 (a IN number, b IN OUT number, c OUT number) RETURN number AS\n"
        + "BEGIN\n"
        + "\tb := a + b;\n"
        + "\tc := b + 1;\n"
        + "\n"
        + "\tRETURN null;\n"
        + "END;");
    TestUtil.execute(conn, "CREATE OR REPLACE PROCEDURE test_in_out_function_as_procedure_1 (a IN number, b IN OUT number, c OUT number, r OUT number) AS\n"
        + "BEGIN\n"
        + "\tr := test_in_out_function(a, b, c);\n"
        + "END;");
    TestUtil.execute(conn, "CREATE OR REPLACE PROCEDURE test_in_out_function_as_procedure_2 (a IN number, b IN OUT number, c OUT number, r OUT number) AS\n"
        + "BEGIN\n"
        + "\tSELECT test_in_out_function(a, b, c) INTO r from dual;\n"
        + "END;\n");
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.execute(conn, "DROP Function Test_Jdbc_Func;");
    TestUtil.execute(conn, "DROP procedure abc1;");
    TestUtil.execute(conn, "DROP Function abc2;");
    TestUtil.execute(conn, "DROP procedure abc3;");
    TestUtil.execute(conn, "DROP procedure Test_Proc;");
  }

  @Test
  public void testGetColumns1() throws Exception {

    String callString = "{ ? = call Test_Jdbc_Func(?, ?) }";
    try (CallableStatement cstmt = conn.prepareCall(callString)) {
      cstmt.registerOutParameter(1, Types.NUMERIC);
      // 设置输入参数
      cstmt.setInt(2, Types.INTEGER);

      // 注册输出参数
      cstmt.registerOutParameter(3, Types.VARCHAR);

      // 执行存储过程
      cstmt.executeUpdate();

      // 输出结果
      System.out.println("v_ret: " + cstmt.getInt(1));
      System.out.println("v_ret3: " + cstmt.getString(3));
    }
  }

  @Test
  public void testGetColumns2() throws Exception {

    String callString = "{ ? = call abc2(?, ?) }";
    try (CallableStatement cstmt = conn.prepareCall(callString)) {
      cstmt.registerOutParameter(1, Types.NUMERIC);
      // 设置输入参数
      cstmt.setObject(2, 2);

      // 注册输出参数
      cstmt.setObject(3, 1);

      // 执行存储过程
      cstmt.executeUpdate();

      // 输出结果
      System.out.println("v_ret: " + cstmt.getInt(1));
    }
  }

  @Test
  public void testGetColumns3() throws Exception {

    String callString = "{call abc1(?, ?) }";
    try (CallableStatement cstmt = conn.prepareCall(callString)) {
      // 设置输入参数
      cstmt.setObject(1, 1);
      // 设置输入参数
      cstmt.setObject(2, 1);

      // 执行存储过程
      cstmt.executeUpdate();
    }
  }

  @Test
  public void testGetColumns4() throws Exception {

    String callString = "{ ? = call abc2(?, ?) }";
    try (CallableStatement cstmt = conn.prepareCall(callString)) {
      cstmt.registerOutParameter(1, Types.NUMERIC);
      // 设置输入参数
      cstmt.setInt(2, 2);

      // 注册输出参数
      cstmt.setInt(3, 1);

      // 执行存储过程
      cstmt.executeUpdate();

      // 输出结果
      System.out.println("v_ret: " + cstmt.getInt(1));
    }
  }

  @Test
  public void testGetColumns5() throws Exception {

    String callString = "{call abc3(?, ?) }";
    try (CallableStatement cstmt = conn.prepareCall(callString)) {
      // 设置输入参数
      cstmt.setObject(1, 1);
      // 设置输入参数
      cstmt.registerOutParameter(2, Types.NUMERIC);

      // 执行存储过程
      cstmt.executeUpdate();

      System.out.println("v_ret: " + cstmt.getInt(2));
    }
  }

  @Test
  public void testGetColumns6() throws Exception {
    String sql = "{call Test_Proc(?, ?, ?, ?, ?) }";
    CallableStatement pstmt = conn.prepareCall(sql);
    pstmt.setObject(1, 7000031);
    pstmt.setObject(2, null);
    pstmt.setObject(3, null);
    pstmt.setObject(4, null);
    pstmt.setObject(5, null);
    // 打印字符串长度，检查是否合理
    System.out.println("SQL length: " + sql.length());

    // 执行 SQL
    pstmt.executeUpdate();
  }

  @Test
  public void test_in_out_procedure() throws Exception {
    CallableStatement callableStatement = null;
    int a = 1;
    int b = 2;
    int c = 0;
    System.out.println(String.format("BEFORE test_in_out_procedure: a=%d, b=%d, c=%d", a, b, c));

    callableStatement = conn.prepareCall("{call test_in_out_procedure (?, ?, ?)}");
    // a
    callableStatement.setInt(1, a);
    // b
    callableStatement.setInt(2, b);
    callableStatement.registerOutParameter(2, java.sql.Types.INTEGER);
    // c
    callableStatement.registerOutParameter(3, java.sql.Types.INTEGER);
    callableStatement.execute();
    b = callableStatement.getInt(2);
    c = callableStatement.getInt(3);

    System.out.println(String.format("AFTER test_in_out_procedure: a=%d, b=%d, c=%d", a, b, c));
    System.out.println();
  }

  @Test
  public void test_in_out_procedure2() throws Exception {
    CallableStatement callableStatement = null;
    int a = 1;
    int b = 2;
    int c = 0;
    System.out.println(String.format("BEFORE test_in_out_procedure: a=%d, b=%d, c=%d", a, b, c));

    callableStatement = conn.prepareCall("BEGIN test_in_out_procedure (?, ?, ?); END;");
    // a
    callableStatement.setInt(1, a);
    // b
    callableStatement.setInt(2, b);
    callableStatement.registerOutParameter(2, java.sql.Types.INTEGER);
    // c
    callableStatement.registerOutParameter(3, JDBCType.INTEGER);
    callableStatement.execute();
    b = callableStatement.getInt(2);
    c = callableStatement.getInt(3);

    System.out.println(String.format("AFTER test_in_out_procedure: a=%d, b=%d, c=%d", a, b, c));
    System.out.println();
  }

  @Test
  public void test_in_out_function() throws Exception {
    CallableStatement callableStatement = null;

    int r = 0;
    int a = 1;
    int b = 2;
    int c = 0;

    System.out.println(String.format("BEFORE test_in_out_function: r=%d, a=%d, b=%d, c=%d", r, a, b, c));

    callableStatement = conn.prepareCall("{ ?= call test_in_out_function (?, ?, ?)}");

    // r
    callableStatement.registerOutParameter(1, java.sql.Types.INTEGER);

    // a
    callableStatement.setInt(2, a);

    // b
    callableStatement.setInt(3, b);
    callableStatement.registerOutParameter(3, java.sql.Types.INTEGER);

    // c
    callableStatement.registerOutParameter(4, java.sql.Types.INTEGER);

    callableStatement.execute();

    r = callableStatement.getInt(1);
    b = callableStatement.getInt(3);
    c = callableStatement.getInt(4);

    System.out.println(String.format("AFTER test_in_out_function: r=%d, a=%d, b=%d, c=%d", r, a, b, c));
    System.out.println();
  }

  @Test
  public void test_in_out_function2() throws Exception {
    CallableStatement callableStatement = null;

    int r = 0;
    int a = 1;
    int b = 2;
    int c = 0;

    System.out.println(String.format("BEFORE test_in_out_function2: r=%d, a=%d, b=%d, c=%d", r, a, b, c));

    callableStatement = conn.prepareCall("{ ?= call test_in_out_function2 (?, ?, ?)}");

    // r
    callableStatement.registerOutParameter(1, java.sql.Types.INTEGER);

    // a
    callableStatement.setInt(2, a);

    // b
    callableStatement.setInt(3, b);
    callableStatement.registerOutParameter(3, java.sql.Types.INTEGER);

    // c
    callableStatement.registerOutParameter(4, java.sql.Types.INTEGER);

    callableStatement.execute();

    r = callableStatement.getInt(1);
    b = callableStatement.getInt(3);
    c = callableStatement.getInt(4);

    System.out.println(String.format("AFTER test_in_out_function: r=%d, a=%d, b=%d, c=%d", r, a, b, c));
    System.out.println();
  }

  @Test
  public void test_in_out_function_as_anonymous_block() throws Exception {
    CallableStatement callableStatement = null;

    int r = 0;
    int a = 1;
    int b = 2;
    int c = 0;

    System.out.println(String.format("BEFORE test_in_out_function: r=%d, a=%d, b=%d, c=%d", r, a, b, c));

    callableStatement = conn.prepareCall("BEGIN ? := test_in_out_function (?, ?, ?); END;");

    // r
    callableStatement.registerOutParameter(1, java.sql.Types.INTEGER);

    // a
    callableStatement.setInt(2, a);

    // b
    callableStatement.setInt(3, b);
    callableStatement.registerOutParameter(3, java.sql.Types.INTEGER);

    // c
    callableStatement.registerOutParameter(4, java.sql.Types.INTEGER);

    callableStatement.execute();

    r = callableStatement.getInt(1);
    b = callableStatement.getInt(3);
    c = callableStatement.getInt(4);

    System.out.println(String.format("AFTER test_in_out_function: r=%d, a=%d, b=%d, c=%d", r, a, b, c));
    System.out.println();
  }

  @Test
  public void test_in_out_function_as_procedure_1() throws Exception {
    CallableStatement callableStatement = null;

    int r = 0;
    int a = 1;
    int b = 2;
    int c = 0;

    System.out.println(String.format("BEFORE proc_test_in_out_function: r=%d, a=%d, b=%d, c=%d", r, a, b, c));

    callableStatement = conn.prepareCall("{call test_in_out_function_as_procedure_1 (?, ?, ?, ?)}");

    // a
    callableStatement.setInt(1, a);

    // b
    callableStatement.setInt(2, b);
    callableStatement.registerOutParameter(2, java.sql.Types.INTEGER);

    // c
    callableStatement.registerOutParameter(3, java.sql.Types.INTEGER);

    // r
    callableStatement.registerOutParameter(4, java.sql.Types.INTEGER);

    callableStatement.execute();

    b = callableStatement.getInt(2);
    c = callableStatement.getInt(3);

    r = callableStatement.getInt(4);

    System.out.println(String.format("AFTER proc_test_in_out_function: r=%d, a=%d, b=%d, c=%d", r, a, b, c));
    System.out.println();
  }

  @Test
  public void test_in_out_function_as_procedure_2() throws Exception {
    CallableStatement callableStatement = null;

    int r = 0;
    int a = 1;
    int b = 2;
    int c = 0;

    System.out.println(String.format("BEFORE proc_test_in_out_function: r=%d, a=%d, b=%d, c=%d", r, a, b, c));

    callableStatement = conn.prepareCall("{call test_in_out_function_as_procedure_2 (?, ?, ?, ?)}");

    // a
    callableStatement.setInt(1, a);

    // b
    callableStatement.setInt(2, b);
    callableStatement.registerOutParameter(2, java.sql.Types.INTEGER);

    // c
    callableStatement.registerOutParameter(3, java.sql.Types.INTEGER);

    // r
    callableStatement.registerOutParameter(4, java.sql.Types.INTEGER);

    callableStatement.execute();

    b = callableStatement.getInt(2);
    c = callableStatement.getInt(3);

    r = callableStatement.getInt(4);

    System.out.println(String.format("AFTER proc_test_in_out_function: r=%d, a=%d, b=%d, c=%d", r, a, b, c));
    System.out.println();
  }

  // ==================== DO Anonymous Block Tests ====================

  /**
   * POLAR: Test basic DO block with ? = assignment (no :=).
   * Verifies: constant assignment returns correct values.
   */
  @Test
  public void testDoBlockBasicAssign() throws Exception {
    try (CallableStatement cs = conn.prepareCall("begin ? = 1; ? = 'xxx'; end;")) {
      cs.registerOutParameter(1, Types.NUMERIC);
      cs.registerOutParameter(2, Types.VARCHAR);
      cs.execute();

      assert cs.getInt(1) == 1 : "Expected 1 but got " + cs.getObject(1);
      assert "xxx".equals(cs.getString(2)) : "Expected 'xxx' but got " + cs.getObject(2);
    }
  }

  /**
   * POLAR: Test DO block with partial register - only register param 2.
   * Verifies: unregistered params auto-null, registered params return values.
   */
  @Test
  public void testDoBlockPartialRegister() throws Exception {
    try (CallableStatement cs = conn.prepareCall("begin ? = 100; ? = 'test'; end;")) {
      cs.registerOutParameter(2, Types.VARCHAR);
      cs.execute();

      assert "test".equals(cs.getString(2)) : "Expected 'test' but got " + cs.getObject(2);
    }
  }

  /**
   * POLAR: Test DO block with := assignment and input values.
   * Verifies: input params are used, output params return computed values.
   */
  @Test
  public void testDoBlockWithInputValues() throws Exception {
    try (CallableStatement cs = conn.prepareCall("begin ? := ? * 2; ? := ? || '_suffix'; end;")) {
      cs.setInt(2, 21);
      cs.setString(4, "hello");
      cs.registerOutParameter(1, Types.NUMERIC);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();

      assert cs.getInt(1) == 42 : "Expected 42 but got " + cs.getObject(1);
      assert "hello_suffix".equals(cs.getString(3)) : "Expected 'hello_suffix' but got " + cs.getObject(3);
    }
  }

  /**
   * POLAR: Test DO block calling internal function.
   * Verifies: function calls within DO block work correctly.
   */
  @Test
  public void testDoBlockCallInternalFunction() throws Exception {
    // Create test function
    TestUtil.execute(conn, "CREATE OR REPLACE FUNCTION do_test_add(a int, b int) RETURN int AS BEGIN RETURN a + b; END;");
    try {
      try (CallableStatement cs = conn.prepareCall("begin ? := do_test_add(?, ?); end;")) {
        cs.setInt(2, 10);
        cs.setInt(3, 20);
        cs.registerOutParameter(1, Types.NUMERIC);
        cs.execute();

        assert cs.getInt(1) == 30 : "Expected 30 but got " + cs.getObject(1);
      }
    } finally {
      TestUtil.execute(conn, "DROP FUNCTION IF EXISTS do_test_add;");
    }
  }

  /**
   * POLAR: Test DO block calling internal procedure with OUT param.
   * Verifies: procedure calls within DO block work correctly.
   */
  @Test
  public void testDoBlockCallInternalProcedure() throws Exception {
    // Create test procedure
    TestUtil.execute(conn, "CREATE OR REPLACE PROCEDURE do_test_proc(a int, b out int) AS BEGIN b := a * 3; END;");
    try {
      try (CallableStatement cs = conn.prepareCall("begin do_test_proc(?, ?); end;")) {
        cs.setInt(1, 7);
        cs.registerOutParameter(2, Types.NUMERIC);
        cs.execute();

        assert cs.getInt(2) == 21 : "Expected 21 but got " + cs.getObject(2);
      }
    } finally {
      TestUtil.execute(conn, "DROP PROCEDURE IF EXISTS do_test_proc;");
    }
  }

  /**
   * POLAR: Test DO block with conditional logic using internal function.
   */
  @Test
  public void testDoBlockConditionalWithFunction() throws Exception {
    TestUtil.execute(conn, "CREATE OR REPLACE FUNCTION do_test_is_positive(n int) RETURN boolean AS BEGIN RETURN n > 0; END;");
    try {
      // Positive case
      try (CallableStatement cs = conn.prepareCall(
          "begin if do_test_is_positive(?) then ? := 'POSITIVE'; else ? := 'NON-POSITIVE'; end if; end;")) {
        cs.setInt(1, 5);
        cs.registerOutParameter(2, Types.VARCHAR);
        cs.registerOutParameter(3, Types.VARCHAR);
        cs.execute();

        assert "POSITIVE".equals(cs.getString(2)) : "Expected 'POSITIVE' but got " + cs.getObject(2);
        assert cs.getObject(3) == null : "Expected null for unassigned param 3";
      }

      // Non-positive case
      try (CallableStatement cs = conn.prepareCall(
          "begin if do_test_is_positive(?) then ? := 'POSITIVE'; else ? := 'NON-POSITIVE'; end if; end;")) {
        cs.setInt(1, -3);
        cs.registerOutParameter(2, Types.VARCHAR);
        cs.registerOutParameter(3, Types.VARCHAR);
        cs.execute();

        assert cs.getObject(2) == null : "Expected null for unassigned param 2";
        assert "NON-POSITIVE".equals(cs.getString(3)) : "Expected 'NON-POSITIVE' but got " + cs.getObject(3);
      }
    } finally {
      TestUtil.execute(conn, "DROP FUNCTION IF EXISTS do_test_is_positive;");
    }
  }

  /**
   * POLAR: Test DO block with COALESCE and mixed set/unset params.
   * Verifies: set params used, unset params use default via COALESCE.
   */
  @Test
  public void testDoBlockCoalesceMixed() throws Exception {
    try (CallableStatement cs = conn.prepareCall("begin ? := COALESCE(?, 100); ? := COALESCE(?, 'default'); end;")) {
      cs.setInt(2, 50);
      // param 4 not set - should use default
      cs.registerOutParameter(1, Types.NUMERIC);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();

      assert cs.getInt(1) == 50 : "Expected 50 but got " + cs.getObject(1);
      assert "default".equals(cs.getString(3)) : "Expected 'default' but got " + cs.getObject(3);
    }
  }

  /**
   * POLAR: Test DO block with explicit NULL input.
   * Verifies: explicitly set NULL triggers COALESCE default.
   */
  @Test
  public void testDoBlockExplicitNull() throws Exception {
    try (CallableStatement cs = conn.prepareCall("begin ? := COALESCE(?, 999); end;")) {
      cs.setNull(2, Types.NUMERIC);
      cs.registerOutParameter(1, Types.NUMERIC);
      cs.execute();

      assert cs.getInt(1) == 999 : "Expected 999 but got " + cs.getObject(1);
    }
  }

  /**
   * POLAR: Test DO block with all params unset (pure output).
   * Verifies: auto-null works, constant assignments return correctly.
   */
  @Test
  public void testDoBlockAllUnset() throws Exception {
    try (CallableStatement cs = conn.prepareCall("begin ? := 123; ? := 'abc'; end;")) {
      cs.registerOutParameter(1, Types.NUMERIC);
      cs.registerOutParameter(2, Types.VARCHAR);
      cs.execute();

      assert cs.getInt(1) == 123 : "Expected 123 but got " + cs.getObject(1);
      assert "abc".equals(cs.getString(2)) : "Expected 'abc' but got " + cs.getObject(2);
    }
  }

  /**
   * POLAR: Test DO block with multiple function calls.
   */
  @Test
  public void testDoBlockMultipleFunctionCalls() throws Exception {
    TestUtil.execute(conn, "CREATE OR REPLACE FUNCTION do_test_mul(a int, b int) RETURN int AS BEGIN RETURN a * b; END;");
    TestUtil.execute(conn, "CREATE OR REPLACE FUNCTION do_test_concat(s1 varchar, s2 varchar) RETURN varchar AS BEGIN RETURN s1 || '-' || s2; END;");
    try {
      try (CallableStatement cs = conn.prepareCall(
          "begin ? := do_test_mul(?, ?); ? := do_test_concat(?, ?); end;")) {
        cs.setInt(2, 6);
        cs.setInt(3, 7);
        cs.setString(5, "hello");
        cs.setString(6, "world");
        cs.registerOutParameter(1, Types.NUMERIC);
        cs.registerOutParameter(4, Types.VARCHAR);
        cs.execute();

        assert cs.getInt(1) == 42 : "Expected 42 but got " + cs.getObject(1);
        assert "hello-world".equals(cs.getString(4)) : "Expected 'hello-world' but got " + cs.getObject(4);
      }
    } finally {
      TestUtil.execute(conn, "DROP FUNCTION IF EXISTS do_test_mul;");
      TestUtil.execute(conn, "DROP FUNCTION IF EXISTS do_test_concat;");
    }
  }

  /**
   * POLAR: Test DO block with execute() method.
   */
  @Test
  public void testDoBlockWithExecute() throws Exception {
    try (CallableStatement cs = conn.prepareCall("begin ? := 888; end;")) {
      cs.registerOutParameter(1, Types.NUMERIC);
      boolean hasResultSet = cs.execute();

      assert !hasResultSet : "Expected no result set for DO block";
      assert cs.getInt(1) == 888 : "Expected 888 but got " + cs.getObject(1);
    }
  }

  /**
   * POLAR: Test DO block with executeUpdate() method.
   */
  @Test
  public void testDoBlockWithExecuteUpdate() throws Exception {
    try (CallableStatement cs = conn.prepareCall("begin ? := 777; end;")) {
      cs.registerOutParameter(1, Types.NUMERIC);
      int updateCount = cs.executeUpdate();

      assert updateCount == 0 : "Expected updateCount=0 but got " + updateCount;
      assert cs.getInt(1) == 777 : "Expected 777 but got " + cs.getObject(1);
    }
  }

  /**
   * POLAR: Test DO block with complex nested logic.
   */
  @Test
  public void testDoBlockComplexNested() throws Exception {
    TestUtil.execute(conn, "CREATE OR REPLACE FUNCTION do_test_max(a int, b int) RETURN int AS BEGIN RETURN CASE WHEN a > b THEN a ELSE b END; END;");
    try {
      try (CallableStatement cs = conn.prepareCall(
          "begin "
          + "  ? := do_test_max(?, ?); "
          + "  if ? > 50 then ? := 'big'; else ? := 'small'; end if; "
          + "end;")) {
        cs.setInt(2, 30);
        cs.setInt(3, 20);
        cs.setInt(4, 30);  // condition for if statement
        cs.registerOutParameter(1, Types.NUMERIC);
        cs.registerOutParameter(5, Types.VARCHAR);
        cs.registerOutParameter(6, Types.VARCHAR);
        cs.execute();

        assert cs.getInt(1) == 30 : "Expected 30 but got " + cs.getObject(1);
        assert cs.getObject(5) == null : "Expected null for param 5 (condition was false)";
        assert "small".equals(cs.getString(6)) : "Expected 'small' but got " + cs.getObject(6);
      }
    } finally {
      TestUtil.execute(conn, "DROP FUNCTION IF EXISTS do_test_max;");
    }
  }

  /**
   * POLAR: Test DO block with $N style parameters (already converted).
   */
  @Test
  public void testDoBlockDollarStyle() throws Exception {
    // Note: This tests direct $N usage which might be used in some scenarios
    try (CallableStatement cs = conn.prepareCall("begin ? := 555; ? := 'direct'; end;")) {
      cs.registerOutParameter(1, Types.NUMERIC);
      cs.registerOutParameter(2, Types.VARCHAR);
      cs.execute();

      assert cs.getInt(1) == 555 : "Expected 555 but got " + cs.getObject(1);
      assert "direct".equals(cs.getString(2)) : "Expected 'direct' but got " + cs.getObject(2);
    }
  }

  /**
   * POLAR: Complex DO block with function and procedure having IN/OUT/INOUT parameters.
   * Creates a function with IN/OUT/INOUT params and a procedure with IN/OUT/INOUT params,
   * then calls them within a DO block and verifies all OUT parameter values.
   */
  @Test
  public void testDoBlockComplexInOutParams() throws Exception {
    // Create function: calc_stats(IN base int, INOUT multiplier int, OUT result int, OUT msg varchar)
    TestUtil.execute(conn,
        "CREATE OR REPLACE FUNCTION do_calc_stats("
        + "  base IN int,"
        + "  multiplier INOUT int,"
        + "  result OUT int,"
        + "  msg OUT varchar"
        + ") RETURN varchar AS "
        + "BEGIN "
        + "  result := base * multiplier; "
        + "  multiplier := multiplier + 1; "
        + "  msg := 'Base=' || base || ', Mult=' || (multiplier-1) || ', Result=' || result; "
        + "  RETURN msg; "
        + "END;");

    // Create procedure: update_stats(IN base int, INOUT factor int, OUT total int, OUT status varchar)
    TestUtil.execute(conn,
        "CREATE OR REPLACE PROCEDURE do_update_stats("
        + "  base IN int,"
        + "  factor INOUT int,"
        + "  total OUT int,"
        + "  status OUT varchar"
        + ") AS "
        + "BEGIN "
        + "  total := base + factor; "
        + "  factor := factor * 2; "
        + "  IF total > 100 THEN "
        + "    status := 'HIGH'; "
        + "  ELSE "
        + "    status := 'LOW'; "
        + "  END IF; "
        + "END;");

    try {
      // Test 1: Call function with IN/OUT/INOUT params in DO block
      try (CallableStatement cs = conn.prepareCall(
          "begin "
          + "  ? := do_calc_stats(?, ?, ?, ?); "  // return value + 3 OUT params
          + "end;")) {
        // IN param: base = 10 (param 2)
        cs.setInt(2, 10);
        // INOUT param: multiplier = 5 (param 3), will become 6 after call
        cs.setInt(3, 5);

        // Register outputs
        cs.registerOutParameter(1, Types.VARCHAR);  // function return
        cs.registerOutParameter(3, Types.NUMERIC);  // INOUT multiplier (new value)
        cs.registerOutParameter(4, Types.NUMERIC);  // OUT result
        cs.registerOutParameter(5, Types.VARCHAR);  // OUT msg

        cs.execute();

        // Verify all OUT values
        assert cs.getInt(4) == 50 : "Expected result=50 (10*5) but got " + cs.getObject(4);
        assert cs.getInt(3) == 6 : "Expected multiplier=6 (5+1) but got " + cs.getObject(3);
        assert "Base=10, Mult=5, Result=50".equals(cs.getString(5)) :
            "Expected msg 'Base=10, Mult=5, Result=50' but got " + cs.getObject(5);
        assert "Base=10, Mult=5, Result=50".equals(cs.getString(1)) :
            "Expected return value 'Base=10, Mult=5, Result=50' but got " + cs.getObject(1);
      }

      // Test 2: Call procedure with IN/OUT/INOUT params in DO block
      try (CallableStatement cs = conn.prepareCall(
          "begin "
          + "  do_update_stats(?, ?, ?, ?); "
          + "end;")) {
        // IN param: base = 30 (param 1)
        cs.setInt(1, 30);
        // INOUT param: factor = 40 (param 2), will become 80 after call
        cs.setInt(2, 40);

        // Register outputs
        cs.registerOutParameter(2, Types.NUMERIC);  // INOUT factor (new value)
        cs.registerOutParameter(3, Types.NUMERIC);  // OUT total
        cs.registerOutParameter(4, Types.VARCHAR);  // OUT status

        cs.execute();

        // Verify all OUT values
        assert cs.getInt(3) == 70 : "Expected total=70 (30+40) but got " + cs.getObject(3);
        assert cs.getInt(2) == 80 : "Expected factor=80 (40*2) but got " + cs.getObject(2);
        assert "LOW".equals(cs.getString(4)) : "Expected status='LOW' (70<=100) but got " + cs.getObject(4);
      }

      // Test 3: HIGH status case
      try (CallableStatement cs = conn.prepareCall(
          "begin "
          + "  do_update_stats(?, ?, ?, ?); "
          + "end;")) {
        cs.setInt(1, 80);
        cs.setInt(2, 50);  // 80+50=130 > 100, should be HIGH

        cs.registerOutParameter(2, Types.NUMERIC);
        cs.registerOutParameter(3, Types.NUMERIC);
        cs.registerOutParameter(4, Types.VARCHAR);

        cs.execute();

        assert cs.getInt(3) == 130 : "Expected total=130 but got " + cs.getObject(3);
        assert cs.getInt(2) == 100 : "Expected factor=100 (50*2) but got " + cs.getObject(2);
        assert "HIGH".equals(cs.getString(4)) : "Expected status='HIGH' (130>100) but got " + cs.getObject(4);
      }

      // Test 4: Multiple calls in single DO block
      try (CallableStatement cs = conn.prepareCall(
          "begin "
          + "  ? := do_calc_stats(?, ?, ?, ?); "
          + "  do_update_stats(?, ?, ?, ?); "
          + "end;")) {
        // First call: do_calc_stats(3, 4)
        cs.setInt(2, 3);   // base
        cs.setInt(3, 4);   // multiplier
        // Second call: do_update_stats(10, 20)
        cs.setInt(6, 10);  // base
        cs.setInt(7, 20);  // factor

        // Register all outputs
        cs.registerOutParameter(1, Types.VARCHAR);  // func return
        cs.registerOutParameter(3, Types.NUMERIC);  // func multiplier out
        cs.registerOutParameter(4, Types.NUMERIC);  // func result
        cs.registerOutParameter(5, Types.VARCHAR);  // func msg
        cs.registerOutParameter(7, Types.NUMERIC);  // proc factor out
        cs.registerOutParameter(8, Types.NUMERIC);  // proc total
        cs.registerOutParameter(9, Types.VARCHAR);  // proc status

        cs.execute();

        // Verify function results: 3*4=12, multiplier becomes 5
        assert cs.getInt(4) == 12 : "Expected func result=12 but got " + cs.getObject(4);
        assert cs.getInt(3) == 5 : "Expected func multiplier=5 but got " + cs.getObject(3);

        // Verify procedure results: 10+20=30, factor becomes 40, status=LOW
        assert cs.getInt(8) == 30 : "Expected proc total=30 but got " + cs.getObject(8);
        assert cs.getInt(7) == 40 : "Expected proc factor=40 but got " + cs.getObject(7);
        assert "LOW".equals(cs.getString(9)) : "Expected proc status='LOW' but got " + cs.getObject(9);
      }

    } finally {
      TestUtil.execute(conn, "DROP FUNCTION IF EXISTS do_calc_stats;");
      TestUtil.execute(conn, "DROP PROCEDURE IF EXISTS do_update_stats;");
    }
  }
}
