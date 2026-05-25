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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.Map;
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

    // Record type for file receive log (SERVICE_TRACK_HK.UPDATE_PROGRESS_DOCS)
    // Simulates REC_FILE_RECV_LOG — 8 fields
    stmt.execute(
        "CREATE OR REPLACE TYPE rec_file_recv_log AS (\n"
            + "  pol_num       varchar(20),\n"
            + "  cert_num      numeric,\n"
            + "  seq_no        numeric,\n"
            + "  doc_type      varchar(20),\n"
            + "  description   varchar(200),\n"
            + "  status        numeric,\n"
            + "  recv_date     timestamp,\n"
            + "  process_date  timestamp\n"
            + ")");

    stmt.execute(
        "CREATE OR REPLACE TYPE tab_file_recv_log AS TABLE OF rec_file_recv_log");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE file_recv_pkg AS\n"
            + "  PROCEDURE update_docs(\n"
            + "    p_items   IN  tab_file_recv_log,\n"
            + "    p_count   OUT integer,\n"
            + "    p_desc    OUT varchar\n"
            + "  );\n"
            + "END file_recv_pkg;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY file_recv_pkg AS\n"
            + "  PROCEDURE update_docs(\n"
            + "    p_items   IN  tab_file_recv_log,\n"
            + "    p_count   OUT integer,\n"
            + "    p_desc    OUT varchar\n"
            + "  ) IS\n"
            + "  BEGIN\n"
            + "    p_count := p_items.COUNT;\n"
            + "    IF p_items.COUNT > 0 THEN\n"
            + "      p_desc := p_items(1).description;\n"
            + "    END IF;\n"
            + "  END;\n"
            + "END file_recv_pkg;");

    // Record type with large varchar field for Chinese text + parentheses testing
    // Simulates REC_PROGRESS_REQ from production (SERVICE_TRACK_HK.UPDATE_PROGRESS_CTL)
    stmt.execute(
        "CREATE OR REPLACE TYPE rec_progress_item AS (\n"
            + "  pol_num      varchar(20),\n"
            + "  pos_num      numeric,\n"
            + "  trxn_date    timestamp,\n"
            + "  pro_num      numeric,\n"
            + "  ppr_num      numeric,\n"
            + "  pos_chg_req  varchar(4000),\n"
            + "  status       varchar(2),\n"
            + "  req_recv_dt  timestamp,\n"
            + "  req_folup_dt timestamp,\n"
            + "  req_req_dt   timestamp,\n"
            + "  form_name    varchar(40),\n"
            + "  rel_ple_num  numeric,\n"
            + "  turn_day     numeric\n"
            + ")");

    stmt.execute(
        "CREATE OR REPLACE TYPE tab_progress_items AS TABLE OF rec_progress_item");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE progress_pkg AS\n"
            + "  PROCEDURE update_progress(\n"
            + "    p_items   IN  tab_progress_items,\n"
            + "    p_count   OUT integer,\n"
            + "    p_status  OUT integer\n"
            + "  );\n"
            + "END progress_pkg;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY progress_pkg AS\n"
            + "  PROCEDURE update_progress(\n"
            + "    p_items   IN  tab_progress_items,\n"
            + "    p_count   OUT integer,\n"
            + "    p_status  OUT integer\n"
            + "  ) IS\n"
            + "  BEGIN\n"
            + "    p_count  := p_items.COUNT;\n"
            + "    p_status := 0;\n"
            + "  END;\n"
            + "END progress_pkg;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("DROP PACKAGE IF EXISTS file_recv_pkg");
    stmt.execute("DROP TYPE IF EXISTS tab_file_recv_log");
    stmt.execute("DROP TYPE IF EXISTS rec_file_recv_log");
    stmt.execute("DROP PACKAGE IF EXISTS progress_pkg");
    stmt.execute("DROP TYPE IF EXISTS tab_progress_items");
    stmt.execute("DROP TYPE IF EXISTS rec_progress_item");
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

  // ===================================================================
  // Additional reproduction paths: other ways the framework might
  // set TABLE OF array parameters that bypass our existing fixes.
  //
  // Customer still reports "malformed record literal: HHF01790" after
  // previous fixes. The framework (Manulife OracleArrayParameter)
  // might use code paths we haven't covered yet.
  // ===================================================================

  /**
   * A minimal java.sql.Array implementation that simulates what the
   * Manulife framework's OracleArrayParameter might produce when its
   * toString() returns an unformatted array string.
   */
  private static class SimpleArrayWrapper implements Array {
    private final String baseTypeName;
    private final String stringValue;

    SimpleArrayWrapper(String baseTypeName, String stringValue) {
      this.baseTypeName = baseTypeName;
      this.stringValue = stringValue;
    }

    @Override
    public String getBaseTypeName() {
      return baseTypeName;
    }

    @Override
    public int getBaseType() {
      return Types.STRUCT;
    }

    @Override
    public String toString() {
      return stringValue;
    }

    @Override
    public Object getArray() {
      return null;
    }

    @Override
    public Object getArray(Map<String, Class<?>> map) {
      return null;
    }

    @Override
    public Object getArray(long index, int count) {
      return null;
    }

    @Override
    public Object getArray(long index, int count, Map<String, Class<?>> map) {
      return null;
    }

    @Override
    public ResultSet getResultSet() {
      return null;
    }

    @Override
    public ResultSet getResultSet(Map<String, Class<?>> map) {
      return null;
    }

    @Override
    public ResultSet getResultSet(long index, int count) {
      return null;
    }

    @Override
    public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) {
      return null;
    }

    @Override
    public void free() {
    }
  }

  /**
   * Path A: setArray with a custom Array implementation (non-PgArray).
   *
   * <p>Framework's OracleArrayParameter likely implements java.sql.Array.
   * Its toString() returns a brace-wrapped flat list of field values:
   * {@code {HHF01790,HH,EO,HOSP,15,900.0}} (no quotes, no record parens).
   *
   * <p>In setArray, non-PgArray goes to {@code setString(i, x.toString(), oid)}.
   * The raw string is sent as-is → server splits on commas → each field
   * is treated as a separate element → "malformed record literal: HHF01790".
   */
  @Test
  public void testCustomArrayImplFlatFieldsNoQuotes() throws SQLException {
    Array customArr = new SimpleArrayWrapper(
        "rec_claim_item",
        "{HHF01790,HH,RB,HOSP,15,900.0}");

    Struct struct = conn.createStruct("rec_claim_item",
        new Object[]{"HHF01790", "HH", "HOSP", "ALL", 1, 80000.0});

    try (CallableStatement cs = conn.prepareCall(
        "begin claim_body_pkg.validate_payment(?, ?, ?, ?, ?); end;")) {
      cs.setString(1, "HH");
      cs.setObject(2, struct);
      cs.setArray(3, customArr);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Status should be 0", 0, cs.getInt(5));
    }
  }

  /**
   * Path B: setArray with custom Array whose toString() has quotes but
   * no record parentheses: {@code {"HHF01790,HH,RB,HOSP,15,900.0"}}.
   *
   * <p>Server extracts the quoted element (removing quotes) and gets
   * {@code HHF01790,HH,RB,HOSP,15,900.0} — still no parentheses.
   */
  @Test
  public void testCustomArrayImplQuotedRowNoParens() throws SQLException {
    // Simulate a framework that quotes each row but doesn't add record parens
    Array customArr = new SimpleArrayWrapper(
        "rec_claim_item",
        "{\"HHF01790,HH,RB,HOSP,15,900.0\"}");

    Struct struct = conn.createStruct("rec_claim_item",
        new Object[]{"HHF01790", "HH", "HOSP", "ALL", 1, 80000.0});

    try (CallableStatement cs = conn.prepareCall(
        "begin claim_body_pkg.validate_payment(?, ?, ?, ?, ?); end;")) {
      cs.setString(1, "HH");
      cs.setObject(2, struct);
      cs.setArray(3, customArr);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Status should be 0", 0, cs.getInt(5));
    }
  }

  /**
   * Path C: createArrayOf with String[] where each String is a
   * comma-separated row without record parentheses.
   *
   * <p>STRING_ARRAY encoder quotes each string element but does not
   * add record parentheses. Produces: {@code {"HHF01790,HH,RB,HOSP,15,900.0"}}.
   * Server extracts unquoted {@code HHF01790,HH,...} and fails.
   */
  @Test
  public void testCreateArrayOfWithStringRowNoParens() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    // Framework might convert each row to a comma-separated String
    String[] rows = new String[]{"HHF01790,HH,RB,HOSP,15,900.0"};
    Array array = pgConn.createArrayOf("rec_claim_item", rows);

    Struct struct = conn.createStruct("rec_claim_item",
        new Object[]{"HHF01790", "HH", "HOSP", "ALL", 1, 80000.0});

    try (CallableStatement cs = conn.prepareCall(
        "begin claim_body_pkg.validate_payment(?, ?, ?, ?, ?); end;")) {
      cs.setString(1, "HH");
      cs.setObject(2, struct);
      cs.setArray(3, array);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Status should be 0", 0, cs.getInt(5));
    }
  }

  /**
   * Path D: createArrayOf with Object[] of String (not PGobject).
   *
   * <p>OBJECT_ARRAY encoder's else branch calls
   * {@code PgArray.escapeArrayElement(sb, array[i].toString())} — quotes the
   * string but does NOT add record parentheses.
   */
  @Test
  public void testCreateArrayOfWithObjectStringNoParens() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    Object[] rows = new Object[]{"HHF01790,HH,RB,HOSP,15,900.0"};
    Array array = pgConn.createArrayOf("rec_claim_item", rows);

    Struct struct = conn.createStruct("rec_claim_item",
        new Object[]{"HHF01790", "HH", "HOSP", "ALL", 1, 80000.0});

    try (CallableStatement cs = conn.prepareCall(
        "begin claim_body_pkg.validate_payment(?, ?, ?, ?, ?); end;")) {
      cs.setString(1, "HH");
      cs.setObject(2, struct);
      cs.setArray(3, array);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Status should be 0", 0, cs.getInt(5));
    }
  }

  /**
   * Path E: createArrayOf with flattened field values as individual
   * String elements — each field is a separate array element.
   *
   * <p>This is a FRAMEWORK-LEVEL USAGE BUG: individual field values
   * are passed as separate array elements instead of being grouped
   * into composite records. The driver wraps each element as a
   * single-field record like {@code {"(HHF01790)","(HH)",...}}, but
   * the server expects 6-field records — resulting in a type mismatch.
   *
   * <p>This test verifies the driver does NOT crash (no NPE etc.)
   * but accepts that the server will reject the malformed records.
   */
  @Test(expected = SQLException.class)
  public void testCreateArrayOfWithFlattenedFields() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    // Framework might flatten row fields into individual array elements
    String[] fields = new String[]{"HHF01790", "HH", "RB", "HOSP", "15", "900.0"};
    Array array = pgConn.createArrayOf("rec_claim_item", fields);

    Struct struct = conn.createStruct("rec_claim_item",
        new Object[]{"HHF01790", "HH", "HOSP", "ALL", 1, 80000.0});

    try (CallableStatement cs = conn.prepareCall(
        "begin claim_body_pkg.validate_payment(?, ?, ?, ?, ?); end;")) {
      cs.setString(1, "HH");
      cs.setObject(2, struct);
      cs.setArray(3, array);
      cs.registerOutParameter(4, Types.INTEGER);
      cs.registerOutParameter(5, Types.INTEGER);
      cs.execute();

      assertEquals("Status should be 0", 0, cs.getInt(5));
    }
  }

  // ===================================================================
  // Chinese text with ASCII parentheses reproduction:
  //
  // Customer production scenario (SERVICE_TRACK_HK.UPDATE_PROGRESS_CTL):
  //   Parameter 11 = OracleArrayParameter:
  //     table name=(TAB_PROGRESS_REQ) type name=(REC_PROGRESS_REQ)
  //     row list=([0=3800180451|15482|2026-07-02 00:00:00.0|1|null|
  //       ------...
  //       MED020 - Copy of consultation summary / note.
  //       MED020 - 會診摘要副本 ( 詳情請參閱以上英文版 )。
  //       |0|2027-06-18 00:00:00.0|...|28])
  //
  // Server error:
  //   ERROR: malformed record literal: "(3800180451,...,
  //     會診摘要副本 ( 詳情請參閱以上英文版 )。,0,...)"
  //   DETAIL: Too few columns.
  //
  // Root cause: The description field contains ASCII '(' and ')' which
  // cause PostgreSQL's record_in parser to misparse field boundaries
  // unless the field is properly double-quoted.
  // ===================================================================

  /** Description field value containing Chinese text with ASCII parentheses. */
  private static final String CHINESE_DESC_WITH_PARENS =
      "--------------------------------------------------------------------------"
          + "----------------------\r\n"
          + "        MED020 - Copy of consultation summary / note.\r\n"
          + "        MED020 - 會診摘要副本 "
          + " (詳情請參閱以上英文版 )。";

  /**
   * Path F: PGobject[] with Chinese text containing ASCII parentheses.
   *
   * <p>Reproduces the exact production bug from SERVICE_TRACK_HK:
   * OracleArrayParameter creates PGobject with comma-separated fields,
   * where the 6th field (pos_chg_req) contains multiline Chinese text
   * with ASCII '(' and ')'. Without per-field quoting, server reports
   * "malformed record literal: Too few columns".
   */
  @Test
  public void testPGobjectWithChineseParensNowSucceeds() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    // Simulate OracleArrayParameter's raw comma-separated record value
    // matching production data from SERVICE_TRACK_HK.UPDATE_PROGRESS_CTL
    String rawRecord = String.join(",",
        "3800180451",
        "15482",
        "2026-07-02 00:00:00.0",
        "1",
        "",                              // ppr_num null
        CHINESE_DESC_WITH_PARENS,        // pos_chg_req with ( )
        "0",
        "2027-06-18 00:00:00.0",
        "2027-06-18 00:00:00.0",
        "2026-07-02 14:09:00.0",
        "FORM_NAME",
        "1",
        "28");

    PGobject obj = new PGobject();
    obj.setType("rec_progress_item");
    obj.setValue(rawRecord);  // NO parentheses — simulates OracleArrayParameter

    Array array = pgConn.createArrayOf("tab_progress_items", new PGobject[]{obj});
    System.out.println("[DIAG-CN] PGobject array string = " + array.toString());

    try (CallableStatement cs = conn.prepareCall(
        "begin progress_pkg.update_progress(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  /**
   * Path G: Object[][] with Chinese text containing ASCII parentheses.
   *
   * <p>Simulates OracleArrayParameter.getOracleArray(conn) which builds
   * Object[][] and calls conn.createArrayOf(tableName, data).
   * This is the primary production code path.
   */
  @Test
  public void testObject2DArrayWithChineseParensSucceeds() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    Object[][] data = new Object[1][];
    data[0] = new Object[]{
        "3800180451",                                         // pol_num
        Integer.valueOf(15482),                               // pos_num
        java.sql.Timestamp.valueOf("2026-07-02 00:00:00.0"),  // trxn_date
        Integer.valueOf(1),                                   // pro_num
        null,                                                 // ppr_num
        CHINESE_DESC_WITH_PARENS,                             // pos_chg_req with ( )
        "0",                                                  // status
        java.sql.Timestamp.valueOf("2027-06-18 00:00:00.0"),  // req_recv_dt
        java.sql.Timestamp.valueOf("2027-06-18 00:00:00.0"),  // req_folup_dt
        java.sql.Timestamp.valueOf("2026-07-02 14:09:00.0"),  // req_req_dt
        "FORM_NAME",                                          // form_name
        Integer.valueOf(1),                                   // rel_ple_num
        Integer.valueOf(28)                                   // turn_day
    };

    Array array = pgConn.createArrayOf("tab_progress_items", data);
    System.out.println("[DIAG-CN] Object[][] array string = " + array.toString());

    try (CallableStatement cs = conn.prepareCall(
        "begin progress_pkg.update_progress(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  /**
   * Path H: Custom Array (simulating OracleArrayParameter.toString()) with
   * Chinese text containing ASCII parentheses.
   *
   * <p>If OracleArrayParameter implements java.sql.Array and its toString()
   * returns a brace-wrapped comma-separated string, setArray() calls
   * wrapArrayRecordLiterals() which must properly quote the field with
   * parentheses.
   */
  @Test
  public void testCustomArrayWithChineseParensNowSucceeds() throws SQLException {
    // Build the raw comma-separated record (same as PGobject test)
    String rawRecord = String.join(",",
        "3800180451",
        "15482",
        "2026-07-02 00:00:00.0",
        "1",
        "",
        CHINESE_DESC_WITH_PARENS,
        "0",
        "2027-06-18 00:00:00.0",
        "2027-06-18 00:00:00.0",
        "2026-07-02 14:09:00.0",
        "FORM_NAME",
        "1",
        "28");

    // Simulate OracleArrayParameter.toString() returning brace-wrapped raw fields
    Array customArr = new SimpleArrayWrapper(
        "tab_progress_items",
        "{" + rawRecord + "}");

    try (CallableStatement cs = conn.prepareCall(
        "begin progress_pkg.update_progress(?, ?, ?); end;")) {
      cs.setArray(1, customArr);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  /**
   * Path I: Custom Array with no braces — raw comma-separated record.
   *
   * <p>Some frameworks may return toString() without braces.
   */
  @Test
  public void testCustomArrayNoBracesWithChineseParensSucceeds() throws SQLException {
    String rawRecord = String.join(",",
        "3800180451",
        "15482",
        "2026-07-02 00:00:00.0",
        "1",
        "",
        CHINESE_DESC_WITH_PARENS,
        "0",
        "2027-06-18 00:00:00.0",
        "2027-06-18 00:00:00.0",
        "2026-07-02 14:09:00.0",
        "FORM_NAME",
        "1",
        "28");

    // toString() returns raw record without braces
    Array customArr = new SimpleArrayWrapper(
        "tab_progress_items",
        rawRecord);

    try (CallableStatement cs = conn.prepareCall(
        "begin progress_pkg.update_progress(?, ?, ?); end;")) {
      cs.setArray(1, customArr);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  // ===================================================================
  // Customer exact code path reproduction:
  //
  // OracleArrayParameter.getOracleArray(conn) does:
  //   Struct aStruct = conn.createStruct(arrayTypeName, aRow);
  //   structs[i] = aStruct;
  //   return conn.createArrayOf(arrayTableName, structs);
  //
  // Then the framework calls:
  //   query.setArray(11, oArray.getOracleArray(conn));
  //
  // This is the Struct[] path through ArrayEncoding.OBJECT_ARRAY
  // which calls getAttributes() on each Struct element.
  // ===================================================================

  /**
   * Path J: Exact customer code path — OracleArrayParameter.getOracleArray(conn).
   *
   * <p>Reproduces the exact production scenario from NbtsManagerDAO_HK.saveProgReport:
   * <pre>
   * SQL: begin SERVICE_TRACK_HK.UPDATE_PROGRESS_CTL(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?); end;
   *
   * Parameter 11 = OracleArrayParameter:
   *   table name=(TAB_PROGRESS_REQ) type name=(REC_PROGRESS_REQ)
   *   row list=([0=3800180451|15482|2026-07-02 00:00:00.0|1|null|
   *     ------...MED020 - Copy of consultation summary / note.
   *     MED020 - 會診摘要副本 （ 詳情請參閱以上英文版 )。
   *     |0|2027-06-18 00:00:00.0|2027-06-18 00:00:00.0|2026-07-02 14:09:00.0|FORM_NAME|1|28])
   * </pre>
   *
   * <p>Customer code path:
   * <pre>
   * OracleArrayParameter oArray = new OracleArrayParameter("TAB_PROGRESS_REQ", "REC_PROGRESS_REQ");
   * Object[] row = { holdingPK.getContractNr(), ..., docVO.getDoc().getDesc(), ... };
   * oArray.addObjectArray(row);
   * query.setArray(11, oArray.getOracleArray(conn));
   * </pre>
   *
   * <p>getOracleArray(conn) internally does:
   * <pre>
   * Struct aStruct = conn.createStruct(arrayTypeName, aRow);
   * return conn.createArrayOf(arrayTableName, structs);
   * </pre>
   */
  @Test
  public void testOracleArrayParameterStructPathWithChineseParens() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    // ---- Build Object[] row exactly as customer's NbtsManagerDAO_HK does ----
    // Fields match REC_PROGRESS_REQ (13 columns from production log)
    Object[] row = new Object[]{
        "3800180451",                                             // POL_NUM (holdingPK.getContractNr())
        Integer.valueOf(15482),                                   // POS_NUM (NbtsDAOHelper.stringToInteger(nbtsPK.getPosNr()))
        java.sql.Timestamp.valueOf("2026-07-02 00:00:00.0"),      // TRXN_DATE
        Integer.valueOf(1),                                       // PRO_NUM
        null,                                                     // PPR_NUM (docVO.getDocSeq() null)
        CHINESE_DESC_WITH_PARENS,                                 // POS_CHG_REQ (docVO.getDoc().getDesc())
        "0",                                                      // STATUS (docVO.getStatus())
        java.sql.Timestamp.valueOf("2027-06-18 00:00:00.0"),      // REQ_RECV_DT
        java.sql.Timestamp.valueOf("2027-06-18 00:00:00.0"),      // REQ_FOLUP_DT
        java.sql.Timestamp.valueOf("2026-07-02 14:09:00.0"),      // REQ_REQ_DT
        "FORM_NAME",                                              // FORM_NAME (docVO.getFileName())
        Integer.valueOf(1),                                       // REL_PLE_NUM
        Integer.valueOf(28)                                       // TURN_DAY (docVO.getDoc().getTurnDay())
    };

    // ---- Simulate OracleArrayParameter.getOracleArray(conn) ----
    // Step 1: Struct aStruct = conn.createStruct(arrayTypeName, aRow);
    Struct aStruct = pgConn.createStruct("rec_progress_item", row);

    // Step 2: return conn.createArrayOf(arrayTableName, structs);
    Struct[] structs = new Struct[]{aStruct};
    Array array = pgConn.createArrayOf("tab_progress_items", structs);

    System.out.println("[DIAG-CUSTOMER] Struct[] array string = " + array.toString());

    // ---- Simulate query.setArray(11, oArray.getOracleArray(conn)) ----
    try (CallableStatement cs = conn.prepareCall(
        "begin progress_pkg.update_progress(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  /**
   * Path K: Same as Path J but with empty array (parameter 10 in production).
   *
   * <p>Production parameter 10 is an empty OracleArrayParameter:
   * {@code table name=(TAB_PROGRESS_STAT) type name=(REC_PROGRESS_STAT) row list=()}
   *
   * <p>This tests the combination of an empty Struct[] array alongside
   * a non-empty one (matching the production call pattern).
   */
  @Test
  public void testOracleArrayParameterEmptyAndNonEmptyCombination() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    // ---- Parameter 10: empty OracleArrayParameter (TAB_PROGRESS_STAT) ----
    Struct[] emptyStructs = new Struct[0];
    Array emptyArray = pgConn.createArrayOf("tab_progress_items", emptyStructs);

    // ---- Parameter 11: non-empty with Chinese parentheses data ----
    Object[] row = new Object[]{
        "3800180451",
        Integer.valueOf(15482),
        java.sql.Timestamp.valueOf("2026-07-02 00:00:00.0"),
        Integer.valueOf(1),
        null,
        CHINESE_DESC_WITH_PARENS,
        "0",
        java.sql.Timestamp.valueOf("2027-06-18 00:00:00.0"),
        java.sql.Timestamp.valueOf("2027-06-18 00:00:00.0"),
        java.sql.Timestamp.valueOf("2026-07-02 14:09:00.0"),
        "FORM_NAME",
        Integer.valueOf(1),
        Integer.valueOf(28)
    };

    Struct aStruct = pgConn.createStruct("rec_progress_item", row);
    Struct[] structs = new Struct[]{aStruct};
    Array dataArray = pgConn.createArrayOf("tab_progress_items", structs);

    // Call with non-empty array (simulating parameter 11 being the one that matters)
    try (CallableStatement cs = conn.prepareCall(
        "begin progress_pkg.update_progress(?, ?, ?); end;")) {
      cs.setArray(1, dataArray);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }

    // Also verify empty array doesn't cause issues
    try (CallableStatement cs = conn.prepareCall(
        "begin progress_pkg.update_progress(?, ?, ?); end;")) {
      cs.setArray(1, emptyArray);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Empty array should count 0 rows", 0, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  // ===================================================================
  // Reproduction: SERVICE_TRACK_HK.UPDATE_PROGRESS_DOCS
  //
  // ClaimsManagerDAO_HK.saveDocumentLetter — Parameter 5:
  //   OracleArrayParameter: table name=(TAB_FILE_RECV_LOG)
  //     type name=(REC_FILE_RECV_LOG)
  //   row list=([0=0135941780|958844|null|null|
  //     *Death certificate (See Remarks)|1|
  //     2026-01-01 00:00:00.0|2025-01-01 00:00:00.0])
  //
  // Server error:
  //   ERROR: malformed record literal:
  //     "(0135941780,958844,,,*Death certificate (See Remarks),1,
  //      2026-01-01 00:00:00.0,2025-01-01 00:00:00.0)"
  //   DETAIL: Too few columns.
  //
  // Root cause: The description field "*Death certificate (See Remarks)"
  // contains ASCII '(' and ')' which cause PostgreSQL's record_in parser
  // to misparse field boundaries — it sees ')' as record end.
  // ===================================================================

  /**
   * Path L: Exact reproduction of ClaimsManagerDAO_HK.saveDocumentLetter bug.
   *
   * <p>The field value {@code "*Death certificate (See Remarks)"} contains
   * ASCII parentheses. Customer code path:
   * <pre>
   * OracleArrayParameter oArray = new OracleArrayParameter("TAB_FILE_RECV_LOG", "REC_FILE_RECV_LOG");
   * Object[] row = {polNum, certNum, null, null, "*Death certificate (See Remarks)", 1, ts1, ts2};
   * oArray.addObjectArray(row);
   * query.setArray(5, oArray.getOracleArray(conn));
   * </pre>
   *
   * <p>getOracleArray(conn) internally does:
   * <pre>
   * Struct aStruct = conn.createStruct("REC_FILE_RECV_LOG", aRow);
   * return conn.createArrayOf("TAB_FILE_RECV_LOG", structs);
   * </pre>
   */
  @Test
  public void testDeathCertificateWithParensStructPath() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    // Exact production data from the screenshot
    Object[] row = new Object[]{
        "0135941780",                                           // pol_num
        Integer.valueOf(958844),                                // cert_num
        null,                                                   // seq_no (null)
        null,                                                   // doc_type (null)
        "*Death certificate (See Remarks)",                     // description ← has ()
        Integer.valueOf(1),                                     // status
        java.sql.Timestamp.valueOf("2026-01-01 00:00:00.0"),    // recv_date
        java.sql.Timestamp.valueOf("2025-01-01 00:00:00.0")     // process_date
    };

    // Simulate OracleArrayParameter.getOracleArray(conn)
    Struct aStruct = pgConn.createStruct("rec_file_recv_log", row);
    Struct[] structs = new Struct[]{aStruct};
    Array array = pgConn.createArrayOf("tab_file_recv_log", structs);

    System.out.println("[DIAG-CLAIMS] Struct[] array = " + array.toString());

    try (CallableStatement cs = conn.prepareCall(
        "begin file_recv_pkg.update_docs(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Description should be preserved intact",
          "*Death certificate (See Remarks)", cs.getString(3));
    }
  }

  /**
   * Path M: Same as Path L but using PGobject[] path (alternative framework usage).
   *
   * <p>Some wrappers create PGobject instead of Struct. This verifies
   * the PGobject branch also handles parentheses in the description field.
   */
  @Test
  public void testDeathCertificateWithParensPGobjectPath() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    // PGobject with comma-separated values, no parentheses wrapper
    PGobject obj = new PGobject();
    obj.setType("rec_file_recv_log");
    obj.setValue("0135941780,958844,,,*Death certificate (See Remarks),1,"
        + "2026-01-01 00:00:00.0,2025-01-01 00:00:00.0");

    Array array = pgConn.createArrayOf("tab_file_recv_log", new PGobject[]{obj});

    System.out.println("[DIAG-CLAIMS-PGO] PGobject[] array = " + array.toString());

    try (CallableStatement cs = conn.prepareCall(
        "begin file_recv_pkg.update_docs(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Description should be preserved intact",
          "*Death certificate (See Remarks)", cs.getString(3));
    }
  }

  /**
   * Path N: EXACT customer OracleArrayParameter.getOracleArray(conn) code path.
   *
   * <p>CRITICAL: The real OracleArrayParameter.java does NOT use createStruct!
   * It builds Object[][] and passes it directly to createArrayOf:
   * <pre>
   * Object[][] data = new Object[rowList.size()][];
   * data[i] = (Object[]) rowList.get(i);
   * return conn.createArrayOf(arrayTableName, data);
   * </pre>
   *
   * <p>This goes through PgConnection.buildCompositeArrayFromObject2D (NOT
   * ArrayEncoding.OBJECT_ARRAY Struct branch). The quoting is done by
   * PgConnection.needsQuotingInRecord, not ArrayEncoding.needsRecordQuoting.
   */
  @Test
  public void testDeathCertificateObject2DPath() throws SQLException {
    // ---- Simulate OracleArrayParameter.getOracleArray(conn) ----
    // Customer builds Object[][] (NOT Struct[])
    Object[][] data = new Object[1][];
    data[0] = new Object[]{
        "0135941780",                                           // pol_num
        Integer.valueOf(958844),                                // cert_num
        null,                                                   // seq_no
        null,                                                   // doc_type
        "*Death certificate (See Remarks)",                     // description ← has ()
        Integer.valueOf(1),                                     // status
        java.sql.Timestamp.valueOf("2026-01-01 00:00:00.0"),    // recv_date
        java.sql.Timestamp.valueOf("2025-01-01 00:00:00.0")     // process_date
    };

    // This is the exact call in OracleArrayParameter.getOracleArray:
    // return conn.createArrayOf(arrayTableName, data);
    Array array = conn.createArrayOf("tab_file_recv_log", data);

    System.out.println("[DIAG-CUSTOMER-OBJ2D] Object[][] array = " + array.toString());

    try (CallableStatement cs = conn.prepareCall(
        "begin file_recv_pkg.update_docs(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Description should be preserved intact",
          "*Death certificate (See Remarks)", cs.getString(3));
    }
  }

  /**
   * Path O: Full simulation of OracleArrayParameter with addObjectArray + getOracleArray.
   *
   * <p>Reproduces the entire customer flow:
   * <pre>
   * OracleArrayParameter oArray = new OracleArrayParameter("TAB_FILE_RECV_LOG", "REC_FILE_RECV_LOG");
   * Object[] row = {...};
   * oArray.addObjectArray(row);
   * query.setArray(5, oArray.getOracleArray(conn));
   * </pre>
   *
   * <p>getOracleArray internally does:
   * <pre>
   * Object[][] data = new Object[rowList.size()][];
   * data[i] = (Object[]) rowList.get(i);
   * return conn.createArrayOf(arrayTableName, data);
   * </pre>
   */
  @Test
  public void testFullOracleArrayParameterSimulation() throws SQLException {
    // ---- Simulate addObjectArray calls (may add multiple rows) ----
    java.util.ArrayList<Object[]> rowList = new java.util.ArrayList<Object[]>();

    // Row 0: the problematic row with parentheses in description
    rowList.add(new Object[]{
        "0135941780",
        Integer.valueOf(958844),
        null,
        null,
        "*Death certificate (See Remarks)",
        Integer.valueOf(1),
        java.sql.Timestamp.valueOf("2026-01-01 00:00:00.0"),
        java.sql.Timestamp.valueOf("2025-01-01 00:00:00.0")
    });

    // Row 1: another row without parentheses (to test multi-row scenario)
    rowList.add(new Object[]{
        "0135941780",
        Integer.valueOf(958844),
        null,
        null,
        "Medical report",
        Integer.valueOf(0),
        java.sql.Timestamp.valueOf("2026-02-01 00:00:00.0"),
        java.sql.Timestamp.valueOf("2025-02-01 00:00:00.0")
    });

    // ---- Simulate getOracleArray(conn) ----
    Object[][] data = new Object[rowList.size()][];
    for (int i = 0; i < rowList.size(); i++) {
      data[i] = rowList.get(i);
    }
    Array array = conn.createArrayOf("tab_file_recv_log", data);

    System.out.println("[DIAG-FULL-SIM] Multi-row array = " + array.toString());

    try (CallableStatement cs = conn.prepareCall(
        "begin file_recv_pkg.update_docs(?, ?, ?); end;")) {
      cs.setArray(1, array);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();

      assertEquals("Should count 2 rows", 2, cs.getInt(2));
      // p_desc returns 1st row's description
      assertEquals("Description should be preserved intact",
          "*Death certificate (See Remarks)", cs.getString(3));
    }
  }

  // ===================================================================
  // Simulate EXACT customer framework call chain:
  //
  // From OracleStructSQLType.setInputParameter we know the pattern:
  //   1. oArray = ((OracleArrayParameter)obj).getOracleArray(stat.getConnection())
  //   2. stat.setObject(position, oArray, Types.ARRAY)
  //
  // Key differences from our earlier tests:
  //   - Connection is obtained via stat.getConnection() (not our test conn)
  //   - setObject(pos, array, Types.ARRAY) is used instead of setArray()
  //
  // OracleArraySQLType.java is unavailable but based on OracleStructSQLType
  // pattern, the ARRAY handler very likely follows the same structure.
  // ===================================================================

  /**
   * Path P: Simulate OracleArraySQLType.setInputParameter — Object[][] version.
   *
   * <p>Matches the actual OracleArrayParameter.java code (Object[][] path):
   * <pre>
   * // In OracleArraySQLType.setInputParameter:
   * Array oArray = ((OracleArrayParameter)obj).getOracleArray(stat.getConnection());
   * ((OraclePreparedStatement)stat).setObject(position, oArray, Types.ARRAY);
   *
   * // In OracleArrayParameter.getOracleArray:
   * Object[][] data = new Object[rowList.size()][];
   * data[i] = (Object[]) rowList.get(i);
   * return conn.createArrayOf(arrayTableName, data);
   * </pre>
   */
  @Test
  public void testFrameworkFlowObject2DSetObjectTypesArray() throws SQLException {
    // Step 1: Framework creates CallableStatement
    try (CallableStatement cs = conn.prepareCall(
        "begin file_recv_pkg.update_docs(?, ?, ?); end;")) {

      // Step 2: Simulate getOracleArray(stat.getConnection())
      //   — key: use stat.getConnection() instead of our test 'conn'
      Connection stmtConn = cs.getConnection();

      Object[][] data = new Object[1][];
      data[0] = new Object[]{
          "0135941780",
          Integer.valueOf(958844),
          null,
          null,
          "*Death certificate (See Remarks)",   // ← has ()
          Integer.valueOf(1),
          java.sql.Timestamp.valueOf("2026-01-01 00:00:00.0"),
          java.sql.Timestamp.valueOf("2025-01-01 00:00:00.0")
      };
      Array array = stmtConn.createArrayOf("tab_file_recv_log", data);
      System.out.println("[DIAG-P] setObject(Types.ARRAY) array = " + array.toString());

      // Step 3: Simulate OracleArraySQLType calling setObject(pos, array, Types.ARRAY)
      //   — NOT setArray(pos, array)
      cs.setObject(1, array, Types.ARRAY);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Description should be preserved intact",
          "*Death certificate (See Remarks)", cs.getString(3));
    }
  }

  /**
   * Path Q: Simulate framework flow — Struct[] version (IFP_26_RL06_CSMD_24309).
   *
   * <p>Matches the test.java code (createStruct → Struct[] path):
   * <pre>
   * // In OracleArrayParameter.getOracleArray (IFP_26_RL06_CSMD_24309 version):
   * Struct aStruct = conn.createStruct(arrayTypeName, aRow);
   * structs[i] = aStruct;
   * return conn.createArrayOf(arrayTableName, structs);
   *
   * // In OracleArraySQLType.setInputParameter:
   * stat.setObject(position, oArray, Types.ARRAY);
   * </pre>
   */
  @Test
  public void testFrameworkFlowStructArraySetObjectTypesArray() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "begin file_recv_pkg.update_docs(?, ?, ?); end;")) {

      Connection stmtConn = cs.getConnection();

      Object[] row = new Object[]{
          "0135941780",
          Integer.valueOf(958844),
          null,
          null,
          "*Death certificate (See Remarks)",
          Integer.valueOf(1),
          java.sql.Timestamp.valueOf("2026-01-01 00:00:00.0"),
          java.sql.Timestamp.valueOf("2025-01-01 00:00:00.0")
      };

      // createStruct → Struct[] → createArrayOf (IFP_26_RL06_CSMD_24309 path)
      Struct aStruct = stmtConn.createStruct("rec_file_recv_log", row);
      Struct[] structs = new Struct[]{aStruct};
      Array array = stmtConn.createArrayOf("tab_file_recv_log", structs);
      System.out.println("[DIAG-Q] Struct[] + setObject(Types.ARRAY) = " + array.toString());

      // setObject(pos, array, Types.ARRAY) — not setArray
      cs.setObject(1, array, Types.ARRAY);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Description should be preserved intact",
          "*Death certificate (See Remarks)", cs.getString(3));
    }
  }

  /**
   * Path R: Full framework simulation with multiple rows — Object[][] + setObject.
   *
   * <p>Simulates the complete customer flow for SERVICE_TRACK_HK.UPDATE_PROGRESS_DOCS:
   * <pre>
   * OracleArrayParameter oArray = new OracleArrayParameter("TAB_FILE_RECV_LOG", "REC_FILE_RECV_LOG");
   * oArray.addObjectArray(row1);
   * oArray.addObjectArray(row2);
   *
   * // StatementParameter.setInputParameter → OracleArraySQLType.setInputParameter:
   * Array sqlArray = oArray.getOracleArray(stat.getConnection());
   * stat.setObject(position, sqlArray, Types.ARRAY);
   * </pre>
   */
  @Test
  public void testFrameworkFlowMultiRowSetObjectTypesArray() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "begin file_recv_pkg.update_docs(?, ?, ?); end;")) {

      Connection stmtConn = cs.getConnection();

      // Simulate OracleArrayParameter.addObjectArray (multiple rows)
      java.util.ArrayList<Object[]> rowList = new java.util.ArrayList<Object[]>();
      rowList.add(new Object[]{
          "0135941780", Integer.valueOf(958844), null, null,
          "*Death certificate (See Remarks)",
          Integer.valueOf(1),
          java.sql.Timestamp.valueOf("2026-01-01 00:00:00.0"),
          java.sql.Timestamp.valueOf("2025-01-01 00:00:00.0")
      });
      rowList.add(new Object[]{
          "0135941780", Integer.valueOf(958844), null, null,
          "Medical report (no special chars)",
          Integer.valueOf(0),
          java.sql.Timestamp.valueOf("2026-02-01 00:00:00.0"),
          java.sql.Timestamp.valueOf("2025-02-01 00:00:00.0")
      });

      // Simulate getOracleArray(stat.getConnection()) — Object[][] path
      Object[][] data = new Object[rowList.size()][];
      for (int i = 0; i < rowList.size(); i++) {
        data[i] = rowList.get(i);
      }
      Array array = stmtConn.createArrayOf("tab_file_recv_log", data);
      System.out.println("[DIAG-R] Multi-row + setObject(Types.ARRAY) = " + array.toString());

      cs.setObject(1, array, Types.ARRAY);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();

      assertEquals("Should count 2 rows", 2, cs.getInt(2));
      assertEquals("Description should be preserved intact",
          "*Death certificate (See Remarks)", cs.getString(3));
    }
  }

  /**
   * Path S: Chinese text with parentheses — full framework simulation.
   *
   * <p>Reproduces SERVICE_TRACK_HK.UPDATE_PROGRESS_CTL scenario
   * with OracleArraySQLType.setInputParameter pattern.
   */
  @Test
  public void testFrameworkFlowChineseParensSetObjectTypesArray() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "begin progress_pkg.update_progress(?, ?, ?); end;")) {

      Connection stmtConn = cs.getConnection();

      Object[][] data = new Object[1][];
      data[0] = new Object[]{
          "3800180451",
          Integer.valueOf(15482),
          java.sql.Timestamp.valueOf("2026-07-02 00:00:00.0"),
          Integer.valueOf(1),
          null,
          CHINESE_DESC_WITH_PARENS,
          "0",
          java.sql.Timestamp.valueOf("2027-06-18 00:00:00.0"),
          java.sql.Timestamp.valueOf("2027-06-18 00:00:00.0"),
          java.sql.Timestamp.valueOf("2026-07-02 14:09:00.0"),
          "FORM_NAME",
          Integer.valueOf(1),
          Integer.valueOf(28)
      };

      Array array = stmtConn.createArrayOf("tab_progress_items", data);
      System.out.println("[DIAG-S] Chinese parens + setObject(Types.ARRAY) = " + array.toString());

      cs.setObject(1, array, Types.ARRAY);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  /**
   * Path T: Struct[] with Chinese parens — full framework simulation.
   */
  @Test
  public void testFrameworkFlowStructChineseParensSetObjectTypesArray() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "begin progress_pkg.update_progress(?, ?, ?); end;")) {

      Connection stmtConn = cs.getConnection();

      Object[] row = new Object[]{
          "3800180451",
          Integer.valueOf(15482),
          java.sql.Timestamp.valueOf("2026-07-02 00:00:00.0"),
          Integer.valueOf(1),
          null,
          CHINESE_DESC_WITH_PARENS,
          "0",
          java.sql.Timestamp.valueOf("2027-06-18 00:00:00.0"),
          java.sql.Timestamp.valueOf("2027-06-18 00:00:00.0"),
          java.sql.Timestamp.valueOf("2026-07-02 14:09:00.0"),
          "FORM_NAME",
          Integer.valueOf(1),
          Integer.valueOf(28)
      };

      // createStruct → Struct[] path
      Struct aStruct = stmtConn.createStruct("rec_progress_item", row);
      Struct[] structs = new Struct[]{aStruct};
      Array array = stmtConn.createArrayOf("tab_progress_items", structs);
      System.out.println("[DIAG-T] Struct[] + Chinese parens + setObject = " + array.toString());

      cs.setObject(1, array, Types.ARRAY);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();

      assertEquals("Should count 1 row", 1, cs.getInt(2));
      assertEquals("Status should be 0", 0, cs.getInt(3));
    }
  }

  // ===========================================================================
  // Path U: Unit tests for fixCompositeArrayElements fix —
  //   Verifies that record literals starting with '(' but containing unquoted
  //   fields with ')' are detected and properly re-quoted.
  // ===========================================================================

  /**
   * Path U1: Test recordNeedsFieldReQuoting detection.
   * A record literal like (f1,f2,*Death certificate (See Remarks),f4)
   * has an unquoted ')' which would break the server's record_in parser.
   */
  @Test
  public void testRecordNeedsFieldReQuoting_unquotedParens() {
    // Unquoted field with ')' → needs re-quoting
    assertTrue("Should detect unquoted ) in field",
        com.aliyun.polardb2.jdbc.PgArray.recordNeedsFieldReQuoting(
            "(0135941780,958844,,,*Death certificate (See Remarks),1,2026-01-01,2025-01-01)"));

    // Properly quoted field with ')' → no re-quoting needed
    assertTrue("Should NOT detect properly quoted field",
        !com.aliyun.polardb2.jdbc.PgArray.recordNeedsFieldReQuoting(
            "(0135941780,958844,,,\"*Death certificate (See Remarks)\",1,2026-01-01,2025-01-01)"));

    // No special chars → no re-quoting needed
    assertTrue("Simple record should not need re-quoting",
        !com.aliyun.polardb2.jdbc.PgArray.recordNeedsFieldReQuoting(
            "(HHF01790,HH,RB,HOSP,5000.0,2026-01-01,2025-01-01,NOTE)"));

    // Chinese text with ASCII parens → needs re-quoting
    assertTrue("Chinese text with ASCII parens should need re-quoting",
        com.aliyun.polardb2.jdbc.PgArray.recordNeedsFieldReQuoting(
            "(3800180451,15482,,,MED020 - 會診摘要副本 (詳情請參閱以上英文版 )。,0,2027-06-18,2026-07-02)"));
  }

  /**
   * Path U2: Test reQuoteRecordFields rebuilds correctly.
   */
  @Test
  public void testReQuoteRecordFields_addsFieldQuoting() {
    String input = "(0135941780,958844,,,*Death certificate (See Remarks),1,2026-01-01,2025-01-01)";
    String result = com.aliyun.polardb2.jdbc.PgArray.reQuoteRecordFields(input);

    System.out.println("[DIAG-U2] input:  " + input);
    System.out.println("[DIAG-U2] result: " + result);

    // The field with parens should be double-quoted
    assertTrue("Result should contain quoted field",
        result.contains("\"*Death certificate (See Remarks)\""));
    // Fields without special chars should NOT be quoted
    assertTrue("Simple fields should not be quoted",
        result.startsWith("(0135941780,958844,,,"));
    // Result should start with ( and end with )
    assertTrue("Should start with (", result.charAt(0) == '(');
    assertTrue("Should end with )", result.charAt(result.length() - 1) == ')');
  }

  /**
   * Path U3: Test fixCompositeArrayElements with pre-formed record that
   * starts with '(' but has unquoted field containing ')'.
   * This simulates the customer's actual failing scenario.
   */
  @Test
  public void testFixCompositeArrayElements_preformedRecordWithParens() {
    // Simulate array string where element is already a record (starts with '(')
    // but has an unquoted field with ')' — the exact customer failure case.
    String badArrayStr =
        "{\"(0135941780,958844,,,*Death certificate (See Remarks),1,2026-01-01,2025-01-01)\"}";
    String fixed = com.aliyun.polardb2.jdbc.PgArray.fixCompositeArrayElements(badArrayStr, ',');

    System.out.println("[DIAG-U3] input:  " + badArrayStr);
    System.out.println("[DIAG-U3] fixed:  " + fixed);

    // After fix, the field should be properly quoted in the output
    // The output should contain escaped quotes around the problematic field
    assertTrue("Fixed string should have the field quoted",
        fixed.contains("*Death certificate (See Remarks)"));
    // The ')' in the field value should be inside quotes (escaped as \" at array level)
    assertTrue("Should not have raw unquoted ) in the middle of record",
        !fixed.contains(",*Death certificate (See Remarks),"));
  }

  /**
   * Path U4: Test fixCompositeArrayElements with Chinese text containing ASCII parens.
   */
  @Test
  public void testFixCompositeArrayElements_chineseParens() {
    String badArrayStr =
        "{\"(3800180451,15482,,,MED020 - 會診摘要副本 (詳情請參閱以上英文版 )。,0,2027-06-18,2026-07-02)\"}";
    String fixed = com.aliyun.polardb2.jdbc.PgArray.fixCompositeArrayElements(badArrayStr, ',');

    System.out.println("[DIAG-U4] input:  " + badArrayStr);
    System.out.println("[DIAG-U4] fixed:  " + fixed);

    // The Chinese text with parens should be properly quoted
    assertTrue("Should not have raw unquoted ) in the middle",
        !fixed.contains(",MED020 - 會診摘要副本 (詳情請參閱以上英文版 )。,"));
  }

  /**
   * Path U5: Test wrapArrayRecordLiterals with unbraced record containing parens.
   * Covers the case where PgArray.toString() returns a string like
   * {(f1,f2,*Death certificate (See Remarks),f4)} without element quotes.
   */
  @Test
  public void testWrapArrayRecordLiterals_unquotedParensInRecord() throws Exception {
    // Use reflection to test private wrapArrayRecordLiterals
    Class<?> clazz = Class.forName("com.aliyun.polardb2.jdbc.PgPreparedStatement");
    java.lang.reflect.Method method = clazz
        .getDeclaredMethod("wrapArrayRecordLiterals", String.class);
    method.setAccessible(true);

    // Case 1: {(record with unquoted parens)} — inner starts with (
    String input1 = "{(0135941780,958844,,,*Death certificate (See Remarks),1,2026-01-01,2025-01-01)}";
    String result1 = (String) method.invoke(null, input1);
    System.out.println("[DIAG-U5a] input:  " + input1);
    System.out.println("[DIAG-U5a] result: " + result1);
    assertTrue("Should fix unquoted parens in braced record",
        !result1.contains(",*Death certificate (See Remarks),"));

    // Case 2: (record with unquoted parens) — no braces, starts with (
    String input2 = "(0135941780,958844,,,*Death certificate (See Remarks),1,2026-01-01,2025-01-01)";
    String result2 = (String) method.invoke(null, input2);
    System.out.println("[DIAG-U5b] input:  " + input2);
    System.out.println("[DIAG-U5b] result: " + result2);
    assertTrue("Should fix unquoted parens in unbraced record",
        !result2.contains(",*Death certificate (See Remarks),"));
  }
}
