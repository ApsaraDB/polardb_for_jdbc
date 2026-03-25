/*
 * Portions Copyright (c) 2025, Alibaba Group Holding Limited
 *
 * Regression test for Issue:
 * getPGArrayType() returned the composite element type OID instead of the
 * collection type OID when the type's schema was NOT in the current search_path.
 *
 * Root cause:
 *   getPGType("typeName[]") uses current_schemas(true) in the WHERE clause,
 *   so it returns UNSPECIFIED when the schema is not in search_path.
 *   The fallback getPGType("typeName") uses LEFT JOIN with current_schemas
 *   and CAN find the type, but returns the composite (element) type OID.
 *   The server then rejects the procedure call because the parameter type
 *   doesn't match (composite != collection).
 *
 * Fix:
 *   findCollectionTypeByElement() queries pg_type by typelem OID and
 *   typcategory IN ('J','K','L'), bypassing the search_path restriction.
 *
 * Customer scenario (330-332.sql):
 *   {call cis.possible_match2(?, ?, ...)}
 *   Parameter LIST_OF_CUST_POL_INFO is TABLE OF cust_pol_info in schema "cis".
 *   Spring SimpleJdbcCall uses createArrayOf("CUST_POL_INFO", data) which
 *   sent cust_pol_info OID instead of list_of_cust_pol_info OID.
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.core.TypeInfo;
import com.aliyun.polardb2.jdbc.PgConnection;
import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.Properties;

/**
 * Tests that getPGArrayType() correctly resolves PolarDB collection types
 * (VARRAY / TABLE OF) even when the types reside in a schema NOT in the
 * current search_path.
 *
 * <p>This is the core regression test for Issue #300. Types are created in
 * schema {@code jdbc_test_cis} which is deliberately excluded from
 * {@code search_path}, simulating the real-world scenario where the
 * customer's {@code cis} schema is not in the default search_path.
 */
public class CollectionTypeOidResolutionTest {

  private Connection conn;

  /** Schema deliberately NOT in search_path (simulates customer's "cis" schema). */
  private static final String SCHEMA_CIS = "jdbc_test_cis";

  @Before
  public void setUp() throws Exception {
    conn = TestUtil.openDB(new Properties());

    Statement stmt = conn.createStatement();

    // Create a separate schema and types within it
    stmt.execute("DROP SCHEMA IF EXISTS " + SCHEMA_CIS + " CASCADE");
    stmt.execute("CREATE SCHEMA " + SCHEMA_CIS);

    // Temporarily switch to the schema to create types
    stmt.execute("SET search_path TO " + SCHEMA_CIS);

    // Composite types (element types)
    stmt.execute(
        "CREATE TYPE cust_pol_info AS ("
            + "client_id NUMERIC,"
            + "surname VARCHAR(100),"
            + "given_name VARCHAR(100))");

    stmt.execute(
        "CREATE TYPE writing_agt_type AS ("
            + "agt_code VARCHAR(20),"
            + "agt_name VARCHAR(100))");

    // Collection type: TABLE OF (typcategory = 'K')
    stmt.execute(
        "CREATE TYPE list_of_cust_pol_info IS TABLE OF cust_pol_info");

    // Collection type: VARRAY (typcategory = 'J')
    stmt.execute(
        "CREATE TYPE writing_agt_tab IS VARRAY(50) OF writing_agt_type");

    // Procedures that accept collection types
    stmt.execute(
        "CREATE OR REPLACE PROCEDURE possible_match_test("
            + "i_data IN list_of_cust_pol_info, "
            + "o_result OUT VARCHAR"
            + ") AS "
            + "BEGIN "
            + "  o_result := 'MATCH_OK:count=' || i_data.COUNT; "
            + "END;");

    stmt.execute(
        "CREATE OR REPLACE PROCEDURE agt_check_test("
            + "i_agts IN writing_agt_tab, "
            + "o_result OUT VARCHAR"
            + ") AS "
            + "BEGIN "
            + "  o_result := 'AGT_OK:count=' || i_agts.COUNT; "
            + "END;");

    // IMPORTANT: Reset search_path so SCHEMA_CIS is NOT included.
    // This is the key to reproducing the bug.
    stmt.execute("SET search_path TO public");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    if (conn == null || conn.isClosed()) {
      return;
    }
    Statement stmt = conn.createStatement();
    try {
      stmt.execute("DROP SCHEMA IF EXISTS " + SCHEMA_CIS + " CASCADE");
    } catch (Exception ignored) {
      // ignore
    }
    stmt.close();
    conn.close();
  }

  // =====================================================================
  // Core bug reproduction: procedure call with non-search_path schema
  // =====================================================================

  /**
   * TABLE OF type in non-search_path schema.
   *
   * <p>Before fix: fails with
   * "procedure jdbc_test_cis.possible_match_test(jdbc_test_cis.cust_pol_info, unknown)
   * does not exist" because the driver sends composite type OID.
   *
   * <p>After fix: succeeds because the driver correctly resolves to
   * list_of_cust_pol_info OID.
   */
  @Test
  public void testTableOfInNonSearchPathSchema() throws SQLException {
    Struct s1 = conn.createStruct(SCHEMA_CIS + ".cust_pol_info",
        new Object[]{new BigDecimal(1), "SMITH", "JOHN"});
    Struct s2 = conn.createStruct(SCHEMA_CIS + ".cust_pol_info",
        new Object[]{new BigDecimal(2), "DOE", "JANE"});

    // Use UNQUALIFIED element type name — this is how Spring SimpleJdbcCall works
    // and is the exact trigger for the bug.
    Array arr = conn.createArrayOf("cust_pol_info", new Struct[]{s1, s2});

    CallableStatement cs = conn.prepareCall(
        "{call " + SCHEMA_CIS + ".possible_match_test(?, ?)}");
    cs.setArray(1, arr);
    cs.registerOutParameter(2, Types.VARCHAR);
    cs.execute();

    String result = cs.getString(2);
    assertEquals("MATCH_OK:count=2", result);
    cs.close();
  }

  /**
   * VARRAY type in non-search_path schema.
   *
   * <p>Before fix: fails with
   * "procedure jdbc_test_cis.agt_check_test(jdbc_test_cis.writing_agt_type, unknown)
   * does not exist".
   */
  @Test
  public void testVarrayInNonSearchPathSchema() throws SQLException {
    Struct a1 = conn.createStruct(SCHEMA_CIS + ".writing_agt_type",
        new Object[]{"AGT001", "Agent Smith"});
    Struct a2 = conn.createStruct(SCHEMA_CIS + ".writing_agt_type",
        new Object[]{"AGT002", "Agent Jones"});

    Array arr = conn.createArrayOf("writing_agt_type", new Struct[]{a1, a2});

    CallableStatement cs = conn.prepareCall(
        "{call " + SCHEMA_CIS + ".agt_check_test(?, ?)}");
    cs.setArray(1, arr);
    cs.registerOutParameter(2, Types.VARCHAR);
    cs.execute();

    String result = cs.getString(2);
    assertEquals("AGT_OK:count=2", result);
    cs.close();
  }

  // =====================================================================
  // OID resolution verification
  // =====================================================================

  /**
   * Verifies that getPGArrayType('cust_pol_info') returns the TABLE OF
   * collection type OID, NOT the composite element type OID.
   */
  @Test
  public void testOidResolutionTableOf() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);
    TypeInfo ti = pgConn.getTypeInfo();

    int elemOid = ti.getPGType("cust_pol_info");
    assertTrue("Element type should be found", elemOid != 0);

    int arrayOid = ti.getPGArrayType("cust_pol_info");
    assertTrue("Array/collection type should be found", arrayOid != 0);

    // The bug: arrayOid == elemOid (returned composite type instead of collection)
    assertNotEquals(
        "getPGArrayType must NOT return the composite element type OID",
        elemOid, arrayOid);

    String resolvedName = ti.getPGType(arrayOid);
    assertEquals("list_of_cust_pol_info", resolvedName);
  }

  /**
   * Verifies that getPGArrayType('writing_agt_type') returns the VARRAY
   * collection type OID, NOT the composite element type OID.
   */
  @Test
  public void testOidResolutionVarray() throws SQLException {
    PgConnection pgConn = conn.unwrap(PgConnection.class);
    TypeInfo ti = pgConn.getTypeInfo();

    int elemOid = ti.getPGType("writing_agt_type");
    assertTrue("Element type should be found", elemOid != 0);

    int arrayOid = ti.getPGArrayType("writing_agt_type");
    assertTrue("Array/collection type should be found", arrayOid != 0);

    assertNotEquals(
        "getPGArrayType must NOT return the composite element type OID",
        elemOid, arrayOid);

    String resolvedName = ti.getPGType(arrayOid);
    assertEquals("writing_agt_tab", resolvedName);
  }

  // =====================================================================
  // Regression: standard PostgreSQL arrays must still work
  // =====================================================================

  /**
   * Standard PG int[] must not be affected by the collection type lookup.
   */
  @Test
  public void testStandardPgIntArray() throws SQLException {
    Array arr = conn.createArrayOf("int4", new Integer[]{1, 2, 3});
    PreparedStatement ps = conn.prepareStatement("SELECT ?::int[]");
    ps.setArray(1, arr);
    ResultSet rs = ps.executeQuery();
    assertTrue(rs.next());
    assertEquals("{1,2,3}", rs.getString(1));
    rs.close();
    ps.close();
  }

  /**
   * Standard PG text[] must not be affected by the collection type lookup.
   */
  @Test
  public void testStandardPgTextArray() throws SQLException {
    Array arr = conn.createArrayOf("text", new String[]{"hello", "world"});
    PreparedStatement ps = conn.prepareStatement("SELECT ?::text[]");
    ps.setArray(1, arr);
    ResultSet rs = ps.executeQuery();
    assertTrue(rs.next());
    assertEquals("{hello,world}", rs.getString(1));
    rs.close();
    ps.close();
  }
}
