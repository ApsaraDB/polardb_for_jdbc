/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Regression tests for {@code getPrimaryKeys} covering two PolarDB-O issues reported when a
 * customer migrated an Oracle schema through DTS:
 *
 * <ol>
 *   <li>Oracle upper-case schema/table names (the customer's habit) no longer matched the
 *       lower-case names DTS produced, so {@code getPrimaryKeys} returned nothing. With
 *       {@code oracleCase} enabled the driver now resolves the case.</li>
 *   <li>PolarDB-O partitioned tables whose primary key does not include the partition key are
 *       backed by a global index that carries the hidden {@code tableoid} system column. That
 *       system column used to be reported as a bogus extra primary key column.</li>
 * </ol>
 *
 * <p>These tests need a PolarDB-O backend (Oracle-style partitioning and case handling).
 */
public class PolarPrimaryKeysOracleCaseTest {

  // schema whose objects are stored lower-case (as DTS would produce)
  private static final String SCHEMA = "pkcase_repro";
  // two extra schemas used to exercise cross-schema case ambiguity
  private static final String SCHEMA_A = "pkcase_repro_a";
  private static final String SCHEMA_B = "pkcase_repro_b";

  private Connection con;
  private Connection oracleCon;

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();

    Properties props = new Properties();
    PGProperty.ORACLE_METADATA_CASE.set(props, "true");
    oracleCon = TestUtil.openDB(props);

    Statement stmt = con.createStatement();
    try {
      dropSchemas(stmt);

      stmt.execute("CREATE SCHEMA " + SCHEMA);
      // Partitioned table whose PK (id) does NOT include the partition key (d):
      // PolarDB-O backs this with a global index that embeds the tableoid system column.
      stmt.execute("CREATE TABLE " + SCHEMA + ".pk_part("
          + "id NUMBER NOT NULL, d DATE, name VARCHAR2(20), CONSTRAINT pk_part_pk PRIMARY KEY(id)) "
          + "PARTITION BY RANGE(d)("
          + "PARTITION p1 VALUES LESS THAN(DATE '2020-01-01'), "
          + "PARTITION pm VALUES LESS THAN(MAXVALUE))");
      // lower-case only table
      stmt.execute("CREATE TABLE " + SCHEMA + ".only_lower(id NUMBER PRIMARY KEY)");
      // upper-case only table (quoted keeps the case)
      stmt.execute("CREATE TABLE " + SCHEMA + ".\"ONLY_UPPER\"(id NUMBER PRIMARY KEY)");
      // mixed-case table
      stmt.execute("CREATE TABLE " + SCHEMA + ".\"MixCaseTbl\"(id NUMBER PRIMARY KEY)");
      // table with a mixed-case PK column (for oracleCase=strict output-folding checks)
      stmt.execute("CREATE TABLE " + SCHEMA + ".mixcol_tbl(\"MixCol\" NUMBER, "
          + "CONSTRAINT mixcol_pk PRIMARY KEY(\"MixCol\"))");

      // Two schemas hosting the same name in different cases so both variants can co-exist
      // (a single schema on this backend enforces case-insensitive uniqueness of relnames).
      stmt.execute("CREATE SCHEMA " + SCHEMA_A);
      stmt.execute("CREATE SCHEMA " + SCHEMA_B);
      stmt.execute("CREATE TABLE " + SCHEMA_A + ".pkcasex_dup(id NUMBER PRIMARY KEY)");
      stmt.execute("CREATE TABLE " + SCHEMA_B + ".\"PKCASEX_DUP\"(id NUMBER PRIMARY KEY)");
      stmt.execute("CREATE TABLE " + SCHEMA_A + ".pkcasex_ambig(id NUMBER PRIMARY KEY)");
      stmt.execute("CREATE TABLE " + SCHEMA_B + ".\"PKCASEX_AMBIG\"(id NUMBER PRIMARY KEY)");
    } finally {
      stmt.close();
    }
  }

  @After
  public void tearDown() throws Exception {
    if (con != null) {
      Statement stmt = con.createStatement();
      try {
        dropSchemas(stmt);
      } finally {
        stmt.close();
      }
    }
    TestUtil.closeDB(oracleCon);
    TestUtil.closeDB(con);
  }

  private void dropSchemas(Statement stmt) throws SQLException {
    stmt.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    stmt.execute("DROP SCHEMA IF EXISTS " + SCHEMA_A + " CASCADE");
    stmt.execute("DROP SCHEMA IF EXISTS " + SCHEMA_B + " CASCADE");
  }

  /** Collects the {@code (TABLE_SCHEM, TABLE_NAME, COLUMN_NAME)} rows returned by getPrimaryKeys. */
  private List<String[]> primaryKeys(Connection c, String schema, String table)
      throws SQLException {
    DatabaseMetaData md = c.getMetaData();
    List<String[]> rows = new ArrayList<String[]>();
    ResultSet rs = md.getPrimaryKeys(null, schema, table);
    try {
      while (rs.next()) {
        rows.add(new String[]{
            rs.getString("TABLE_SCHEM"),
            rs.getString("TABLE_NAME"),
            rs.getString("COLUMN_NAME")});
      }
    } finally {
      rs.close();
    }
    return rows;
  }

  private static boolean containsColumn(List<String[]> rows, String column) {
    for (String[] row : rows) {
      if (column.equalsIgnoreCase(row[2])) {
        return true;
      }
    }
    return false;
  }

  /**
   * Case-insensitive identity check. Resolution tests only care that the right object was found;
   * the exact case of the returned value is governed by oracleCase output folding, which is
   * asserted separately in {@link #testStrictVersusTrueOutputFolding()} and
   * {@link #testCustomerSingleRowIterationAndUpperCaseOutput()}.
   */
  private static void assertName(String expected, String actual) {
    assertTrue("expected name " + expected + " (any case) but was " + actual,
        expected.equalsIgnoreCase(actual));
  }

  // ---- Issue 2: the tableoid system column must not appear as a primary key column ----

  @Test
  public void testTableoidExcludedFromPartitionedPrimaryKey() throws SQLException {
    // This fix is unconditional (wrong metadata is a defect, not a case option), so validate it
    // on a plain connection with oracleMetadataCase off.
    List<String[]> rows = primaryKeys(con, SCHEMA, "pk_part");
    assertEquals("partitioned PK should expose exactly its real column", 1, rows.size());
    assertEquals("id", rows.get(0)[2]);
    assertFalse("tableoid must never be reported as a primary key column",
        containsColumn(rows, "tableoid"));
  }

  // ---- Issue 1: Oracle upper-case names resolve to the stored (lower-case) names ----

  @Test
  public void testUpperCaseSchemaAndTableCustomerScenario() throws SQLException {
    // Exactly the customer call: upper-case schema and table on lower-case stored objects.
    List<String[]> rows = primaryKeys(oracleCon, "PKCASE_REPRO", "PK_PART");
    assertEquals(1, rows.size());
    assertName("pkcase_repro", rows.get(0)[0]);
    assertName("pk_part", rows.get(0)[1]);
    assertName("id", rows.get(0)[2]);
    assertFalse(containsColumn(rows, "tableoid"));
  }

  @Test
  public void testLowerCaseStillWorksWithOracleCase() throws SQLException {
    List<String[]> rows = primaryKeys(oracleCon, "pkcase_repro", "pk_part");
    assertEquals(1, rows.size());
    assertName("id", rows.get(0)[2]);
  }

  @Test
  public void testOnlyLowerCaseTableReachableByBothStyles() throws SQLException {
    assertName("only_lower", primaryKeys(oracleCon, SCHEMA, "only_lower").get(0)[1]);
    assertName("only_lower", primaryKeys(oracleCon, SCHEMA, "ONLY_LOWER").get(0)[1]);
    assertName("only_lower", primaryKeys(oracleCon, "PKCASE_REPRO", "ONLY_LOWER").get(0)[1]);
  }

  @Test
  public void testOnlyUpperCaseTableReachableByBothStyles() throws SQLException {
    assertName("ONLY_UPPER", primaryKeys(oracleCon, SCHEMA, "ONLY_UPPER").get(0)[1]);
    assertName("ONLY_UPPER", primaryKeys(oracleCon, SCHEMA, "only_upper").get(0)[1]);
    assertName("ONLY_UPPER", primaryKeys(oracleCon, "PKCASE_REPRO", "only_upper").get(0)[1]);
  }

  @Test
  public void testMixedCaseTableExactAndUniqueFallback() throws SQLException {
    // exact match
    assertName("MixCaseTbl", primaryKeys(oracleCon, SCHEMA, "MixCaseTbl").get(0)[1]);
    // unique case-insensitive match still resolves to the single stored object
    assertName("MixCaseTbl", primaryKeys(oracleCon, SCHEMA, "MIXCASETBL").get(0)[1]);
    assertName("MixCaseTbl", primaryKeys(oracleCon, SCHEMA, "mixcasetbl").get(0)[1]);
  }

  // ---- Issue 1, extreme cases: strict matching when both cases exist ----

  @Test
  public void testExactMatchWinsWhenBothCasesExist() throws SQLException {
    // pkcasex_dup (lower, schema A) and PKCASEX_DUP (upper, schema B) both exist.
    List<String[]> upper = primaryKeys(oracleCon, null, "PKCASEX_DUP");
    assertEquals("exact upper-case match must return a single table", 1, upper.size());
    assertName("PKCASEX_DUP", upper.get(0)[1]);
    assertName(SCHEMA_B, upper.get(0)[0]);

    List<String[]> lower = primaryKeys(oracleCon, null, "pkcasex_dup");
    assertEquals("exact lower-case match must return a single table", 1, lower.size());
    assertName("pkcasex_dup", lower.get(0)[1]);
    assertName(SCHEMA_A, lower.get(0)[0]);
  }

  @Test
  public void testAmbiguousMixedCaseReturnsNothing() throws SQLException {
    // pkcasex_ambig (lower) and PKCASEX_AMBIG (upper) both exist; a mixed-case input matches
    // neither exactly, so nothing is returned rather than ambiguously returning two tables.
    List<String[]> rows = primaryKeys(oracleCon, null, "Pkcasex_Ambig");
    assertTrue("ambiguous mixed-case input must match strictly (return nothing)", rows.isEmpty());
  }

  /**
   * Same-schema coexistence of {@code casedup} and {@code CASEDUP}. On PolarDB-O this requires
   * polar_enable_pl_objects_name_default_lowercase / ...ddl... to be off (Oracle-migration
   * instances). Where the backend still enforces case-insensitive relation-name uniqueness the
   * pair cannot be created, so the test is skipped there (cross-schema coverage applies instead).
   * When both do coexist, an exact match wins and a non-exact mixed-case input returns nothing.
   */
  @Test
  public void testBothCasesCoexistInSameSchema() throws SQLException {
    final String schema = "pkcase_same";
    Statement stmt = con.createStatement();
    try {
      stmt.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      stmt.execute("CREATE SCHEMA " + schema);
      try {
        stmt.execute("SET polar_enable_pl_objects_name_default_lowercase = off");
        stmt.execute("SET polar_enable_pl_ddl_object_name_default_lowercase = off");
      } catch (SQLException ignore) {
        // GUCs only exist on PolarDB-O; ignore elsewhere.
      }
      stmt.execute("CREATE TABLE " + schema + ".\"casedup\"(id NUMBER PRIMARY KEY)");
      try {
        stmt.execute("CREATE TABLE " + schema + ".\"CASEDUP\"(id NUMBER PRIMARY KEY)");
      } catch (SQLException e) {
        Assume.assumeNoException(
            "backend enforces case-insensitive relation names; same-schema coexistence "
            + "not creatable here (cross-schema test covers this behaviour)", e);
      }

      // Both casedup and CASEDUP exist in one schema: exact match wins, returns exactly one.
      List<String[]> upper = primaryKeys(oracleCon, schema, "CASEDUP");
      assertEquals(1, upper.size());
      assertName("CASEDUP", upper.get(0)[1]);

      List<String[]> lower = primaryKeys(oracleCon, schema, "casedup");
      assertEquals(1, lower.size());
      assertName("casedup", lower.get(0)[1]);

      // Mixed-case input matches neither exactly -> strict, returns nothing.
      assertTrue(primaryKeys(oracleCon, schema, "CaseDup").isEmpty());
    } finally {
      stmt.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      stmt.close();
    }
  }

  /**
   * A package/type namespace (pg_namespace.nsppkgns != 0, e.g. an Oracle object type) must never
   * be resolved as a schema. An object type {@code "CASEPKGTYPE"} (type namespace) is made to
   * coexist with a real lower-case schema {@code casepkgtype} that holds {@code tbl}; an Oracle
   * upper-case query must resolve to the real schema and find {@code tbl}, not the type namespace
   * (which, being an exact-case match, would otherwise win). Skipped where object types / the
   * nsppkgns column are unsupported.
   */
  @Test
  public void testPackageTypeNamespaceNotResolvedAsSchema() throws SQLException {
    Statement stmt = con.createStatement();
    try {
      stmt.execute("DROP SCHEMA IF EXISTS casepkgtype CASCADE");
      try {
        stmt.execute("DROP TYPE IF EXISTS \"CASEPKGTYPE\"");
        stmt.execute("CREATE TYPE \"CASEPKGTYPE\" AS OBJECT (x number)");
      } catch (SQLException e) {
        Assume.assumeNoException("backend does not support Oracle object-type namespaces", e);
      }
      stmt.execute("CREATE SCHEMA casepkgtype");
      stmt.execute("CREATE TABLE casepkgtype.tbl(id NUMBER PRIMARY KEY)");

      List<String[]> rows = primaryKeys(oracleCon, "CASEPKGTYPE", "tbl");
      assertEquals("must resolve to the real schema, not the object-type namespace",
          1, rows.size());
      assertName("casepkgtype", rows.get(0)[0]);
      assertName("id", rows.get(0)[2]);
    } finally {
      stmt.execute("DROP SCHEMA IF EXISTS casepkgtype CASCADE");
      try {
        stmt.execute("DROP TYPE IF EXISTS \"CASEPKGTYPE\"");
      } catch (SQLException ignore) {
        // ignore cleanup failure
      }
      stmt.close();
    }
  }

  // ---- Backward compatibility: case folding stays off unless oracleCase is enabled ----

  /**
   * Reproduces the customer's exact call pattern: upper-case schema and table, one {@code next()}
   * for the PK column, then a second {@code next()} that must return {@code false}. Guards against
   * the historical bug where a partitioned table's global index leaked a {@code tableoid} phantom
   * row (which made the second next() true and was mis-read as PK_NOT_UNIQUE), and confirms the
   * returned identifier values are upper-cased under oracleCase=true.
   */
  @Test
  public void testCustomerSingleRowIterationAndUpperCaseOutput() throws SQLException {
    ResultSet rs = oracleCon.getMetaData().getPrimaryKeys(null, "PKCASE_REPRO", "PK_PART");
    try {
      assertTrue("first next() must return the single PK row", rs.next());
      assertEquals("ID", rs.getString("COLUMN_NAME"));
      assertEquals("PK_PART", rs.getString("TABLE_NAME"));
      assertEquals("PKCASE_REPRO", rs.getString("TABLE_SCHEM"));
      assertEquals("PK_PART_PK", rs.getString("PK_NAME"));
      assertFalse("single-column PK: no phantom tableoid row, second next() must be false",
          rs.next());
    } finally {
      rs.close();
    }
  }

  /**
   * oracleCase=strict upper-cases all-lower-case stored names but leaves mixed-case names
   * untouched (matching label folding); oracleCase=true always upper-cases.
   */
  @Test
  public void testStrictVersusTrueOutputFolding() throws SQLException {
    Properties props = new Properties();
    PGProperty.ORACLE_METADATA_CASE.set(props, "strict");
    Connection strictCon = TestUtil.openDB(props);
    try {
      // all-lower-case stored column -> upper-cased under strict
      assertEquals("ID", firstPkColumn(strictCon, SCHEMA, "pk_part"));
      // mixed-case stored column -> left unchanged under strict
      assertEquals("MixCol", firstPkColumn(strictCon, SCHEMA, "mixcol_tbl"));
      // oracleCase=true always upper-cases, including mixed-case
      assertEquals("MIXCOL", firstPkColumn(oracleCon, SCHEMA, "mixcol_tbl"));
    } finally {
      TestUtil.closeDB(strictCon);
    }
  }

  private String firstPkColumn(Connection c, String schema, String table) throws SQLException {
    ResultSet rs = c.getMetaData().getPrimaryKeys(null, schema, table);
    try {
      return rs.next() ? rs.getString("COLUMN_NAME") : null;
    } finally {
      rs.close();
    }
  }

  @Test
  public void testCaseFoldingDisabledByDefault() throws SQLException {
    // Plain PostgreSQL semantics: upper-case input does not match a lower-case stored name.
    // oracleCase itself must NOT enable the new metadata behaviour (only oracleMetadataCase does).
    List<String[]> rows = primaryKeys(con, "PKCASE_REPRO", "PK_PART");
    assertTrue("without oracleMetadataCase the lookup must stay case-sensitive", rows.isEmpty());

    Properties props = new Properties();
    PGProperty.ORACLE_CASE.set(props, "true");
    Connection oracleCaseOnly = TestUtil.openDB(props);
    try {
      assertTrue("legacy oracleCase alone must not change metadata lookups",
          primaryKeys(oracleCaseOnly, "PKCASE_REPRO", "PK_PART").isEmpty());
      assertFalse("system-column filter is an unconditional defect fix, active without the switch",
          containsColumn(primaryKeys(oracleCaseOnly, SCHEMA, "pk_part"), "tableoid"));
    } finally {
      TestUtil.closeDB(oracleCaseOnly);
    }
  }
}
