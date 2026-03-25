/*
 * Portions Copyright (c) 2025, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.jdbc.PgCompositeObject;
import com.aliyun.polardb2.jdbc.PgConnection;
import com.aliyun.polardb2.test.TestUtil;
import com.aliyun.polardb2.util.PGobject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.Properties;

/**
 * Tests for the fix of customer bug:
 * <pre>
 * DB: hkped, Account: cis
 * ERROR: malformed record literal: "HHF01790"
 *   Missing left parenthesis.
 *   unnamed portal parameter $3 = '...'
 * SQL: begin CLAIM_BODY.CALL_VALID_CLM_PAY(?, ?, ?, ?, ?, ?); end;
 * </pre>
 *
 * <p>Root cause: {@code ArrayEncoding.OBJECT_ARRAY.appendArray} handled
 * {@code Struct} elements correctly by building {@code (val1,val2,...)} format,
 * but for plain {@code PGobject} elements (which do NOT implement Struct),
 * it fell through to the {@code else} branch and called {@code toString()}
 * directly. If {@code PGobject.value} did not include parentheses
 * (e.g. {@code "HHF01790,HH,RB,HOSP"} instead of
 * {@code "(HHF01790,HH,RB,HOSP)"}), the server received a malformed
 * record literal and threw "Missing left parenthesis".
 *
 * <p>Fix: Added a {@code PGobject} branch in {@code OBJECT_ARRAY.appendArray}
 * that detects comma-separated values without enclosing parentheses and
 * automatically wraps them as record literals.
 */
public class MalformedRecordLiteralTest {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();

    // Simplified record type modelling REC_CLM_BNFT_DTLS
    stmt.execute(
        "CREATE OR REPLACE TYPE rec_claim_item AS (\n"
            + "  claim_no     VARCHAR2(20),\n"
            + "  company      VARCHAR2(10),\n"
            + "  benefit_code VARCHAR2(10),\n"
            + "  plan_type    VARCHAR2(10),\n"
            + "  seq_no       NUMBER,\n"
            + "  amount       NUMBER\n"
            + ")");

    // TABLE OF type modelling TAB_CLM_BNFT_DTLS
    stmt.execute(
        "CREATE OR REPLACE TYPE tab_claim_items AS TABLE OF rec_claim_item");

    // Package modelling CLAIM_BODY
    stmt.execute(
        "CREATE OR REPLACE PACKAGE claim_body_pkg AS\n"
            + "  PROCEDURE validate_payment(\n"
            + "    p_company IN  VARCHAR2,\n"
            + "    p_struct  IN  rec_claim_item,\n"
            + "    p_items   IN  tab_claim_items,\n"
            + "    p_count   OUT INTEGER,\n"
            + "    p_status  OUT INTEGER\n"
            + "  );\n"
            + "END claim_body_pkg;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY claim_body_pkg AS\n"
            + "  PROCEDURE validate_payment(\n"
            + "    p_company IN  VARCHAR2,\n"
            + "    p_struct  IN  rec_claim_item,\n"
            + "    p_items   IN  tab_claim_items,\n"
            + "    p_count   OUT INTEGER,\n"
            + "    p_status  OUT INTEGER\n"
            + "  ) IS\n"
            + "  BEGIN\n"
            + "    p_count  := p_items.COUNT;\n"
            + "    p_status := 0;\n"
            + "  END;\n"
            + "END claim_body_pkg;");

    // Single-field record type to reproduce the "TC067907" scenario
    // (PGobject value without commas and without parentheses)
    stmt.execute(
        "CREATE OR REPLACE TYPE rec_single_code AS (\n"
            + "  code VARCHAR2(20)\n"
            + ")");
    stmt.execute("CREATE OR REPLACE TYPE tab_single_codes AS TABLE OF rec_single_code");
    stmt.execute(
        "CREATE OR REPLACE PACKAGE single_code_pkg AS\n"
            + "  PROCEDURE process_codes(\n"
            + "    p_company IN  VARCHAR2,\n"
            + "    p_codes   IN  tab_single_codes,\n"
            + "    p_count   OUT INTEGER,\n"
            + "    p_status  OUT INTEGER\n"
            + "  );\n"
            + "END single_code_pkg;");
    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY single_code_pkg AS\n"
            + "  PROCEDURE process_codes(\n"
            + "    p_company IN  VARCHAR2,\n"
            + "    p_codes   IN  tab_single_codes,\n"
            + "    p_count   OUT INTEGER,\n"
            + "    p_status  OUT INTEGER\n"
            + "  ) IS\n"
            + "  BEGIN\n"
            + "    p_count  := p_codes.COUNT;\n"
            + "    p_status := 0;\n"
            + "  END;\n"
            + "END single_code_pkg;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("DROP PACKAGE IF EXISTS single_code_pkg");
    stmt.execute("DROP TYPE IF EXISTS tab_single_codes");
    stmt.execute("DROP TYPE IF EXISTS rec_single_code");
    stmt.execute("DROP PACKAGE IF EXISTS claim_body_pkg");
    stmt.execute("DROP TYPE IF EXISTS tab_claim_items");
    stmt.execute("DROP TYPE IF EXISTS rec_claim_item");
    stmt.close();
    conn.close();
  }

  // ===================================================================
  // Bug reproduction: PGobject[] WITHOUT parentheses
  // ===================================================================

  /**
   * Customer bug scenario: PGobject array elements WITHOUT parentheses.
   *
   * <p>The Manulife framework's OracleArrayParameter creates PGobject instances
   * with comma-separated values but NO enclosing (). After the fix,
   * OBJECT_ARRAY.appendArray detects the missing parentheses and wraps
   * the value automatically, so the call should succeed.
   */
  @Test
  public void testPGobjectWithoutParensNowSucceeds() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    // Simulate framework creating PGobject elements WITHOUT ()
    PGobject obj1 = new PGobject();
    obj1.setType("rec_claim_item");
    obj1.setValue("HHF01790,HH,RB,HOSP,15,900.0");  // NO parentheses

    PGobject obj2 = new PGobject();
    obj2.setType("rec_claim_item");
    obj2.setValue("HHF01790,HH,EO,HOSP,15,5000.0");  // NO parentheses

    Array array = pgConn.createArrayOf("tab_claim_items", new PGobject[]{obj1, obj2});

    // Also create a single struct for p_struct param
    Struct struct = conn.createStruct("rec_claim_item",
        new Object[]{"HHF01790", "HH", "HOSP", "15", 1, 80000.0});

    try (CallableStatement cs = conn.prepareCall(
        "{ call claim_body_pkg.validate_payment(?, ?, ?, ?, ?) }")) {
      cs.setString(1, "HH");
      cs.setObject(2, struct);
      cs.setArray(3, array);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 2 items", 2, cs.getInt(4));
      assertEquals("Status should be 0", 0, cs.getInt(5));
    }
  }

  /**
   * Multi-element array with PGobject elements — no parentheses.
   * After fix, the array string should include parentheses.
   */
  @Test
  public void testMultiElementPGobjectArrayNowHasParens() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    PGobject obj1 = new PGobject();
    obj1.setType("rec_claim_item");
    obj1.setValue("CLM001,HH,RB,HOSP,1,900.0");

    PGobject obj2 = new PGobject();
    obj2.setType("rec_claim_item");
    obj2.setValue("CLM001,HH,EO,HOSP,2,5000.0");

    Array array = pgConn.createArrayOf("tab_claim_items", new PGobject[]{obj1, obj2});

    // After fix, the array string should contain parentheses
    String arrayStr = array.toString();
    System.out.println("[DIAGNOSTIC] Array string: " + arrayStr);

    // Correct format: {"(CLM001,HH,RB,HOSP,1,900.0)","(CLM001,HH,EO,HOSP,2,5000.0)"}
    assertTrue("Array string should contain parenthesized records after fix",
        arrayStr.contains("(CLM001"));
  }

  /**
   * Single PGobject element without parentheses — now succeeds after fix.
   */
  @Test
  public void testSinglePGobjectWithoutParensNowSucceeds() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    PGobject obj = new PGobject();
    obj.setType("rec_claim_item");
    obj.setValue("X001,CO,BN,PT,1,100.0");  // NO parentheses

    Array array = pgConn.createArrayOf("tab_claim_items", new PGobject[]{obj});

    Struct struct = conn.createStruct("rec_claim_item",
        new Object[]{"X001", "CO", "BN", "PT", 1, 100.0});

    try (CallableStatement cs = conn.prepareCall(
        "{ call claim_body_pkg.validate_payment(?, ?, ?, ?, ?) }")) {
      cs.setString(1, "CO");
      cs.setObject(2, struct);
      cs.setArray(3, array);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 item", 1, cs.getInt(4));
      assertEquals("Status should be 0", 0, cs.getInt(5));
    }
  }

  // ===================================================================
  // Control tests: correct approaches that should succeed
  // ===================================================================

  /**
   * Control: PgStruct[] elements — already working correctly.
   * PgStruct implements Struct, so OBJECT_ARRAY.appendArray builds (val1,val2,...) format.
   */
  @Test
  public void testPgStructInArraySucceeds() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    Struct s1 = conn.createStruct("rec_claim_item",
        new Object[]{"CLM001", "HH", "RB", "HOSP", 1, 900.0});
    Struct s2 = conn.createStruct("rec_claim_item",
        new Object[]{"CLM001", "HH", "EO", "HOSP", 2, 5000.0});

    Array array = pgConn.createArrayOf("tab_claim_items", new Struct[]{s1, s2});

    Struct struct = conn.createStruct("rec_claim_item",
        new Object[]{"CLM001", "HH", "HOSP", "ALL", 0, 0.0});

    try (CallableStatement cs = conn.prepareCall(
        "{ call claim_body_pkg.validate_payment(?, ?, ?, ?, ?) }")) {
      cs.setString(1, "HH");
      cs.setObject(2, struct);
      cs.setArray(3, array);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 2 items", 2, cs.getInt(4));
      assertEquals("Status should be 0", 0, cs.getInt(5));
    }
  }

  /**
   * Control: PgCompositeObject[] elements (extends PGobject AND implements Struct).
   * Since PgCompositeObject implements Struct, the Struct branch is taken.
   * Values MUST include parentheses since getAttributes() parses them.
   */
  @Test
  public void testPgCompositeObjectInArraySucceeds() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    PgCompositeObject comp1 = new PgCompositeObject();
    comp1.setType("rec_claim_item");
    comp1.setValue("(CLM002,HH,RB,HOSP,1,800.0)");

    PgCompositeObject comp2 = new PgCompositeObject();
    comp2.setType("rec_claim_item");
    comp2.setValue("(CLM002,HH,EO,HOSP,2,3000.0)");

    Array array = pgConn.createArrayOf("tab_claim_items",
        new PgCompositeObject[]{comp1, comp2});

    Struct struct = conn.createStruct("rec_claim_item",
        new Object[]{"CLM002", "HH", "HOSP", "ALL", 0, 0.0});

    try (CallableStatement cs = conn.prepareCall(
        "{ call claim_body_pkg.validate_payment(?, ?, ?, ?, ?) }")) {
      cs.setString(1, "HH");
      cs.setObject(2, struct);
      cs.setArray(3, array);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 2 items", 2, cs.getInt(4));
    }
  }

  /**
   * Control: PGobject[] WITH parentheses in value — should succeed as workaround.
   * If the PGobject value already includes "()", toString() returns the correct format.
   */
  @Test
  public void testPGobjectWithParensSucceeds() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    PGobject obj1 = new PGobject();
    obj1.setType("rec_claim_item");
    obj1.setValue("(CLM003,HH,RB,HOSP,1,700.0)");  // WITH parentheses

    PGobject obj2 = new PGobject();
    obj2.setType("rec_claim_item");
    obj2.setValue("(CLM003,HH,EO,HOSP,2,2000.0)");  // WITH parentheses

    Array array = pgConn.createArrayOf("tab_claim_items", new PGobject[]{obj1, obj2});

    Struct struct = conn.createStruct("rec_claim_item",
        new Object[]{"CLM003", "HH", "HOSP", "ALL", 0, 0.0});

    try (CallableStatement cs = conn.prepareCall(
        "{ call claim_body_pkg.validate_payment(?, ?, ?, ?, ?) }")) {
      cs.setString(1, "HH");
      cs.setObject(2, struct);
      cs.setArray(3, array);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 2 items", 2, cs.getInt(4));
    }
  }

  // ===================================================================
  // Diagnostic: verify array string encoding
  // ===================================================================

  /**
   * Diagnostic: after fix, PGobject and PgStruct array strings should both
   * contain parentheses and produce the same record literal format.
   */
  @Test
  public void testDiagnosticArrayStringComparison() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    // PGobject without parens (was BUG path, now fixed)
    PGobject obj = new PGobject();
    obj.setType("rec_claim_item");
    obj.setValue("A,B,C,D,1,100");
    Array pgObjArray = pgConn.createArrayOf("tab_claim_items", new PGobject[]{obj});
    String pgObjStr = pgObjArray.toString();

    // PgStruct (always correct path)
    Struct s = conn.createStruct("rec_claim_item",
        new Object[]{"A", "B", "C", "D", 1, 100});
    Array structArray = pgConn.createArrayOf("tab_claim_items", new Struct[]{s});
    String structStr = structArray.toString();

    System.out.println("[DIAGNOSTIC] PGobject array: " + pgObjStr);
    System.out.println("[DIAGNOSTIC] PgStruct array: " + structStr);

    // Both should contain parentheses after fix
    assertTrue("PGobject array should contain parentheses after fix",
        pgObjStr.contains("(") && pgObjStr.contains(")"));
    assertTrue("PgStruct array should contain parentheses",
        structStr.contains("(") && structStr.contains(")"));

    // Both strings should now be equivalent
    System.out.println("[DIAGNOSTIC] Strings equal = " + pgObjStr.equals(structStr));
    assertEquals("PGobject and PgStruct arrays should produce same string after fix",
        structStr, pgObjStr);
  }

  // ===================================================================
  // Bug reproduction: PGobject value WITHOUT commas (single field)
  //
  // Customer error:
  //   ERROR: malformed record literal: "TC067907"
  //   Detail: Missing left parenthesis.
  //   Where: unnamed portal parameter $9 = '...'
  //   SQL: begin CLAIM_BODY.UPDATE_IND_CLAIM_REC(?, ..., ?); end;
  //
  // Root cause: The heuristic in OBJECT_ARRAY.appendArray requires at
  // least one comma (val.indexOf(',') >= 0) to auto-wrap. A PGobject
  // value like "TC067907" (single field, no commas) falls through to
  // the else branch and is emitted without parentheses.
  // ===================================================================

  /**
   * Reproduction: PGobject with single value (no commas) as an element
   * in a TABLE OF composite type array, called via begin...end block.
   *
   * <p>This reproduces the exact customer error:
   *   ERROR: malformed record literal: "TC067907"
   *   Missing left parenthesis.
   */
  @Test
  public void testPGobjectSingleValueNoCommaInBeginEndBlock() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    // Simulate framework creating PGobject with a single value — NO commas, NO parens
    PGobject obj = new PGobject();
    obj.setType("rec_single_code");
    obj.setValue("TC067907");  // single value, no commas — old heuristic won't wrap!

    Array array = pgConn.createArrayOf("tab_single_codes", new PGobject[]{obj});

    try (CallableStatement cs = conn.prepareCall(
        "begin single_code_pkg.process_codes(?, ?, ?, ?); end;")) {
      cs.setString(1, "CO");
      cs.setArray(2, array);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 item", 1, cs.getInt(3));
      assertEquals("Status should be 0", 0, cs.getInt(4));
    }
  }

  /**
   * Same scenario but with { call ... } syntax.
   */
  @Test
  public void testPGobjectSingleValueNoCommaInCallSyntax() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    PGobject obj = new PGobject();
    obj.setType("rec_single_code");
    obj.setValue("TC067907");

    Array array = pgConn.createArrayOf("tab_single_codes", new PGobject[]{obj});

    try (CallableStatement cs = conn.prepareCall(
        "{ call single_code_pkg.process_codes(?, ?, ?, ?) }")) {
      cs.setString(1, "CO");
      cs.setArray(2, array);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 item", 1, cs.getInt(3));
      assertEquals("Status should be 0", 0, cs.getInt(4));
    }
  }

  // ===================================================================
  // Bug reproduction: single PGobject as a non-array record parameter
  //
  // Previous fix covered PGobject elements inside arrays (ArrayEncoding).
  // This test reproduces the case where a PGobject is passed directly
  // as a single record-type parameter via setObject(), going through
  // setPGobject → setString — value is sent as-is without parentheses.
  // ===================================================================

  /**
   * Reproduction: PGobject used directly as a single record-type parameter.
   * The value has NO parentheses and the server expects record literal format.
   *
   * <p>This goes through PgPreparedStatement.setPGobject → setString,
   * which does NOT wrap the value in parentheses.
   */
  @Test
  public void testSinglePGobjectAsRecordParamWithoutParens() throws SQLException {
    // PGobject with composite type but value lacks parentheses
    PGobject obj = new PGobject();
    obj.setType("rec_claim_item");
    obj.setValue("HHF01790,HH,RB,HOSP,15,900.0");  // NO parentheses

    PGobject[] items = new PGobject[]{};
    PgConnection pgConn = conn.unwrap(PgConnection.class);
    Array emptyArray = pgConn.createArrayOf("tab_claim_items", items);

    try (CallableStatement cs = conn.prepareCall(
        "{ call claim_body_pkg.validate_payment(?, ?, ?, ?, ?) }")) {
      cs.setString(1, "HH");
      cs.setObject(2, obj);       // single PGobject for record parameter
      cs.setArray(3, emptyArray);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Status should be 0", 0, cs.getInt(5));
    }
  }

  /**
   * Same as above but with begin...end block syntax.
   */
  @Test
  public void testSinglePGobjectAsRecordParamInBeginEndBlock() throws SQLException {
    PGobject obj = new PGobject();
    obj.setType("rec_claim_item");
    obj.setValue("HHF01790,HH,RB,HOSP,15,900.0");

    PGobject[] items = new PGobject[]{};
    PgConnection pgConn = conn.unwrap(PgConnection.class);
    Array emptyArray = pgConn.createArrayOf("tab_claim_items", items);

    try (CallableStatement cs = conn.prepareCall(
        "begin claim_body_pkg.validate_payment(?, ?, ?, ?, ?); end;")) {
      cs.setString(1, "HH");
      cs.setObject(2, obj);
      cs.setArray(3, emptyArray);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Status should be 0", 0, cs.getInt(5));
    }
  }

  /**
   * Single-field PGobject without commas or parens as a record parameter.
   */
  @Test
  public void testSingleFieldPGobjectAsRecordParam() throws SQLException {
    PGobject obj = new PGobject();
    obj.setType("rec_single_code");
    obj.setValue("TC067907");  // single field, no commas, no parens

    PGobject[] items = new PGobject[]{};
    PgConnection pgConn = conn.unwrap(PgConnection.class);
    Array emptyArray = pgConn.createArrayOf("tab_single_codes", items);

    try (CallableStatement cs = conn.prepareCall(
        "{ call single_code_pkg.process_codes(?, ?, ?, ?) }")) {
      cs.setString(1, "CO");
      cs.setObject(2, emptyArray);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.execute();

      assertEquals("Status should be 0", 0, cs.getInt(4));
    }
  }

  /**
   * Customer's exact pattern: PGobject as single record parameter in a
   * procedure call that also takes a TABLE OF array parameter.
   */
  @Test
  public void testPGobjectRecordAndArrayCombination() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    // Single record param as PGobject (NO parentheses)
    PGobject singleRecord = new PGobject();
    singleRecord.setType("rec_claim_item");
    singleRecord.setValue("HHF01790,HH,RB,HOSP,15,900.0");

    // Array of records as PGobject[] (also NO parentheses — covered by previous fix)
    PGobject arrObj1 = new PGobject();
    arrObj1.setType("rec_claim_item");
    arrObj1.setValue("HHF01790,HH,RB,HOSP,15,900.0");

    PGobject arrObj2 = new PGobject();
    arrObj2.setType("rec_claim_item");
    arrObj2.setValue("HHF01790,HH,EO,HOSP,15,5000.0");

    Array array = pgConn.createArrayOf("tab_claim_items",
        new PGobject[]{arrObj1, arrObj2});

    try (CallableStatement cs = conn.prepareCall(
        "begin claim_body_pkg.validate_payment(?, ?, ?, ?, ?); end;")) {
      cs.setString(1, "HH");
      cs.setObject(2, singleRecord);  // single PGobject record param
      cs.setArray(3, array);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 2 items", 2, cs.getInt(4));
      assertEquals("Status should be 0", 0, cs.getInt(5));
    }
  }

  // ===================================================================
  // Bug reproduction: PGobject with TABLE OF type (not via createArrayOf)
  //
  // The Manulife framework's Query.setArray() may create a PGobject with
  // type="TAB_CLM_BNFT_DTLS" (TABLE OF type, sqlType=ARRAY) and value
  // as raw record fields without parentheses, then call setObject().
  // This bypasses ArrayEncoding and our setPGobject fix only checks
  // for Types.STRUCT, NOT Types.ARRAY.
  // ===================================================================

  /**
   * Reproduction: PGobject with TABLE OF type, value is raw record fields
   * without array braces or record parentheses.
   *
   * <p>Framework likely does: pgobj.setType("TAB_CLM_BNFT_DTLS");
   * pgobj.setValue("HHF01790,HH,EO,HOSP,15,900.0"); cs.setObject(3, pgobj);
   *
   * <p>In setPGobject, the TABLE OF type maps to Types.ARRAY, so our
   * STRUCT-only fix doesn't apply. Value is sent as-is → server fails.
   */
  @Test
  public void testPGobjectWithTableOfTypeNoParens() throws SQLException {
    // PGobject with TABLE OF type (not the record type)
    PGobject obj = new PGobject();
    obj.setType("tab_claim_items");  // TABLE OF type, sqlType=ARRAY
    obj.setValue("HHF01790,HH,RB,HOSP,15,900.0");  // raw record fields, no parens

    Struct struct = conn.createStruct("rec_claim_item",
        new Object[]{"HHF01790", "HH", "HOSP", "ALL", 1, 80000.0});

    try (CallableStatement cs = conn.prepareCall(
        "begin claim_body_pkg.validate_payment(?, ?, ?, ?, ?); end;")) {
      cs.setString(1, "HH");
      cs.setObject(2, struct);
      cs.setObject(3, obj);  // PGobject with TABLE OF type
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Status should be 0", 0, cs.getInt(5));
    }
  }

  /**
   * Same scenario but with array braces: value = "{HHF01790,HH,EO,...}".
   * The braces cause the array parser to split on commas, treating each
   * field as a separate array element instead of a single record.
   */
  @Test
  public void testPGobjectWithTableOfTypeArrayBracesNoParens() throws SQLException {
    PGobject obj = new PGobject();
    obj.setType("tab_claim_items");
    obj.setValue("{HHF01790,HH,RB,HOSP,15,900.0}");  // with braces, no inner parens

    Struct struct = conn.createStruct("rec_claim_item",
        new Object[]{"HHF01790", "HH", "HOSP", "ALL", 1, 80000.0});

    try (CallableStatement cs = conn.prepareCall(
        "begin claim_body_pkg.validate_payment(?, ?, ?, ?, ?); end;")) {
      cs.setString(1, "HH");
      cs.setObject(2, struct);
      cs.setObject(3, obj);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Status should be 0", 0, cs.getInt(5));
    }
  }
}
