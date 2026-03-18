/*
 * Portions Copyright (c) 2023, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import com.aliyun.polardb2.core.Oid;
import com.aliyun.polardb2.jdbc.PgArray;
import com.aliyun.polardb2.jdbc.PgConnection;
import com.aliyun.polardb2.test.TestUtil;
import com.aliyun.polardb2.util.ByteConverter;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;

public class NumericIntComp {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("bigintAsNumeric", "true");
    conn = TestUtil.openDB(props);
    TestUtil.execute(conn, "CREATE or replace PROCEDURE test_procedure(a IN number, b INOUT int, c OUT number) AS \n"
        + "DECLARE\n"
        + "userid int;\n"
        + "BEGIN\n"
        + "  \tc = a + b;\n"
        + "    b = a;\n"
        + "END;");

    conn = TestUtil.openDB(props);
    TestUtil.execute(conn, "CREATE or replace PROCEDURE test_procedure2(a inout char, b inout varchar) AS \n"
        + "DECLARE\n"
        + "userid int;\n"
        + "BEGIN\n"
        + "  a = 'xxx' || a;\n"
        + "  b = 'yyyy' || b;\n"
        + "END;");
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.execute(conn, "drop procedure test_procedure;");
    TestUtil.execute(conn, "drop procedure test_procedure2;");
    Statement st = conn.createStatement();
    try {
      st.execute("DROP TABLE IF EXISTS t_bigint_numeric");
    } catch (Exception ignored) {
      // ignore
    }
    st.close();
  }

  // ================================================================
  // 复现: bigintAsNumeric=true 时，读取 bigint[] 数组的 getResultSet() 触发 StackOverflowError
  //
  // 触发路径:
  //   PgArray(fieldBytes != null).getResultSet()
  //     -> readBinaryResultSet() -> 元素列设为 BINARY_FORMAT + elementOid=INT8
  //       -> arrRS.getObject(2) -> internalGetObject(BIGINT)
  //         -> bigintAsNumeric=true -> getNumeric()
  //           -> isBinary=true, sqlType=BIGINT != NUMERIC -> internalGetObject(BIGINT)
  //             -> 无限递归 -> StackOverflowError
  // ================================================================
  @Test
  public void testBigintAsNumericBinaryArrayStackOverflow() throws Exception {
    // 手动构造符合 PostgreSQL binary array 格式的 bigint[] 数据
    // 格式: [4B ndims][4B flags][4B elementOid][4B dim0_len][4B lbound]
    //       + 每个元素: [4B elemLen][8B int8_value]
    long[] values = { 9876543210L, 1111111111L };
    int n = values.length;
    // header: 5 * 4 = 20 bytes; 每个元素: 4(len) + 8(value) = 12 bytes
    byte[] fieldBytes = new byte[20 + 12 * n];
    ByteConverter.int4(fieldBytes, 0, 1);           // ndims = 1
    ByteConverter.int4(fieldBytes, 4, 0);           // flags = 0 (no nulls)
    ByteConverter.int4(fieldBytes, 8, Oid.INT8);    // elementOid = INT8
    ByteConverter.int4(fieldBytes, 12, n);          // dim[0] size
    ByteConverter.int4(fieldBytes, 16, 1);          // lbound = 1
    for (int i = 0; i < n; i++) {
      int off = 20 + i * 12;
      ByteConverter.int4(fieldBytes, off, 8);       // element byte length = 8
      ByteConverter.int8(fieldBytes, off + 4, values[i]);
    }

    // 用 fieldBytes 构造 PgArray，这样 getResultSet() 会走 readBinaryResultSet() 路径
    PgConnection pgConn = conn.unwrap(PgConnection.class);
    PgArray array = new PgArray(pgConn, Oid.INT8_ARRAY, fieldBytes);

    // array.getResultSet() -> readBinaryResultSet() -> 元素列 BINARY_FORMAT + OID=INT8
    // arrRS.getObject(2):
    //   internalGetObject(BIGINT, bigintAsNumeric=true) -> getNumeric()
    //     -> isBinary=true, sqlType=BIGINT != NUMERIC -> internalGetObject(BIGINT)
    //       -> 无限递归 -> StackOverflowError
    ResultSet arrRS = array.getResultSet();
    Assert.assertTrue(arrRS.next());
    Object val = arrRS.getObject(2); // VALUE 列, 预期触发 StackOverflowError
    Assert.assertNotNull(val);
    Assert.assertTrue("should be BigDecimal", val instanceof BigDecimal);
    Assert.assertEquals(new BigDecimal("9876543210"), val);
    arrRS.close();
  }

  @Test
  public void testGetColumns1() throws Exception {
    CallableStatement ps = conn.prepareCall("call test_procedure(?,?,?)");
    ps.setInt(1, 1);
    ps.setInt(2, 2);
    ps.registerOutParameter(2, Types.NUMERIC);
    ps.registerOutParameter(3, Types.NUMERIC);
    ps.execute();

    Assert.assertEquals(1, ps.getInt(2));
    Assert.assertEquals(3, ps.getInt(3));
  }

  @Test
  public void testGetColumns2() throws Exception {
    CallableStatement ps = conn.prepareCall("call test_procedure(?,?,?)");
    ps.setInt(1, 1);
    ps.setInt(2, 2);
    ps.registerOutParameter(2, Types.INTEGER);
    ps.registerOutParameter(3, Types.INTEGER);
    ps.execute();

    Assert.assertEquals(1, ps.getInt(2));
    Assert.assertEquals(3, ps.getInt(3));
  }

  @Test
  public void testGetColumns3() throws Exception {
    CallableStatement ps = conn.prepareCall("call test_procedure2(?,?)");
    ps.setString(1, "aaa");
    ps.setString(2, "bbbb");
    ps.registerOutParameter(1, Types.VARCHAR);
    ps.registerOutParameter(2, Types.VARCHAR);
    ps.execute();

    Assert.assertEquals("xxxaaa", ps.getString(1));
    Assert.assertEquals("yyyybbbb", ps.getString(2));
  }

  @Test
  public void testGetColumns4() throws Exception {
    CallableStatement ps = conn.prepareCall("call test_procedure2(?,?)");
    ps.setString(1, "aaa");
    ps.setString(2, "bbbb");
    ps.registerOutParameter(1, Types.CHAR);
    ps.registerOutParameter(2, Types.CHAR);
    ps.execute();

    Assert.assertEquals("xxxaaa", ps.getString(1));
    Assert.assertEquals("yyyybbbb", ps.getString(2));
  }

}
