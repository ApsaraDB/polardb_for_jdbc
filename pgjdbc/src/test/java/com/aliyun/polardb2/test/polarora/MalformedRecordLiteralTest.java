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

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    Statement stmt = conn.createStatement();
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

  /**
   * Customer's exact pattern: begin...end block with PGobject array.
   * After fix, should succeed without malformed record literal error.
   */
  @Test
  public void testBeginEndBlockWithPGobjectArrayNowSucceeds() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);

    PGobject obj = new PGobject();
    obj.setType("rec_claim_item");
    obj.setValue("HHF01790,HH,RB,HOSP,15,900.0");

    Array array = pgConn.createArrayOf("tab_claim_items", new PGobject[]{obj});

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

      assertEquals("Should count 1 item", 1, cs.getInt(4));
      assertEquals("Status should be 0", 0, cs.getInt(5));
    }
  }
}
