/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.PGProperty;
import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Oracle-case coverage (input resolution + output folding) for the DatabaseMetaData methods that
 * return object identifiers, verified against the behaviour of a real Oracle instance.
 *
 * <p>Objects are created with <b>Oracle DDL</b> (NUMBER / VARCHAR2 / OBJECT type, foreign key,
 * function-based index, INVISIBLE column, quoted mixed-case identifier). Because unquoted names on
 * this instance are folded to lower case (the DTS-migration situation), an Oracle-style caller that
 * passes UPPER-case names must still find the objects and get UPPER-case identifiers back — matching
 * what the same call returns on Oracle (where names are stored upper case). Each interface is
 * checked for: UPPER input, lower input, default (oracleCase off) and, where relevant, strict mode
 * and a quoted mixed-case boundary object.
 */
public class PolarMetadataOracleCaseTest {

  private static final String SCHEMA = "metacase_repro";

  private Connection con;         // oracleMetadataCase off (plain PostgreSQL behaviour)
  private Connection ocTrue;      // oracleMetadataCase=true  (always upper)
  private Connection ocStrict;    // oracleMetadataCase=strict (only all-lower -> upper)
  /** false when the backend's PL/SQL extension cannot create function/procedure/object types. */
  private boolean plObjectsAvailable;

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();

    Properties tp = new Properties();
    PGProperty.ORACLE_METADATA_CASE.set(tp, "true");
    ocTrue = TestUtil.openDB(tp);

    Properties sp = new Properties();
    PGProperty.ORACLE_METADATA_CASE.set(sp, "strict");
    ocStrict = TestUtil.openDB(sp);

    Statement st = con.createStatement();
    try {
      st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
      st.execute("CREATE SCHEMA " + SCHEMA);
      // Oracle DDL: NUMBER / VARCHAR2, primary keys, foreign key, invisible column.
      st.execute("CREATE TABLE " + SCHEMA + ".zz_dept ("
          + "dept_id NUMBER PRIMARY KEY, dname VARCHAR2(30))");
      st.execute("CREATE TABLE " + SCHEMA + ".zz_emp ("
          + "emp_id NUMBER PRIMARY KEY, emp_name VARCHAR2(50), dept_id NUMBER, "
          + "CONSTRAINT zz_emp_fk FOREIGN KEY (dept_id) REFERENCES " + SCHEMA + ".zz_dept(dept_id))");
      st.execute("ALTER TABLE " + SCHEMA + ".zz_emp ADD (hidden_col NUMBER INVISIBLE)");
      st.execute("CREATE UNIQUE INDEX " + SCHEMA + ".zz_emp_uk ON " + SCHEMA + ".zz_emp(emp_name)");
      st.execute("CREATE INDEX " + SCHEMA + ".zz_emp_fx ON " + SCHEMA + ".zz_emp(UPPER(emp_name))");
      st.execute("CREATE VIEW " + SCHEMA + ".zz_v AS SELECT emp_id, emp_name FROM " + SCHEMA + ".zz_emp");
      st.execute("GRANT SELECT ON " + SCHEMA + ".zz_emp TO PUBLIC");
      // Oracle PL/SQL objects (single JDBC statements, so the embedded ';' are fine). Creation
      // is optional: on backends whose plsql extension is unavailable the dependent tests are
      // skipped via Assume instead of failing the whole suite.
      try {
        st.execute("CREATE FUNCTION " + SCHEMA + ".zz_fn (a IN NUMBER, b IN NUMBER) "
            + "RETURN NUMBER AS BEGIN RETURN a + b; END;");
        st.execute("CREATE PROCEDURE " + SCHEMA + ".zz_proc (p_id IN NUMBER) AS BEGIN NULL; END;");
        st.execute("CREATE TYPE " + SCHEMA + ".zz_addr AS OBJECT (street VARCHAR2(50), city VARCHAR2(30))");
        plObjectsAvailable = true;
      } catch (SQLException e) {
        plObjectsAvailable = false;
      }
      // Quoted mixed-case boundary object (stored exactly as written).
      st.execute("CREATE TABLE " + SCHEMA + ".\"Zz_Mixed\" (\"Id\" NUMBER PRIMARY KEY)");
      // Quoted UPPER-case object: an Oracle-style upper-case caller must find it directly
      // (exact match wins) even though other objects here are stored lower case.
      st.execute("CREATE TABLE " + SCHEMA + ".\"ZZ_UP_T\" (\"UP_ID\" NUMBER PRIMARY KEY, "
          + "\"UP_NAME\" VARCHAR2(20))");
    } finally {
      st.close();
    }
  }

  @After
  public void tearDown() throws Exception {
    if (con != null) {
      Statement st = con.createStatement();
      try {
        st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
      } finally {
        st.close();
      }
    }
    TestUtil.closeDB(ocStrict);
    TestUtil.closeDB(ocTrue);
    TestUtil.closeDB(con);
  }

  // ---------- helpers ----------

  private static String[] firstRow(ResultSet rs, String... cols) throws SQLException {
    try {
      assertTrue("expected at least one row", rs.next());
      String[] out = new String[cols.length];
      for (int i = 0; i < cols.length; i++) {
        out[i] = rs.getString(cols[i]);
      }
      return out;
    } finally {
      rs.close();
    }
  }

  private static Set<String> collect(ResultSet rs, String col) throws SQLException {
    Set<String> vals = new LinkedHashSet<String>();
    try {
      while (rs.next()) {
        vals.add(rs.getString(col));
      }
    } finally {
      rs.close();
    }
    return vals;
  }

  private static int rowCount(ResultSet rs) throws SQLException {
    int n = 0;
    try {
      while (rs.next()) {
        n++;
      }
    } finally {
      rs.close();
    }
    return n;
  }

  // ===================== getTables =====================

  @Test
  public void testGetTablesUpperInputFoldsUpper() throws SQLException {
    String[] r = firstRow(ocTrue.getMetaData().getTables(null, "METACASE_REPRO", "ZZ_EMP", null),
        "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE");
    assertEquals("METACASE_REPRO", r[0]);
    assertEquals("ZZ_EMP", r[1]);
    assertEquals("TABLE", r[2]);
  }

  @Test
  public void testGetTablesLowerInputStillFoldsUpper() throws SQLException {
    String[] r = firstRow(ocTrue.getMetaData().getTables(null, "metacase_repro", "zz_emp", null),
        "TABLE_SCHEM", "TABLE_NAME");
    assertEquals("METACASE_REPRO", r[0]);
    assertEquals("ZZ_EMP", r[1]);
  }

  @Test
  public void testGetTablesDefaultOffLowerCase() throws SQLException {
    String[] r = firstRow(con.getMetaData().getTables(null, SCHEMA, "zz_emp", null),
        "TABLE_SCHEM", "TABLE_NAME");
    assertEquals("metacase_repro", r[0]);
    assertEquals("zz_emp", r[1]);
  }

  @Test
  public void testGetTablesStrictFoldsAllLowerName() throws SQLException {
    String[] r = firstRow(ocStrict.getMetaData().getTables(null, "METACASE_REPRO", "ZZ_EMP", null),
        "TABLE_SCHEM", "TABLE_NAME");
    assertEquals("METACASE_REPRO", r[0]);
    assertEquals("ZZ_EMP", r[1]);
  }

  @Test
  public void testGetTablesStrictKeepsMixedCaseName() throws SQLException {
    // Quoted mixed-case "Zz_Mixed": resolved case-insensitively from UPPER input, but strict
    // output leaves a name that is not all-lower-case untouched (matching label folding).
    String[] r = firstRow(ocStrict.getMetaData().getTables(null, "METACASE_REPRO", "ZZ_MIXED", null),
        "TABLE_SCHEM", "TABLE_NAME");
    assertEquals("METACASE_REPRO", r[0]);
    assertEquals("Zz_Mixed", r[1]);
  }

  // ===================== getColumns =====================

  @Test
  public void testGetColumnsUpperFoldsAndIncludesInvisible() throws SQLException {
    Set<String> cols = collect(
        ocTrue.getMetaData().getColumns(null, "METACASE_REPRO", "ZZ_EMP", null), "COLUMN_NAME");
    assertTrue(cols.contains("EMP_ID"));
    assertTrue(cols.contains("EMP_NAME"));
    assertTrue(cols.contains("DEPT_ID"));
    // Oracle returns the INVISIBLE column from getColumns; we match that behaviour.
    assertTrue("invisible column should be returned like Oracle", cols.contains("HIDDEN_COL"));
    assertFalse(cols.contains("emp_id"));
  }

  @Test
  public void testGetColumnsSchemTableFoldUpper() throws SQLException {
    String[] r = firstRow(ocTrue.getMetaData().getColumns(null, "METACASE_REPRO", "ZZ_EMP", "EMP_ID"),
        "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME");
    assertEquals("METACASE_REPRO", r[0]);
    assertEquals("ZZ_EMP", r[1]);
    assertEquals("EMP_ID", r[2]);
  }

  @Test
  public void testGetColumnsDefaultOffLowerCase() throws SQLException {
    Set<String> cols = collect(
        con.getMetaData().getColumns(null, SCHEMA, "zz_emp", null), "COLUMN_NAME");
    assertTrue(cols.contains("emp_id"));
    assertFalse(cols.contains("EMP_ID"));
  }

  // ===================== getPrimaryKeys =====================

  @Test
  public void testGetPrimaryKeysUpperSingleColumnNoSystemCol() throws SQLException {
    ResultSet rs = ocTrue.getMetaData().getPrimaryKeys(null, "METACASE_REPRO", "ZZ_EMP");
    int n = 0;
    try {
      while (rs.next()) {
        n++;
        assertEquals("ZZ_EMP", rs.getString("TABLE_NAME"));
        assertEquals("EMP_ID", rs.getString("COLUMN_NAME"));
      }
    } finally {
      rs.close();
    }
    assertEquals("exactly one PK column, no tableoid/rowid", 1, n);
  }

  // ===================== getIndexInfo =====================

  @Test
  public void testGetIndexInfoUpperFoldsAndKeepsFunctionIndex() throws SQLException {
    ResultSet rs = ocTrue.getMetaData().getIndexInfo(null, "METACASE_REPRO", "ZZ_EMP", false, false);
    Set<String> idxNames = new LinkedHashSet<String>();
    boolean uniqueUk = false;
    boolean sawFbi = false;
    try {
      while (rs.next()) {
        String idx = rs.getString("INDEX_NAME");
        if (idx == null) {
          continue;
        }
        idxNames.add(idx);
        if ("ZZ_EMP_UK".equals(idx)) {
          uniqueUk = "f".equals(rs.getString("NON_UNIQUE")) || "false".equalsIgnoreCase(rs.getString("NON_UNIQUE"));
        }
        if ("ZZ_EMP_FX".equals(idx)) {
          sawFbi = true;
        }
      }
    } finally {
      rs.close();
    }
    assertTrue("unique index folded to upper", idxNames.contains("ZZ_EMP_UK"));
    assertTrue("unique index reported unique", uniqueUk);
    assertTrue("function-based index present", sawFbi);
    // The PolarDB internal ROWID index must never surface.
    for (String idx : idxNames) {
      assertFalse("rowid index leaked: " + idx, idx.toLowerCase(Locale.US).startsWith("polar_rowid"));
    }
  }

  // ===================== foreign keys =====================

  @Test
  public void testGetImportedKeysUpperFolds() throws SQLException {
    String[] r = firstRow(ocTrue.getMetaData().getImportedKeys(null, "METACASE_REPRO", "ZZ_EMP"),
        "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME", "FKTABLE_NAME", "FKCOLUMN_NAME", "FK_NAME");
    assertEquals("METACASE_REPRO", r[0]);
    assertEquals("ZZ_DEPT", r[1]);
    assertEquals("DEPT_ID", r[2]);
    assertEquals("ZZ_EMP", r[3]);
    assertEquals("DEPT_ID", r[4]);
    assertEquals("ZZ_EMP_FK", r[5]);
  }

  @Test
  public void testGetExportedKeysUpperFolds() throws SQLException {
    String[] r = firstRow(ocTrue.getMetaData().getExportedKeys(null, "METACASE_REPRO", "ZZ_DEPT"),
        "PKTABLE_NAME", "PKCOLUMN_NAME", "FKTABLE_NAME", "FKCOLUMN_NAME");
    assertEquals("ZZ_DEPT", r[0]);
    assertEquals("DEPT_ID", r[1]);
    assertEquals("ZZ_EMP", r[2]);
    assertEquals("DEPT_ID", r[3]);
  }

  @Test
  public void testGetCrossReferenceUpperFolds() throws SQLException {
    String[] r = firstRow(ocTrue.getMetaData().getCrossReference(
        null, "METACASE_REPRO", "ZZ_DEPT", null, "METACASE_REPRO", "ZZ_EMP"),
        "PKTABLE_NAME", "FKTABLE_NAME", "FKCOLUMN_NAME");
    assertEquals("ZZ_DEPT", r[0]);
    assertEquals("ZZ_EMP", r[1]);
    assertEquals("DEPT_ID", r[2]);
  }

  @Test
  public void testForeignKeysDefaultOffLowerCase() throws SQLException {
    String[] r = firstRow(con.getMetaData().getImportedKeys(null, SCHEMA, "zz_emp"),
        "PKTABLE_NAME", "FKCOLUMN_NAME");
    assertEquals("zz_dept", r[0]);
    assertEquals("dept_id", r[1]);
  }

  // ===================== getBestRowIdentifier =====================

  @Test
  public void testGetBestRowIdentifierUpperFolds() throws SQLException {
    String[] r = firstRow(
        ocTrue.getMetaData().getBestRowIdentifier(null, "METACASE_REPRO", "ZZ_EMP",
            DatabaseMetaData.bestRowSession, false),
        "COLUMN_NAME");
    assertEquals("EMP_ID", r[0]);
  }

  // ===================== privileges =====================

  @Test
  public void testGetColumnPrivilegesUpperFolds() throws SQLException {
    ResultSet rs = ocTrue.getMetaData().getColumnPrivileges(null, "METACASE_REPRO", "ZZ_EMP", null);
    boolean sawEmpId = false;
    try {
      while (rs.next()) {
        assertEquals("METACASE_REPRO", rs.getString("TABLE_SCHEM"));
        assertEquals("ZZ_EMP", rs.getString("TABLE_NAME"));
        String col = rs.getString("COLUMN_NAME");
        assertEquals(col, col.toUpperCase(Locale.US));
        if ("EMP_ID".equals(col)) {
          sawEmpId = true;
        }
      }
    } finally {
      rs.close();
    }
    assertTrue(sawEmpId);
  }

  @Test
  public void testGetTablePrivilegesUpperFolds() throws SQLException {
    String[] r = firstRow(ocTrue.getMetaData().getTablePrivileges(null, "METACASE_REPRO", "ZZ_EMP"),
        "TABLE_SCHEM", "TABLE_NAME");
    assertEquals("METACASE_REPRO", r[0]);
    assertEquals("ZZ_EMP", r[1]);
  }

  // ===================== procedures / functions / UDTs =====================

  @Test
  public void testGetProceduresUpperFolds() throws SQLException {
    Assume.assumeTrue("backend cannot create PL/SQL objects (plsql extension unavailable)",
        plObjectsAvailable);
    String[] r = firstRow(ocTrue.getMetaData().getProcedures(null, "METACASE_REPRO", "ZZ_PROC"),
        "PROCEDURE_SCHEM", "PROCEDURE_NAME");
    assertEquals("METACASE_REPRO", r[0]);
    assertEquals("ZZ_PROC", r[1]);
  }

  @Test
  public void testGetFunctionsUpperFolds() throws SQLException {
    Assume.assumeTrue("backend cannot create PL/SQL objects (plsql extension unavailable)",
        plObjectsAvailable);
    String[] r = firstRow(ocTrue.getMetaData().getFunctions(null, "METACASE_REPRO", "ZZ_FN"),
        "FUNCTION_SCHEM", "FUNCTION_NAME");
    assertEquals("METACASE_REPRO", r[0]);
    assertEquals("ZZ_FN", r[1]);
  }

  @Test
  public void testGetFunctionColumnsUpperFoldsArgs() throws SQLException {
    Assume.assumeTrue("backend cannot create PL/SQL objects (plsql extension unavailable)",
        plObjectsAvailable);
    Set<String> cols = collect(
        ocTrue.getMetaData().getFunctionColumns(null, "METACASE_REPRO", "ZZ_FN", null), "COLUMN_NAME");
    assertTrue("arg A folded", cols.contains("A"));
    assertTrue("arg B folded", cols.contains("B"));
  }

  @Test
  public void testGetUdtsUpperFolds() throws SQLException {
    Assume.assumeTrue("backend cannot create PL/SQL objects (plsql extension unavailable)",
        plObjectsAvailable);
    String[] r = firstRow(ocTrue.getMetaData().getUDTs(null, "METACASE_REPRO", "ZZ_ADDR", null),
        "TYPE_SCHEM", "TYPE_NAME");
    assertEquals("METACASE_REPRO", r[0]);
    assertEquals("ZZ_ADDR", r[1]);
  }

  // ===================== getSchemas =====================

  @Test
  public void testGetSchemasUpperFolds() throws SQLException {
    String[] r = firstRow(ocTrue.getMetaData().getSchemas(null, "METACASE_REPRO"), "TABLE_SCHEM");
    assertEquals("METACASE_REPRO", r[0]);
  }

  @Test
  public void testGetSchemasStrictFolds() throws SQLException {
    String[] r = firstRow(ocStrict.getMetaData().getSchemas(null, "METACASE_REPRO"), "TABLE_SCHEM");
    assertEquals("METACASE_REPRO", r[0]);
  }

  @Test
  public void testGetSchemasDefaultOffLowerCase() throws SQLException {
    String[] r = firstRow(con.getMetaData().getSchemas(null, SCHEMA), "TABLE_SCHEM");
    assertEquals("metacase_repro", r[0]);
  }

  // ===================== sanity: object actually present =====================

  @Test
  public void testViewResolvedByGetTables() throws SQLException {
    ResultSet rs = ocTrue.getMetaData().getTables(null, "METACASE_REPRO", "ZZ_V",
        new String[] {"VIEW"});
    assertNotNull(rs);
    assertTrue("view should be found and folded", rowCount(rs) >= 1);
  }

  // ===================== objects STORED upper-case (exact match wins) =====================

  @Test
  public void testUpperStoredTableFoundByUpperInput() throws SQLException {
    // "ZZ_UP_T" is stored upper-case; upper-case input must locate it and return it upper-case.
    String[] r = firstRow(ocTrue.getMetaData().getTables(null, "METACASE_REPRO", "ZZ_UP_T", null),
        "TABLE_SCHEM", "TABLE_NAME");
    assertEquals("METACASE_REPRO", r[0]);
    assertEquals("ZZ_UP_T", r[1]);
  }

  @Test
  public void testUpperStoredColumnsFoundByUpperInput() throws SQLException {
    Set<String> cols = collect(
        ocTrue.getMetaData().getColumns(null, "METACASE_REPRO", "ZZ_UP_T", null), "COLUMN_NAME");
    assertTrue(cols.contains("UP_ID"));
    assertTrue(cols.contains("UP_NAME"));
  }

  @Test
  public void testUpperStoredPrimaryKeyFoundByUpperInput() throws SQLException {
    String[] r = firstRow(ocTrue.getMetaData().getPrimaryKeys(null, "METACASE_REPRO", "ZZ_UP_T"),
        "TABLE_NAME", "COLUMN_NAME");
    assertEquals("ZZ_UP_T", r[0]);
    assertEquals("UP_ID", r[1]);
  }

  @Test
  public void testUpperStoredStrictKeepsUpperCase() throws SQLException {
    // strict must also find the upper-case object and keep the already-upper-case names.
    String[] r = firstRow(ocStrict.getMetaData().getTables(null, "METACASE_REPRO", "ZZ_UP_T", null),
        "TABLE_SCHEM", "TABLE_NAME");
    assertEquals("METACASE_REPRO", r[0]);
    assertEquals("ZZ_UP_T", r[1]);
  }
}
