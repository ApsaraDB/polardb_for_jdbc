/*
 * Portions Copyright (c) 2025, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aliyun.polardb2.jdbc.PgArray;
import com.aliyun.polardb2.jdbc.PgConnection;
import com.aliyun.polardb2.test.TestUtil;
import com.aliyun.polardb2.util.PGobject;
import com.aliyun.polardb2.util.PSQLState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.Properties;

/**
 * Comprehensive tests for three PolarDB cross-package TABLE OF RECORD fixes:
 * <ol>
 *   <li>{@code TypeInfoCache.getPGArrayElement}: typarray reverse-lookup fallback
 *       when typelem=0</li>
 *   <li>{@code TypeInfoCache.getSQLTypeFromQueryResult}: recognise typtype='a' as
 *       Types.ARRAY so PgResultSet creates PgArray instead of PGobject</li>
 *   <li>{@code ArrayDecoding.buildTableOfArrayList}: parse the PolarDB-specific
 *       {@code index => "value"} literal format</li>
 * </ol>
 *
 * <p>Customer scenario:
 * <pre>
 * DB: hkped, Account: epos_con_pool
 * Procedure: soa_cim_pkg.create_customer_policy_linkage
 * Error: PSQLException: No results were returned by the query.
 *   at TypeInfoCache.getPGArrayElement
 *   at PgArray.getBaseTypeName
 *   at PgPreparedStatement.setArray
 * </pre>
 */
public class GetPGArrayElementNoResultsTest {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    Statement stmt = conn.createStatement();

    // ---- standalone composite type (for simulating typelem=0 scenarios) ----
    stmt.execute(
        "CREATE OR REPLACE TYPE test_composite_no_array AS (\n"
            + "  id NUMBER, name VARCHAR2(50)\n"
            + ")");

    // ---- Package A: defines RECORD + TABLE OF RECORD (cross-package style) ----
    stmt.execute(
        "CREATE OR REPLACE PACKAGE cim_types_pkg AS\n"
            + "  TYPE cust_linkage_rec IS RECORD (\n"
            + "    cust_id     NUMBER,\n"
            + "    pol_num     VARCHAR2(50),\n"
            + "    link_type   VARCHAR2(10),\n"
            + "    eff_dt      DATE\n"
            + "  );\n"
            + "  TYPE cust_linkage_tab IS TABLE OF cust_linkage_rec INDEX BY BINARY_INTEGER;\n"
            + "END cim_types_pkg;");

    // ---- Package B: functions/procedures for OUT param testing ----
    stmt.execute(
        "CREATE OR REPLACE PACKAGE cim_query_pkg AS\n"
            // function: returns multi-element TABLE OF
            + "  FUNCTION get_linkages(p_cust_id NUMBER)\n"
            + "    RETURN cim_types_pkg.cust_linkage_tab;\n"
            // function: returns single-element TABLE OF
            + "  FUNCTION get_single_linkage(p_cust_id NUMBER)\n"
            + "    RETURN cim_types_pkg.cust_linkage_tab;\n"
            // function: returns TABLE OF with NULL fields
            + "  FUNCTION get_linkages_with_nulls\n"
            + "    RETURN cim_types_pkg.cust_linkage_tab;\n"
            // procedure: IN scalar + OUT TABLE OF
            + "  PROCEDURE fetch_linkages(\n"
            + "    p_cust_id IN NUMBER,\n"
            + "    p_result  OUT cim_types_pkg.cust_linkage_tab\n"
            + "  );\n"
            // function: returns single RECORD (not TABLE OF)
            + "  FUNCTION get_single_rec(p_cust_id NUMBER)\n"
            + "    RETURN cim_types_pkg.cust_linkage_rec;\n"
            + "END cim_query_pkg;");

    stmt.execute(
        "CREATE OR REPLACE PACKAGE BODY cim_query_pkg AS\n"
            // get_linkages: 2 elements
            + "  FUNCTION get_linkages(p_cust_id NUMBER)\n"
            + "    RETURN cim_types_pkg.cust_linkage_tab IS\n"
            + "    v_result cim_types_pkg.cust_linkage_tab;\n"
            + "  BEGIN\n"
            + "    v_result(1).cust_id   := p_cust_id;\n"
            + "    v_result(1).pol_num   := 'POL001';\n"
            + "    v_result(1).link_type := 'PRIMARY';\n"
            + "    v_result(1).eff_dt    := SYSDATE;\n"
            + "    v_result(2).cust_id   := p_cust_id + 1;\n"
            + "    v_result(2).pol_num   := 'POL002';\n"
            + "    v_result(2).link_type := 'SECONDARY';\n"
            + "    v_result(2).eff_dt    := NULL;\n"
            + "    RETURN v_result;\n"
            + "  END;\n"
            // get_single_linkage: 1 element
            + "  FUNCTION get_single_linkage(p_cust_id NUMBER)\n"
            + "    RETURN cim_types_pkg.cust_linkage_tab IS\n"
            + "    v_result cim_types_pkg.cust_linkage_tab;\n"
            + "  BEGIN\n"
            + "    v_result(1).cust_id   := p_cust_id;\n"
            + "    v_result(1).pol_num   := 'SINGLE';\n"
            + "    v_result(1).link_type := 'ONLY';\n"
            + "    v_result(1).eff_dt    := DATE '2025-01-01';\n"
            + "    RETURN v_result;\n"
            + "  END;\n"
            // get_linkages_with_nulls: various NULL combinations
            + "  FUNCTION get_linkages_with_nulls\n"
            + "    RETURN cim_types_pkg.cust_linkage_tab IS\n"
            + "    v_result cim_types_pkg.cust_linkage_tab;\n"
            + "  BEGIN\n"
            + "    v_result(1).cust_id   := 100;\n"
            + "    v_result(1).pol_num   := NULL;\n"
            + "    v_result(1).link_type := NULL;\n"
            + "    v_result(1).eff_dt    := NULL;\n"
            + "    v_result(2).cust_id   := NULL;\n"
            + "    v_result(2).pol_num   := NULL;\n"
            + "    v_result(2).link_type := NULL;\n"
            + "    v_result(2).eff_dt    := NULL;\n"
            + "    v_result(3).cust_id   := 300;\n"
            + "    v_result(3).pol_num   := 'P003';\n"
            + "    v_result(3).link_type := 'X';\n"
            + "    v_result(3).eff_dt    := DATE '2025-12-31';\n"
            + "    RETURN v_result;\n"
            + "  END;\n"
            // fetch_linkages: scalar IN, TABLE OF OUT
            + "  PROCEDURE fetch_linkages(\n"
            + "    p_cust_id IN NUMBER,\n"
            + "    p_result  OUT cim_types_pkg.cust_linkage_tab\n"
            + "  ) IS\n"
            + "  BEGIN\n"
            + "    p_result(1).cust_id   := p_cust_id;\n"
            + "    p_result(1).pol_num   := 'FETCHED';\n"
            + "    p_result(1).link_type := 'OUT';\n"
            + "    p_result(1).eff_dt    := SYSDATE;\n"
            + "  END;\n"
            // get_single_rec: returns a single RECORD
            + "  FUNCTION get_single_rec(p_cust_id NUMBER)\n"
            + "    RETURN cim_types_pkg.cust_linkage_rec IS\n"
            + "    v_rec cim_types_pkg.cust_linkage_rec;\n"
            + "  BEGIN\n"
            + "    v_rec.cust_id   := p_cust_id;\n"
            + "    v_rec.pol_num   := 'REC_POL';\n"
            + "    v_rec.link_type := 'REC';\n"
            + "    v_rec.eff_dt    := DATE '2025-06-15';\n"
            + "    RETURN v_rec;\n"
            + "  END;\n"
            + "END cim_query_pkg;");

    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("DROP TYPE IF EXISTS test_composite_no_array");
    stmt.execute("DROP PACKAGE IF EXISTS cim_query_pkg");
    stmt.execute("DROP PACKAGE IF EXISTS cim_types_pkg");
    stmt.close();
    conn.close();
  }

  // ===================================================================
  // Part 1: getPGArrayElement fallback for typelem=0
  // ===================================================================

  /** getBaseTypeName() path: typelem=0 → NO_DATA */
  @Test
  public void testGetBaseTypeNameThrowsForTypElemZero() throws SQLException {
    int typeOid = lookupCompositeOidWithTypElemZero();
    PgConnection pgConn = conn.unwrap(PgConnection.class);
    PgArray pgArray = new PgArray(pgConn, typeOid, "{}");
    try {
      String baseTypeName = pgArray.getBaseTypeName();
      fail("Expected PSQLException but got baseTypeName=" + baseTypeName);
    } catch (SQLException e) {
      assertTrue("SQL state should be NO_DATA (02000)",
          PSQLState.NO_DATA.getState().equals(e.getSQLState()));
    }
  }

  /** getArray() path: typelem=0 → NO_DATA */
  @Test
  public void testGetArrayThrowsForTypElemZero() throws SQLException {
    int typeOid = lookupCompositeOidWithTypElemZero();
    PgConnection pgConn = conn.unwrap(PgConnection.class);
    PgArray pgArray = new PgArray(pgConn, typeOid, "{\"(1,hello)\"}");
    try {
      pgArray.getArray();
      fail("Expected PSQLException with NO_DATA state");
    } catch (SQLException e) {
      assertTrue(PSQLState.NO_DATA.getState().equals(e.getSQLState()));
    }
  }

  /** getResultSet() path: typelem=0 → NO_DATA */
  @Test
  public void testGetResultSetThrowsForTypElemZero() throws SQLException {
    int typeOid = lookupCompositeOidWithTypElemZero();
    PgConnection pgConn = conn.unwrap(PgConnection.class);
    PgArray pgArray = new PgArray(pgConn, typeOid, "{\"(1,hello)\"}");
    try {
      pgArray.getResultSet();
      fail("Expected PSQLException with NO_DATA state");
    } catch (SQLException e) {
      assertTrue(PSQLState.NO_DATA.getState().equals(e.getSQLState()));
    }
  }

  // ===================================================================
  // Part 2: Function returning cross-package TABLE OF RECORD (OUT)
  // ===================================================================

  /** Multi-element TABLE OF RECORD via function OUT: type recognition + parse */
  @Test
  public void testFuncOutMultiElement() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ ? = call cim_query_pkg.get_linkages(?) }")) {
      cs.registerOutParameter(1, Types.ARRAY, "cim_types_pkg.cust_linkage_tab");
      cs.setInt(2, 12345);
      cs.execute();

      Object result = cs.getObject(1);
      assertTrue("Must be Array", result instanceof Array);

      Array array = cs.getArray(1);
      Object[] elements = (Object[]) array.getArray();
      assertEquals(2, elements.length);

      assertTrue(elements[0] instanceof Struct);
      Object[] a0 = ((Struct) elements[0]).getAttributes();
      // cust_id is NUMBER -> restored to BigDecimal (Oracle-compatible)
      assertEquals(0, new BigDecimal("12345").compareTo((BigDecimal) a0[0]));
      assertEquals("POL001", a0[1]);
      assertEquals("PRIMARY", a0[2]);
      assertNotNull(a0[3]);

      assertTrue(elements[1] instanceof Struct);
      Object[] a1 = ((Struct) elements[1]).getAttributes();
      assertEquals(0, new BigDecimal("12346").compareTo((BigDecimal) a1[0]));
      assertEquals("SECONDARY", a1[2]);
    }
  }

  /** Single-element TABLE OF RECORD via function OUT */
  @Test
  public void testFuncOutSingleElement() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ ? = call cim_query_pkg.get_single_linkage(?) }")) {
      cs.registerOutParameter(1, Types.ARRAY, "cim_types_pkg.cust_linkage_tab");
      cs.setInt(2, 99);
      cs.execute();

      Array array = cs.getArray(1);
      Object[] elements = (Object[]) array.getArray();
      assertEquals(1, elements.length);
      assertTrue(elements[0] instanceof Struct);

      Object[] attrs = ((Struct) elements[0]).getAttributes();
      assertEquals(0, new BigDecimal("99").compareTo((BigDecimal) attrs[0]));
      assertEquals("SINGLE", attrs[1]);
      assertEquals("ONLY", attrs[2]);
    }
  }

  /** TABLE OF with mixed NULL fields inside composite elements */
  @Test
  public void testFuncOutWithNullFields() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ ? = call cim_query_pkg.get_linkages_with_nulls() }")) {
      cs.registerOutParameter(1, Types.ARRAY, "cim_types_pkg.cust_linkage_tab");
      cs.execute();

      Array array = cs.getArray(1);
      Object[] elements = (Object[]) array.getArray();
      assertEquals(3, elements.length);

      // Element 1: only cust_id set, rest NULL
      Object[] a0 = ((Struct) elements[0]).getAttributes();
      assertEquals(4, a0.length);
      assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) a0[0]));
      assertNull(a0[1]);
      assertNull(a0[2]);
      assertNull(a0[3]);

      // Element 2: all NULL
      Object[] a1 = ((Struct) elements[1]).getAttributes();
      assertEquals(4, a1.length);
      for (Object attr : a1) {
        assertNull(attr);
      }

      // Element 3: all filled
      Object[] a2 = ((Struct) elements[2]).getAttributes();
      assertEquals(0, new BigDecimal("300").compareTo((BigDecimal) a2[0]));
      assertEquals("P003", a2[1]);
      assertNotNull(a2[3]);
    }
  }

  /** Function returning single RECORD (not TABLE OF) as OUT param */
  @Test
  public void testFuncOutSingleRecord() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ ? = call cim_query_pkg.get_single_rec(?) }")) {
      cs.registerOutParameter(1, Types.STRUCT);
      cs.setInt(2, 42);
      cs.execute();

      Object result = cs.getObject(1);
      assertNotNull(result);
      // Single RECORD returned as PGobject/Struct
      assertTrue(result instanceof PGobject);
    }
  }

  /** getArray(1) on function OUT returns elements that are both Struct and PGobject */
  @Test
  public void testFuncOutElementsDualInterface() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ ? = call cim_query_pkg.get_linkages(?) }")) {
      cs.registerOutParameter(1, Types.ARRAY, "cim_types_pkg.cust_linkage_tab");
      cs.setInt(2, 1);
      cs.execute();

      Array array = cs.getArray(1);
      Object[] elements = (Object[]) array.getArray();
      for (Object elem : elements) {
        assertTrue("Must be Struct", elem instanceof Struct);
        assertTrue("Must be PGobject", elem instanceof PGobject);
        PGobject pgObj = (PGobject) elem;
        assertNotNull(pgObj.getValue());
        assertTrue(pgObj.getValue().startsWith("("));
      }
    }
  }

  /** Access elements via ResultSet on the Array */
  @Test
  public void testFuncOutViaResultSet() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ ? = call cim_query_pkg.get_linkages(?) }")) {
      cs.registerOutParameter(1, Types.ARRAY, "cim_types_pkg.cust_linkage_tab");
      cs.setInt(2, 7);
      cs.execute();

      Array array = cs.getArray(1);
      ResultSet rs = array.getResultSet();
      int count = 0;
      while (rs.next()) {
        count++;
        Object val = rs.getObject(2);
        assertNotNull(val);
        assertTrue(val instanceof Struct);
      }
      assertEquals(2, count);
      rs.close();
    }
  }

  // ===================================================================
  // Part 3: Procedure with TABLE OF RECORD OUT param
  // ===================================================================

  /** Procedure: scalar IN + TABLE OF OUT */
  @Test
  public void testProcOutTableOf() throws SQLException {
    try (CallableStatement cs = conn.prepareCall(
        "{ call cim_query_pkg.fetch_linkages(?, ?) }")) {
      cs.setInt(1, 555);
      cs.registerOutParameter(2, Types.ARRAY, "cim_types_pkg.cust_linkage_tab");
      cs.execute();

      Array array = cs.getArray(2);
      assertNotNull(array);
      Object[] elements = (Object[]) array.getArray();
      assertEquals(1, elements.length);

      Object[] attrs = ((Struct) elements[0]).getAttributes();
      assertEquals(0, new BigDecimal("555").compareTo((BigDecimal) attrs[0]));
      assertEquals("FETCHED", attrs[1]);
      assertEquals("OUT", attrs[2]);
    }
  }

  // ===================================================================
  // Part 6: Diagnostic
  // ===================================================================

  /** Diagnostic: pg_type metadata for the cross-package TABLE OF type */
  @Test
  public void testDiagnosticCrossPkgTableOfRecordTypElem() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT t.oid, t.typname, t.typelem, t.typtype, t.typarray "
            + "FROM pg_type t WHERE t.typname LIKE '%cust_linkage_tab%'");
    boolean found = false;
    while (rs.next()) {
      found = true;
      System.out.println("[DIAGNOSTIC] oid=" + rs.getInt("oid")
          + " typname=" + rs.getString("typname")
          + " typelem=" + rs.getInt("typelem")
          + " typtype=" + rs.getString("typtype")
          + " typarray=" + rs.getInt("typarray"));
    }
    rs.close();
    stmt.close();
    assertTrue("Should find cust_linkage_tab in pg_type", found);
  }

  // ===================================================================
  // Helper
  // ===================================================================

  private int lookupCompositeOidWithTypElemZero() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(
        "SELECT t.oid FROM pg_type t "
            + "WHERE t.typname = 'test_composite_no_array' AND t.typelem = 0");
    assertTrue("Should find composite type with typelem=0", rs.next());
    int oid = rs.getInt(1);
    rs.close();
    stmt.close();
    return oid;
  }
}
