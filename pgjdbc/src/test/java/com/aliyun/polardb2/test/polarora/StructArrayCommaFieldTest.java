/*
 * Portions Copyright (c) 2025, Alibaba Group Holding Limited
 *
 * Reproduce bug: Struct array element with commas inside a string field
 * causes field shifting in the composite record literal.
 *
 * Root cause: ArrayEncoding.OBJECT_ARRAY.appendArray() processes Struct
 * elements by naively concatenating attributes[j].toString() without
 * double-quoting strings that contain commas. When an address field like
 * "ADDRESS 3, ADDRESS 2, ADDRESS1" is embedded raw, its commas are parsed
 * as composite-type field separators, shifting subsequent fields into
 * wrong columns.
 *
 * Observed production errors (Manulife HKPED):
 *   ERROR: value too long for type character varying(1 byte)
 *     while processing column "invalid_address_signal"
 *     (2 commas in address  ->  "CHINA" lands in 1-byte column)
 *
 *   ERROR: invalid input syntax for type date: "CHINA"
 *     while processing column "address_date"
 *     (1 comma in address  ->  "CHINA" lands in DATE column)
 *
 *   (no comma in address  ->  works correctly)
 */

package com.aliyun.polardb2.test.polarora;

import com.aliyun.polardb2.test.TestUtil;

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
 * Reproduces the Manulife HKPED production error where
 * {@code conn.createStruct("REC_POLICY_ADDR", attrs)} combined with
 * {@code conn.createArrayOf("TAB_POLICY_ADDR", new Struct[]{...})}
 * fails when an address field contains commas.
 */
public class StructArrayCommaFieldTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();

    // Production composite type (simplified — keeping the crucial field order
    // so that field shifting lands on invalid_address_signal / address_date)
    stmt.execute(
        "CREATE OR REPLACE TYPE rec_policy_addr AS (\n"
            + "  policy_key                   character varying(45),\n"  // 1
            + "  contract_no                  character varying(20),\n"  // 2
            + "  sub_group_no                 character varying(5),\n"   // 3
            + "  certificate_no               character varying(10),\n"  // 4
            + "  sys_code                     character varying(3),\n"   // 5
            + "  address_language             character varying(1),\n"   // 6
            + "  address_number               numeric(2),\n"             // 7
            + "  address1                     character varying(40),\n"  // 8  ← has commas
            + "  address2                     character varying(40),\n"  // 9
            + "  address3                     character varying(40),\n"  // 10
            + "  address4                     character varying(40),\n"  // 11 ← "CHINA"
            + "  address_date                 timestamp,\n"              // 12
            + "  invalid_address_signal       character varying(1),\n"   // 13
            + "  invalid_address_signal_date  timestamp,\n"              // 14
            + "  residential_code             character varying(2),\n"   // 15
            + "  residential_code_date        timestamp,\n"              // 16
            + "  building_code                character varying(11),\n"  // 17
            + "  unique_address_code          character varying(30),\n"  // 18
            + "  lob                          character varying(3),\n"   // 19
            + "  last_modified_date           timestamp,\n"              // 20
            + "  last_modified_channel        character varying(100)\n"  // 21
            + ")");

    stmt.execute(
        "CREATE OR REPLACE TYPE tab_policy_addr AS TABLE OF rec_policy_addr");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE addr_test_pkg AS\n"
            + "  PROCEDURE accept_addr(\n"
            + "    p_items  IN  tab_policy_addr,\n"
            + "    p_count  OUT INTEGER,\n"
            + "    p_addr4  OUT character varying,\n"
            + "    p_signal OUT character varying\n"
            + "  );\n"
            + "END addr_test_pkg;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY addr_test_pkg AS\n"
            + "  PROCEDURE accept_addr(\n"
            + "    p_items  IN  tab_policy_addr,\n"
            + "    p_count  OUT INTEGER,\n"
            + "    p_addr4  OUT character varying,\n"
            + "    p_signal OUT character varying\n"
            + "  ) IS\n"
            + "  BEGIN\n"
            + "    p_count  := p_items.COUNT;\n"
            + "    IF p_items.COUNT > 0 THEN\n"
            + "      p_addr4  := p_items(1).address4;\n"
            + "      p_signal := p_items(1).invalid_address_signal;\n"
            + "    END IF;\n"
            + "  END;\n"
            + "END addr_test_pkg;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("DROP PACKAGE IF EXISTS addr_test_pkg");
    stmt.execute("DROP TYPE IF EXISTS tab_policy_addr");
    stmt.execute("DROP TYPE IF EXISTS rec_policy_addr");
    stmt.close();
    conn.close();
  }

  /** Build the 21 attributes matching the production payload. */
  private Object[] buildAddressRow(String address1) {
    return new Object[] {
        "MPF-CER-99567906-39198017",       // 1 policy_key
        null,                               // 2
        null,                               // 3
        null,                               // 4
        null,                               // 5
        "E",                                // 6 address_language
        Integer.valueOf(1),                 // 7 address_number
        address1,                           // 8 address1   ← the varying comma count
        null,                               // 9
        null,                               // 10
        "CHINA",                            // 11 address4
        "2026-03-04 00:00:00",              // 12 address_date
        "N",                                // 13 invalid_address_signal
        "2026-03-04 00:00:00",              // 14
        "XX",                               // 15
        "2026-03-04 00:00:00",              // 16
        null,                               // 17
        null,                               // 18
        "GP",                               // 19
        "2026-04-27 17:17:16",              // 20
        "GP-ASB"                            // 21
    };
  }

  /**
   * Case A: address1 has 2 commas — should fail with
   * "value too long for type character varying(1 byte)" on column
   * invalid_address_signal (CHINA shifted 2 positions into a VARCHAR(1)).
   */
  @Test
  public void testTwoCommasShiftsIntoVarchar1() throws SQLException {
    Object[] attrs = buildAddressRow("ADDRESS 3, ADDRESS 2, ADDRESS1");
    Struct s = conn.createStruct("rec_policy_addr", attrs);
    Array arr = conn.createArrayOf("tab_policy_addr", new Struct[]{s});

    try (CallableStatement cs = conn.prepareCall(
        "begin addr_test_pkg.accept_addr(?, ?, ?, ?); end;")) {
      cs.setArray(1, arr);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.registerOutParameter(4, Types.VARCHAR);
      cs.execute();

      // After the fix: p_addr4 should be "CHINA" and p_signal should be "N"
      org.junit.Assert.assertEquals("address4 should be CHINA",
          "CHINA", cs.getString(3));
      org.junit.Assert.assertEquals("invalid_address_signal should be N",
          "N", cs.getString(4));
    }
  }

  /**
   * Case B: address1 has 1 comma — should fail with
   * "invalid input syntax for type date: CHINA" on column address_date
   * (CHINA shifted 1 position into a DATE column).
   */
  @Test
  public void testOneCommaShiftsIntoDate() throws SQLException {
    Object[] attrs = buildAddressRow("ADDRESS 3, ADDRESS 2");
    Struct s = conn.createStruct("rec_policy_addr", attrs);
    Array arr = conn.createArrayOf("tab_policy_addr", new Struct[]{s});

    try (CallableStatement cs = conn.prepareCall(
        "begin addr_test_pkg.accept_addr(?, ?, ?, ?); end;")) {
      cs.setArray(1, arr);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.registerOutParameter(4, Types.VARCHAR);
      cs.execute();

      org.junit.Assert.assertEquals("address4 should be CHINA",
          "CHINA", cs.getString(3));
      org.junit.Assert.assertEquals("invalid_address_signal should be N",
          "N", cs.getString(4));
    }
  }

  /**
   * Case C: address1 has no comma — should work correctly (baseline).
   */
  @Test
  public void testNoCommaWorks() throws SQLException {
    Object[] attrs = buildAddressRow("ADDRESS 3");
    Struct s = conn.createStruct("rec_policy_addr", attrs);
    Array arr = conn.createArrayOf("tab_policy_addr", new Struct[]{s});

    try (CallableStatement cs = conn.prepareCall(
        "begin addr_test_pkg.accept_addr(?, ?, ?, ?); end;")) {
      cs.setArray(1, arr);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.registerOutParameter(4, Types.VARCHAR);
      cs.execute();

      org.junit.Assert.assertEquals("address4 should be CHINA",
          "CHINA", cs.getString(3));
      org.junit.Assert.assertEquals("invalid_address_signal should be N",
          "N", cs.getString(4));
    }
  }

  /**
   * Case D: Production payload from the HKPED policy address master.
   *
   * <p>The {@code last_modified_channel} value {@code "HKCUSTSWS(NB)-RAY LIU"}
   * contains parentheses, which are composite-literal control characters in
   * PostgreSQL.  Without the {@code needsRecordQuoting} protection, a closing
   * {@code ')'} inside the last field would prematurely terminate the record
   * literal and cause a parse error.  This case verifies that both commas and
   * parentheses are handled correctly together.
   */
  @Test
  public void testProductionPayloadWithParens() throws SQLException {
    Object[] attrs = new Object[] {
        "IFP-POL-3800376398",                   //  1 policy_key
        null,                                    //  2 contract_no
        null,                                    //  3 sub_group_no
        null,                                    //  4 certificate_no
        null,                                    //  5 sys_code
        "E",                                     //  6 address_language (varchar(1))
        Integer.valueOf(1),                      //  7 address_number
        "KKK GGG",                               //  8 address1
        "MMM NN",                                //  9 address2
        "AAABBBCCC",                             // 10 address3
        "HONG KONG",                             // 11 address4 (inner space)
        "2026-03-09 20:11:03",                   // 12 address_date
        "N",                                     // 13 invalid_address_signal
        "2025-04-01 17:48:17",                   // 14 invalid_address_signal_date
        "HK",                                    // 15 residential_code
        "2026-03-09 20:06:00",                   // 16 residential_code_date
        null,                                    // 17 building_code
        null,                                    // 18 unique_address_code
        "GLH",                                   // 19 lob
        "2026-03-09 20:11:03",                   // 20 last_modified_date
        "HKCUSTSWS(NB)-RAY LIU"                  // 21 last_modified_channel ← parentheses
    };
    Struct s = conn.createStruct("rec_policy_addr", attrs);
    Array arr = conn.createArrayOf("tab_policy_addr", new Struct[]{s});

    try (CallableStatement cs = conn.prepareCall(
        "begin addr_test_pkg.accept_addr(?, ?, ?, ?); end;")) {
      cs.setArray(1, arr);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.registerOutParameter(4, Types.VARCHAR);
      cs.execute();

      org.junit.Assert.assertEquals("row count", 1, cs.getInt(2));
      org.junit.Assert.assertEquals("address4 should be HONG KONG",
          "HONG KONG", cs.getString(3));
      org.junit.Assert.assertEquals("invalid_address_signal should be N",
          "N", cs.getString(4));
    }
  }
}
