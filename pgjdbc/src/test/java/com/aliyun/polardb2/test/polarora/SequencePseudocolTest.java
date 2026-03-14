/*
 * Portions Copyright (c) 2023, Alibaba Group Holding Limited
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
import java.sql.Types;
import java.util.Properties;

/**
 * POLAR: Tests for Oracle sequence pseudocolumn support via JDBC escape syntax.
 *
 * <p>Oracle-style sequence access like {@code {? = call seq_name.nextval()}} should be
 * translated to {@code SELECT seq_name.nextval FROM dual} and the result returned
 * as an OUT parameter.
 */
public class SequencePseudocolTest {
  private Connection conn;
  private static final String SEQ_NAME = "test_jdbc_sequence";

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    // Create test sequence starting at 100
    TestUtil.execute(conn, "DROP SEQUENCE IF EXISTS " + SEQ_NAME);
    TestUtil.execute(conn, "CREATE SEQUENCE " + SEQ_NAME + " START WITH 100 INCREMENT BY 1");
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.execute(conn, "DROP SEQUENCE IF EXISTS " + SEQ_NAME);
    TestUtil.closeDB(conn);
  }

  /**
   * Test sequence nextval with empty parentheses: {? = call seq.nextval()}
   */
  @Test
  public void testSequenceNextvalWithParens() throws Exception {
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".nextval()}")) {
      cs.registerOutParameter(1, Types.BIGINT);
      cs.execute();

      long val = cs.getLong(1);
      assertEquals("First nextval should return 100", 100L, val);
    }

    // Call again to verify increment
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".nextval()}")) {
      cs.registerOutParameter(1, Types.BIGINT);
      cs.execute();

      long val = cs.getLong(1);
      assertEquals("Second nextval should return 101", 101L, val);
    }
  }

  /**
   * Test sequence nextval without parentheses: {? = call seq.nextval}
   */
  @Test
  public void testSequenceNextvalWithoutParens() throws Exception {
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".nextval}")) {
      cs.registerOutParameter(1, Types.BIGINT);
      cs.execute();

      long val = cs.getLong(1);
      assertEquals("nextval without parens should return 100", 100L, val);
    }
  }

  /**
   * Test sequence currval: {? = call seq.currval()}
   * Note: currval requires nextval to be called first in the same session.
   */
  @Test
  public void testSequenceCurrval() throws Exception {
    // First call nextval to initialize the sequence in this session
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".nextval()}")) {
      cs.registerOutParameter(1, Types.BIGINT);
      cs.execute();
      assertEquals(100L, cs.getLong(1));
    }

    // Now currval should return the same value
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".currval()}")) {
      cs.registerOutParameter(1, Types.BIGINT);
      cs.execute();

      long val = cs.getLong(1);
      assertEquals("currval should return same value as last nextval", 100L, val);
    }
  }

  /**
   * Test sequence currval without parentheses: {? = call seq.currval}
   */
  @Test
  public void testSequenceCurrvalWithoutParens() throws Exception {
    // First call nextval
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".nextval}")) {
      cs.registerOutParameter(1, Types.BIGINT);
      cs.execute();
    }

    // Now currval without parens
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".currval}")) {
      cs.registerOutParameter(1, Types.BIGINT);
      cs.execute();

      long val = cs.getLong(1);
      assertEquals("currval without parens should return 100", 100L, val);
    }
  }

  /**
   * Test registerOutParameter with Types.NUMERIC
   */
  @Test
  public void testSequenceWithNumericType() throws Exception {
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".nextval()}")) {
      cs.registerOutParameter(1, Types.NUMERIC);
      cs.execute();

      java.math.BigDecimal val = cs.getBigDecimal(1);
      assertNotNull("BigDecimal result should not be null", val);
      assertEquals("Value should be 100", 100, val.intValue());
    }
  }

  /**
   * Test registerOutParameter with Types.INTEGER
   */
  @Test
  public void testSequenceWithIntegerType() throws Exception {
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".nextval()}")) {
      cs.registerOutParameter(1, Types.INTEGER);
      cs.execute();

      int val = cs.getInt(1);
      assertEquals("Integer result should be 100", 100, val);
    }
  }

  /**
   * Test getString on sequence result
   */
  @Test
  public void testSequenceGetString() throws Exception {
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".nextval()}")) {
      cs.registerOutParameter(1, Types.BIGINT);
      cs.execute();

      String val = cs.getString(1);
      assertEquals("String result should be '100'", "100", val);
    }
  }

  /**
   * Test multiple nextval calls in sequence
   */
  @Test
  public void testMultipleNextvalCalls() throws Exception {
    long[] expected = {100L, 101L, 102L, 103L, 104L};

    for (int i = 0; i < expected.length; i++) {
      try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".nextval()}")) {
        cs.registerOutParameter(1, Types.BIGINT);
        cs.execute();

        long val = cs.getLong(1);
        assertEquals("nextval call " + (i + 1) + " should return " + expected[i], expected[i], val);
      }
    }
  }

  /**
   * Test executeUpdate instead of execute
   */
  @Test
  public void testSequenceWithExecuteUpdate() throws Exception {
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".nextval()}")) {
      cs.registerOutParameter(1, Types.BIGINT);
      cs.executeUpdate();

      long val = cs.getLong(1);
      assertEquals("executeUpdate should also work", 100L, val);
    }
  }

  /**
   * Test sequence with schema-qualified name: {? = call schema.seq.nextval()}
   */
  @Test
  public void testSchemaQualifiedSequence() throws Exception {
    // Create sequence in public schema explicitly
    String schemaSeqName = "public." + SEQ_NAME + "_schema";
    TestUtil.execute(conn, "DROP SEQUENCE IF EXISTS " + schemaSeqName);
    TestUtil.execute(conn, "CREATE SEQUENCE " + schemaSeqName + " START WITH 200");

    try {
      try (CallableStatement cs = conn.prepareCall("{? = call " + schemaSeqName + ".nextval()}")) {
        cs.registerOutParameter(1, Types.BIGINT);
        cs.execute();

        long val = cs.getLong(1);
        assertEquals("Schema-qualified sequence should return 200", 200L, val);
      }
    } finally {
      TestUtil.execute(conn, "DROP SEQUENCE IF EXISTS " + schemaSeqName);
    }
  }

  /**
   * Test case insensitivity of nextval/currval keywords
   */
  @Test
  public void testCaseInsensitivity() throws Exception {
    // Test NEXTVAL (uppercase)
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".NEXTVAL()}")) {
      cs.registerOutParameter(1, Types.BIGINT);
      cs.execute();
      assertEquals(100L, cs.getLong(1));
    }

    // Test NextVal (mixed case)
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".NextVal()}")) {
      cs.registerOutParameter(1, Types.BIGINT);
      cs.execute();
      assertEquals(101L, cs.getLong(1));
    }

    // Test CURRVAL (uppercase)
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".CURRVAL()}")) {
      cs.registerOutParameter(1, Types.BIGINT);
      cs.execute();
      assertEquals(101L, cs.getLong(1));
    }
  }

  /**
   * Test reusing the same CallableStatement multiple times
   */
  @Test
  public void testReuseCallableStatement() throws Exception {
    try (CallableStatement cs = conn.prepareCall("{? = call " + SEQ_NAME + ".nextval()}")) {
      cs.registerOutParameter(1, Types.BIGINT);

      // First execution
      cs.execute();
      assertEquals(100L, cs.getLong(1));

      // Second execution - reuse same statement
      cs.execute();
      assertEquals(101L, cs.getLong(1));

      // Third execution
      cs.execute();
      assertEquals(102L, cs.getLong(1));
    }
  }
}
