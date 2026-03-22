/*
 * Portions Copyright (c) 2025, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.jdbc.PgCompositeObject;
import com.aliyun.polardb2.jdbc.PgStruct;
import com.aliyun.polardb2.jdbc.PostgresStructConverter;
import com.aliyun.polardb2.test.TestUtil;
import com.aliyun.polardb2.util.PGobject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.HashMap;
import java.util.Properties;

/**
 * Comprehensive tests for composite type handling in the JDBC driver.
 *
 * <p>Covers three patch components:
 * <ul>
 *   <li>{@code PostgresStructConverter.parsePostgresStruct} - composite literal parsing</li>
 *   <li>{@code PgCompositeObject} - PGobject + Struct dual-interface class</li>
 *   <li>{@code ArrayDecoding.CompositeStructArrayDecoder} - TABLE OF composite OUT param</li>
 * </ul>
 */
public class CompositeTypeNullDateTest {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();

    // Create a composite type with date field (similar to quot_pol_info)
    stmt.execute(
        "CREATE OR REPLACE TYPE test_pol_info AS ("
            + "action_code VARCHAR(10),"
            + "apply_dt DATE,"
            + "pol_eff_dt DATE,"
            + "mode_prem NUMERIC(11,2)"
            + ")");

    // Create a table of that type
    stmt.execute(
        "CREATE OR REPLACE TYPE test_tbl_pol_info AS TABLE OF test_pol_info");

    // Create a simple stored procedure that accepts single composite type
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE test_proc_single_pol_info("
            + "p_data IN test_pol_info"
            + ") IS "
            + "BEGIN "
            + "  NULL; "
            + "END;");

    // Create a simple stored procedure that accepts the table type
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE test_proc_pol_info("
            + "p_data IN test_tbl_pol_info"
            + ") IS "
            + "BEGIN "
            + "  NULL; "
            + "END;");

    // Create a function returning TABLE OF composite type (for PGobject→Struct cast test)
    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_func_return_pol_info "
            + "RETURN test_tbl_pol_info AS "
            + "  v_result test_tbl_pol_info := test_tbl_pol_info(); "
            + "BEGIN "
            + "  v_result.extend; "
            + "  v_result(1) := test_pol_info('NFORPU', DATE '2025-06-15', DATE '2025-07-01', 1234.56); "
            + "  v_result.extend; "
            + "  v_result(2) := test_pol_info('RENEWAL', DATE '2025-08-01', DATE '2025-09-01', 5678.90); "
            + "  RETURN v_result; "
            + "END;");

    // Function returning TABLE OF with null fields in composite elements
    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_func_return_with_nulls "
            + "RETURN test_tbl_pol_info AS "
            + "  v_result test_tbl_pol_info := test_tbl_pol_info(); "
            + "BEGIN "
            + "  v_result.extend; "
            + "  v_result(1) := test_pol_info('ACT1', NULL, NULL, NULL); "
            + "  v_result.extend; "
            + "  v_result(2) := test_pol_info('ACT2', DATE '2025-06-15', NULL, 99.99); "
            + "  v_result.extend; "
            + "  v_result(3) := test_pol_info(NULL, NULL, NULL, NULL); "
            + "  RETURN v_result; "
            + "END;");

    // Function returning single-element TABLE OF
    stmt.execute(
        "CREATE OR REPLACE FUNCTION test_func_return_single "
            + "RETURN test_tbl_pol_info AS "
            + "  v_result test_tbl_pol_info := test_tbl_pol_info(); "
            + "BEGIN "
            + "  v_result.extend; "
            + "  v_result(1) := test_pol_info('ONLY', DATE '2025-01-01', DATE '2025-12-31', 0.01); "
            + "  RETURN v_result; "
            + "END;");

    // Procedure that accepts single composite and OUTs it back
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE test_proc_roundtrip("
            + "p_in IN test_pol_info, "
            + "p_out OUT test_tbl_pol_info"
            + ") IS "
            + "BEGIN "
            + "  p_out := test_tbl_pol_info(); "
            + "  p_out.extend; "
            + "  p_out(1) := p_in; "
            + "END;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("DROP PROCEDURE IF EXISTS test_proc_roundtrip");
    stmt.execute("DROP FUNCTION IF EXISTS test_func_return_single");
    stmt.execute("DROP FUNCTION IF EXISTS test_func_return_with_nulls");
    stmt.execute("DROP FUNCTION IF EXISTS test_func_return_pol_info");
    stmt.execute("DROP PROCEDURE IF EXISTS test_proc_single_pol_info");
    stmt.execute("DROP PROCEDURE IF EXISTS test_proc_pol_info");
    stmt.execute("DROP TYPE IF EXISTS test_tbl_pol_info");
    stmt.execute("DROP TYPE IF EXISTS test_pol_info");
    stmt.close();
    conn.close();
  }

  /**
   * Tests that composite type with null DATE fields works correctly.
   *
   * <p>When a composite type has a DATE field with null value, PostgresStructConverter
   * must encode it as an empty field (nothing between commas), not the word NULL.
   * PolarDB interprets NULL word inside a composite literal as a string, causing:
   * ERROR: invalid input syntax for type date: "NULL"
   */
  @Test
  public void testCompositeTypeWithNullDateField() throws SQLException {
    // Create PgStruct with null Date fields
    Object[] attributes = new Object[] {
        "NFORPU",           // action_code VARCHAR
        null,               // apply_dt DATE - NULL!
        null,               // pol_eff_dt DATE - NULL!
        null                // mode_prem NUMERIC - NULL!
    };

    PgStruct struct = new PgStruct("test_pol_info", attributes);

    // Verify fix: null fields must be empty (not 'NULL' word)
    assertEquals("(\"NFORPU\",,,)", struct.toString());

    // Use setObject which internally calls setStruct for PgStruct
    // After fix, this should NOT throw:
    // ERROR: invalid input syntax for type date: "NULL"
    CallableStatement cs = conn.prepareCall("{ call test_proc_single_pol_info(?) }");
    cs.setObject(1, struct);
    cs.execute();
    cs.close();
  }

  /**
   * Test with mixed null and non-null date values.
   */
  @Test
  public void testCompositeTypeWithMixedDateFields() throws SQLException {
    // First struct: all nulls
    Object[] attrs1 = new Object[] {
        "TEST1",
        null,                       // null date
        null,                       // null date
        null                        // null numeric
    };

    // Second struct: with values
    Object[] attrs2 = new Object[] {
        "TEST2",
        Date.valueOf("2025-06-15"), // non-null date
        Date.valueOf("2025-07-01"), // non-null date
        new java.math.BigDecimal("1234.56")
    };

    PgStruct struct1 = new PgStruct("test_pol_info", attrs1);
    PgStruct struct2 = new PgStruct("test_pol_info", attrs2);

    Struct[] structs = new Struct[] { struct1, struct2 };
    Array array = conn.createArrayOf("test_pol_info", structs);

    CallableStatement cs = conn.prepareCall("{ call test_proc_pol_info(?) }");
    cs.setArray(1, array);
    cs.execute();
    cs.close();
  }

  /**
   * Tests that TABLE OF composite_type OUT parameter elements can be cast to
   * both java.sql.Struct (JDBC standard) and PGobject (backward compatible).
   *
   * <p>Previously, elements were plain PGobject and casting to Struct threw:
   * ClassCastException: PGobject cannot be cast to java.sql.Struct
   */
  @Test
  public void testTableOfCompositeTypeOutParamCastToStruct() throws SQLException {
    // Simulate: registerOutParameter(parameterIndex, Types.ARRAY, oracleUser + "EXRATETAB")
    // where EXRATETAB is TABLE OF exratetype (a composite type)
    try (CallableStatement cs = conn.prepareCall(
        "{ ? = call test_func_return_pol_info() }")) {
      cs.registerOutParameter(1, Types.ARRAY, "test_tbl_pol_info");
      cs.execute();

      // Get array result
      Array result = cs.getArray(1);
      assertNotNull("Expected non-null ARRAY result", result);

      Object[] elements = (Object[]) result.getArray();
      assertNotNull("Expected non-null elements", elements);
      assertEquals("Expected 2 elements", 2, elements.length);

      // Verify: elements can be cast to both Struct AND PGobject
      for (Object element : elements) {
        assertTrue("Element should be instanceof Struct",
            element instanceof Struct);
        assertTrue("Element should be instanceof PGobject (backward compatible)",
            element instanceof PGobject);

        // Cast to Struct and access attributes
        Struct struct = (Struct) element;
        assertNotNull("getSQLTypeName should not be null", struct.getSQLTypeName());
        Object[] attrs = struct.getAttributes();
        assertNotNull("getAttributes should not be null", attrs);
        assertEquals("Composite type has 4 fields", 4, attrs.length);
      }

      // Verify first element attributes
      Struct s1 = (Struct) elements[0];
      Object[] attrs1 = s1.getAttributes();
      assertEquals("NFORPU", attrs1[0]);

      // Verify second element attributes
      Struct s2 = (Struct) elements[1];
      Object[] attrs2 = s2.getAttributes();
      assertEquals("RENEWAL", attrs2[0]);
    }
  }

  // ===================================================================
  // Part 1: PostgresStructConverter.parsePostgresStruct unit tests
  // ===================================================================

  @Test
  public void testParseBasicUnquotedFields() {
    Object[] result = PostgresStructConverter.parsePostgresStruct("(abc,123,true)");
    assertArrayEquals(new Object[]{"abc", "123", "true"}, result);
  }

  @Test
  public void testParseQuotedStringFields() {
    Object[] result = PostgresStructConverter.parsePostgresStruct("(\"hello\",\"world\")");
    assertArrayEquals(new Object[]{"hello", "world"}, result);
  }

  @Test
  public void testParseNullFieldsMiddle() {
    // Empty fields between commas represent NULL
    Object[] result = PostgresStructConverter.parsePostgresStruct("(val1,,val3)");
    assertEquals(3, result.length);
    assertEquals("val1", result[0]);
    assertNull("Middle field should be null", result[1]);
    assertEquals("val3", result[2]);
  }

  @Test
  public void testParseAllNullFields() {
    // (,,,) => 4 null fields
    Object[] result = PostgresStructConverter.parsePostgresStruct("(,,,)");
    assertEquals(4, result.length);
    for (Object field : result) {
      assertNull("All fields should be null", field);
    }
  }

  @Test
  public void testParseTrailingNull() {
    // (val1,) => [val1, null]
    Object[] result = PostgresStructConverter.parsePostgresStruct("(val1,)");
    assertEquals(2, result.length);
    assertEquals("val1", result[0]);
    assertNull("Trailing field should be null", result[1]);
  }

  @Test
  public void testParseLeadingNull() {
    // (,val2) => [null, val2]
    Object[] result = PostgresStructConverter.parsePostgresStruct("(,val2)");
    assertEquals(2, result.length);
    assertNull("Leading field should be null", result[0]);
    assertEquals("val2", result[1]);
  }

  @Test
  public void testParseEscapedDoubleQuotes() {
    // ("say ""hi""",val2) => [say "hi", val2]
    Object[] result = PostgresStructConverter.parsePostgresStruct(
        "(\"say \"\"hi\"\"\",val2)");
    assertEquals(2, result.length);
    assertEquals("say \"hi\"", result[0]);
    assertEquals("val2", result[1]);
  }

  @Test
  public void testParseEmptyStringVsNull() {
    // ("",) => ["", null] - empty quoted string is NOT null
    Object[] result = PostgresStructConverter.parsePostgresStruct("(\"\",)");
    assertEquals(2, result.length);
    assertEquals("Empty quoted string should be empty, not null", "", result[0]);
    assertNull("Trailing empty should be null", result[1]);
  }

  @Test
  public void testParseSingleField() {
    Object[] result = PostgresStructConverter.parsePostgresStruct("(onlyval)");
    assertArrayEquals(new Object[]{"onlyval"}, result);
  }

  @Test
  public void testParseSingleNullField() {
    // (,) has 2 null fields; single null is just ()
    // Actually () is empty → 0 fields
    Object[] result = PostgresStructConverter.parsePostgresStruct("()");
    assertEquals(0, result.length);
  }

  @Test
  public void testParseNullInput() {
    Object[] result = PostgresStructConverter.parsePostgresStruct(null);
    assertEquals(0, result.length);
  }

  @Test
  public void testParseShortInput() {
    Object[] result = PostgresStructConverter.parsePostgresStruct("x");
    assertEquals(0, result.length);
  }

  @Test
  public void testParseMixedQuotedAndUnquoted() {
    // ("hello",123,,"world") => [hello, 123, null, world]
    Object[] result = PostgresStructConverter.parsePostgresStruct(
        "(\"hello\",123,,\"world\")");
    assertEquals(4, result.length);
    assertEquals("hello", result[0]);
    assertEquals("123", result[1]);
    assertNull(result[2]);
    assertEquals("world", result[3]);
  }

  @Test
  public void testParseMultipleConsecutiveNulls() {
    // (a,,,b) => [a, null, null, b]
    Object[] result = PostgresStructConverter.parsePostgresStruct("(a,,,b)");
    assertEquals(4, result.length);
    assertEquals("a", result[0]);
    assertNull(result[1]);
    assertNull(result[2]);
    assertEquals("b", result[3]);
  }

  @Test
  public void testParseQuotedWithComma() {
    // ("a,b",c) => [a,b, c] - comma inside quotes is part of value
    Object[] result = PostgresStructConverter.parsePostgresStruct(
        "(\"a,b\",c)");
    assertEquals(2, result.length);
    assertEquals("a,b", result[0]);
    assertEquals("c", result[1]);
  }

  @Test
  public void testParseQuotedWithParentheses() {
    // ("(nested)",val) => [(nested), val] - parens inside quotes
    Object[] result = PostgresStructConverter.parsePostgresStruct(
        "(\"(nested)\",val)");
    assertEquals(2, result.length);
    assertEquals("(nested)", result[0]);
    assertEquals("val", result[1]);
  }

  // ===================================================================
  // Part 2: Serialization/Deserialization roundtrip tests
  // ===================================================================

  @Test
  public void testSerializeDeserializeRoundtrip() {
    Object[] original = new Object[]{"NFORPU", null, null, null};
    String serialized = PostgresStructConverter.objectArrayToPostgresStruct(original);
    assertEquals("(\"NFORPU\",,,)", serialized);

    Object[] parsed = PostgresStructConverter.parsePostgresStruct(serialized);
    assertEquals(4, parsed.length);
    assertEquals("NFORPU", parsed[0]);
    assertNull(parsed[1]);
    assertNull(parsed[2]);
    assertNull(parsed[3]);
  }

  @Test
  public void testSerializeNullArray() {
    assertEquals("NULL", PostgresStructConverter.objectArrayToPostgresStruct(null));
  }

  @Test
  public void testSerializeEmptyArray() {
    assertEquals("()", PostgresStructConverter.objectArrayToPostgresStruct(new Object[0]));
  }

  @Test
  public void testSerializeAllNullFields() {
    String result = PostgresStructConverter.objectArrayToPostgresStruct(
        new Object[]{null, null, null});
    assertEquals("(,,)", result);
  }

  // ===================================================================
  // Part 3: PgCompositeObject unit tests
  // ===================================================================

  @Test
  public void testPgCompositeObjectInstanceOf() throws SQLException {
    PgCompositeObject obj = new PgCompositeObject();
    obj.setType("my_type");
    obj.setValue("(a,b,c)");
    assertTrue("Must be instanceof Struct", obj instanceof Struct);
    assertTrue("Must be instanceof PGobject", obj instanceof PGobject);
  }

  @Test
  public void testPgCompositeObjectGetSQLTypeName() throws SQLException {
    PgCompositeObject obj = new PgCompositeObject();
    obj.setType("test_pol_info");
    assertEquals("test_pol_info", obj.getSQLTypeName());
  }

  @Test
  public void testPgCompositeObjectGetAttributes() throws SQLException {
    PgCompositeObject obj = new PgCompositeObject();
    obj.setType("test_pol_info");
    obj.setValue("(\"NFORPU\",2025-06-15,,1234.56)");

    Object[] attrs = obj.getAttributes();
    assertEquals(4, attrs.length);
    assertEquals("NFORPU", attrs[0]);
    assertEquals("2025-06-15", attrs[1]);
    assertNull("Null field should be null", attrs[2]);
    assertEquals("1234.56", attrs[3]);
  }

  @Test
  public void testPgCompositeObjectGetAttributesAllNull() throws SQLException {
    PgCompositeObject obj = new PgCompositeObject();
    obj.setType("test_pol_info");
    obj.setValue("(,,,)");

    Object[] attrs = obj.getAttributes();
    assertEquals(4, attrs.length);
    for (Object attr : attrs) {
      assertNull(attr);
    }
  }

  @Test
  public void testPgCompositeObjectNullValue() throws SQLException {
    PgCompositeObject obj = new PgCompositeObject();
    obj.setType("test_pol_info");
    // value is null by default
    Object[] attrs = obj.getAttributes();
    assertEquals("Null value should return empty array", 0, attrs.length);
  }

  @Test
  public void testPgCompositeObjectGetAttributesWithMap() throws SQLException {
    PgCompositeObject obj = new PgCompositeObject();
    obj.setType("test_pol_info");
    obj.setValue("(a,b)");

    // getAttributes(map) should delegate to getAttributes()
    Object[] attrs = obj.getAttributes(new HashMap<String, Class<?>>());
    assertArrayEquals(new Object[]{"a", "b"}, attrs);
  }

  @Test
  public void testPgCompositeObjectPGobjectBackwardCompat() throws SQLException {
    PgCompositeObject obj = new PgCompositeObject();
    obj.setType("test_pol_info");
    obj.setValue("(\"TEST\",2025-01-01,,100)");

    // PGobject methods should still work
    assertEquals("test_pol_info", obj.getType());
    assertEquals("(\"TEST\",2025-01-01,,100)", obj.getValue());
  }

  // ===================================================================
  // Part 4: Integration tests - NULL encoding with setStruct/setArray
  // ===================================================================

  @Test
  public void testSetStructSingleFieldNull() throws SQLException {
    // Only action_code set, all others null
    PgStruct struct = new PgStruct("test_pol_info",
        new Object[]{"X", null, null, null});
    assertEquals("(\"X\",,,)", struct.toString());

    CallableStatement cs = conn.prepareCall("{ call test_proc_single_pol_info(?) }");
    cs.setObject(1, struct);
    cs.execute();
    cs.close();
  }

  @Test
  public void testSetStructAllFieldsNull() throws SQLException {
    // All fields null
    PgStruct struct = new PgStruct("test_pol_info",
        new Object[]{null, null, null, null});
    assertEquals("(,,,)", struct.toString());

    CallableStatement cs = conn.prepareCall("{ call test_proc_single_pol_info(?) }");
    cs.setObject(1, struct);
    cs.execute();
    cs.close();
  }

  @Test
  public void testSetStructAllFieldsPopulated() throws SQLException {
    PgStruct struct = new PgStruct("test_pol_info",
        new Object[]{"FULL", Date.valueOf("2025-06-15"),
            Date.valueOf("2025-07-01"), new java.math.BigDecimal("999.99")});

    CallableStatement cs = conn.prepareCall("{ call test_proc_single_pol_info(?) }");
    cs.setObject(1, struct);
    cs.execute();
    cs.close();
  }

  @Test
  public void testSetArrayMixedNullComposites() throws SQLException {
    // Mix of full, partial-null, and all-null structs
    PgStruct s1 = new PgStruct("test_pol_info",
        new Object[]{"A", Date.valueOf("2025-01-01"), Date.valueOf("2025-02-01"),
            new java.math.BigDecimal("100")});
    PgStruct s2 = new PgStruct("test_pol_info",
        new Object[]{"B", null, null, null});
    PgStruct s3 = new PgStruct("test_pol_info",
        new Object[]{null, null, null, null});

    Array array = conn.createArrayOf("test_pol_info", new Struct[]{s1, s2, s3});
    CallableStatement cs = conn.prepareCall("{ call test_proc_pol_info(?) }");
    cs.setArray(1, array);
    cs.execute();
    cs.close();
  }

  // ===================================================================
  // Part 5: Integration tests - TABLE OF composite OUT param
  // ===================================================================

  /**
   * OUT param with null fields inside composite elements.
   */
  @Test
  public void testOutParamWithNullFieldsInComposite() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ ? = call test_func_return_with_nulls() }")) {
      cs.registerOutParameter(1, Types.ARRAY, "test_tbl_pol_info");
      cs.execute();

      Array result = cs.getArray(1);
      assertNotNull(result);
      Object[] elements = (Object[]) result.getArray();
      assertEquals(3, elements.length);

      // Element 1: ('ACT1', NULL, NULL, NULL)
      Struct e1 = (Struct) elements[0];
      Object[] a1 = e1.getAttributes();
      assertEquals(4, a1.length);
      assertEquals("ACT1", a1[0]);

      // Element 2: ('ACT2', '2025-06-15', NULL, 99.99)
      Struct e2 = (Struct) elements[1];
      Object[] a2 = e2.getAttributes();
      assertEquals(4, a2.length);
      assertEquals("ACT2", a2[0]);

      // Element 3: all NULL
      Struct e3 = (Struct) elements[2];
      Object[] a3 = e3.getAttributes();
      assertEquals(4, a3.length);
    }
  }

  /**
   * Single element TABLE OF should work correctly.
   */
  @Test
  public void testOutParamSingleElement() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ ? = call test_func_return_single() }")) {
      cs.registerOutParameter(1, Types.ARRAY, "test_tbl_pol_info");
      cs.execute();

      Array result = cs.getArray(1);
      assertNotNull(result);
      Object[] elements = (Object[]) result.getArray();
      assertEquals(1, elements.length);

      assertTrue(elements[0] instanceof Struct);
      assertTrue(elements[0] instanceof PGobject);

      Struct s = (Struct) elements[0];
      assertEquals("test_pol_info", s.getSQLTypeName());
      Object[] attrs = s.getAttributes();
      assertEquals(4, attrs.length);
      assertEquals("ONLY", attrs[0]);
    }
  }

  /**
   * Roundtrip: write PgStruct with nulls → read back as PgCompositeObject → verify attributes.
   */
  @Test
  public void testRoundtripWriteAndReadComposite() throws SQLException {
    PgStruct input = new PgStruct("test_pol_info",
        new Object[]{"ROUND", null, Date.valueOf("2025-10-10"), null});

    try (CallableStatement cs = conn.prepareCall(
        "{ call test_proc_roundtrip(?, ?) }")) {
      cs.setObject(1, input);
      cs.registerOutParameter(2, Types.ARRAY, "test_tbl_pol_info");
      cs.execute();

      Array result = cs.getArray(2);
      assertNotNull(result);
      Object[] elements = (Object[]) result.getArray();
      assertEquals(1, elements.length);

      // Must be castable to Struct
      Struct out = (Struct) elements[0];
      Object[] attrs = out.getAttributes();
      assertEquals(4, attrs.length);
      assertEquals("ROUND", attrs[0]);
    }
  }

  /**
   * Verify PGobject backward compat: element can be cast to PGobject and getValue works.
   */
  @Test
  public void testOutParamPGobjectGetValue() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ ? = call test_func_return_pol_info() }")) {
      cs.registerOutParameter(1, Types.ARRAY, "test_tbl_pol_info");
      cs.execute();

      Array result = cs.getArray(1);
      Object[] elements = (Object[]) result.getArray();

      for (Object element : elements) {
        PGobject pgObj = (PGobject) element;
        assertNotNull("PGobject.getType() should not be null", pgObj.getType());
        assertNotNull("PGobject.getValue() should not be null", pgObj.getValue());
        // Value should be the composite literal
        assertTrue("Value should start with (",
            pgObj.getValue().startsWith("("));
        assertTrue("Value should end with )",
            pgObj.getValue().endsWith(")"));
      }
    }
  }

  /**
   * getResultSet() on the array should also return elements castable to Struct.
   */
  @Test
  public void testOutParamViaResultSet() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ ? = call test_func_return_pol_info() }")) {
      cs.registerOutParameter(1, Types.ARRAY, "test_tbl_pol_info");
      cs.execute();

      Array result = cs.getArray(1);
      ResultSet rs = result.getResultSet();
      int count = 0;
      while (rs.next()) {
        count++;
        Object val = rs.getObject(2); // column 2 is the value
        assertTrue("Element from ResultSet should be instanceof Struct",
            val instanceof Struct);
        assertTrue("Element from ResultSet should be instanceof PGobject",
            val instanceof PGobject);
      }
      assertEquals("Expected 2 rows from ResultSet", 2, count);
      rs.close();
    }
  }
}
