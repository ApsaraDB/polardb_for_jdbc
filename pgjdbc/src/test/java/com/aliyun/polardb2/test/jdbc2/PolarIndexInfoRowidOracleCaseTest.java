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
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Regression tests for {@code getIndexInfo} / {@code getPrimaryKeys} around PolarDB-O internal
 * columns and Oracle-migrated hidden columns.
 *
 * <p>The expected results below were confirmed to match Oracle (via ojdbc8 against a live Oracle
 * instance) for the equivalent DDL. Oracle, for a table with a primary key on a visible column and
 * a UNIQUE constraint on an INVISIBLE column, reports through {@code getIndexInfo} exactly the two
 * index columns {PK column, invisible column} and never surfaces a ROWID pseudo-column. PolarDB-O
 * must behave the same, which means:
 * <ul>
 *   <li>the PolarDB-only system column {@code polar_sys_rowid_attr} and its unique index are
 *       hidden (Oracle has no such user-visible index);</li>
 *   <li>a UNIQUE constraint on an INVISIBLE user column is still reported (Oracle reports it);</li>
 *   <li>the {@code tableoid} system column embedded in a partitioned table's global unique index
 *       is not reported as an index column.</li>
 * </ul>
 */
public class PolarIndexInfoRowidOracleCaseTest {

  private static final String SCHEMA = "idxrowid_repro";
  private static final String HIDDEN_COL = "_polarHiddenprefix_dts_ROWID";

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
      stmt.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
      stmt.execute("CREATE SCHEMA " + SCHEMA);

      // PK on a visible column + a UNIQUE constraint on an INVISIBLE column (DTS ROWID pattern).
      stmt.execute("CREATE TABLE " + SCHEMA + ".t(id NUMBER, name VARCHAR2(50), "
          + "CONSTRAINT t_pk PRIMARY KEY(id))");
      stmt.execute("ALTER TABLE " + SCHEMA + ".t ADD \"" + HIDDEN_COL + "\" VARCHAR2(100) invisible");
      stmt.execute("ALTER TABLE " + SCHEMA + ".t ADD CONSTRAINT \"UK_t_ROWID\" unique(\""
          + HIDDEN_COL + "\")");

      // Partitioned table whose PK does not include the partition key -> global unique index that
      // embeds the tableoid system column.
      stmt.execute("CREATE TABLE " + SCHEMA + ".part_t(id NUMBER NOT NULL, d DATE, "
          + "CONSTRAINT part_t_pk PRIMARY KEY(id)) PARTITION BY RANGE(d)("
          + "PARTITION p1 VALUES LESS THAN(DATE '2020-01-01'), "
          + "PARTITION pm VALUES LESS THAN(MAXVALUE))");

      // Materialized view with a unique index: Oracle exposes a MV's indexes/PK via metadata,
      // so name resolution must reach materialized views ('m') too.
      stmt.execute("CREATE TABLE " + SCHEMA + ".base(id NUMBER PRIMARY KEY, name VARCHAR2(50))");
      stmt.execute("CREATE MATERIALIZED VIEW " + SCHEMA + ".mv AS SELECT id, name FROM "
          + SCHEMA + ".base");
      stmt.execute("CREATE UNIQUE INDEX mv_uidx ON " + SCHEMA + ".mv(id)");

      // Table with an expression/function-based unique index. Oracle returns function-based
      // indexes through getIndexInfo, so PolarDB must keep them (attnum 0, not a system column).
      stmt.execute("CREATE TABLE " + SCHEMA + ".fx(id NUMBER PRIMARY KEY, name VARCHAR2(50))");
      stmt.execute("CREATE UNIQUE INDEX fx_lower_uidx ON " + SCHEMA + ".fx(lower(name))");
    } finally {
      stmt.close();
    }
  }

  @After
  public void tearDown() throws Exception {
    if (con != null) {
      Statement stmt = con.createStatement();
      try {
        stmt.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
      } finally {
        stmt.close();
      }
    }
    TestUtil.closeDB(oracleCon);
    TestUtil.closeDB(con);
  }

  private Set<String> indexColumns(Connection c, String schema, String table, boolean unique)
      throws SQLException {
    Set<String> cols = new LinkedHashSet<String>();
    ResultSet rs = c.getMetaData().getIndexInfo(null, schema, table, unique, false);
    try {
      while (rs.next()) {
        String col = rs.getString("COLUMN_NAME");
        if (col != null) {
          cols.add(col);
        }
      }
    } finally {
      rs.close();
    }
    return cols;
  }

  private Set<String> primaryKeyColumns(Connection c, String schema, String table)
      throws SQLException {
    Set<String> cols = new LinkedHashSet<String>();
    ResultSet rs = c.getMetaData().getPrimaryKeys(null, schema, table);
    try {
      while (rs.next()) {
        cols.add(rs.getString("COLUMN_NAME"));
      }
    } finally {
      rs.close();
    }
    return cols;
  }

  /**
   * Oracle exposes {PK column, invisible-UK column} and nothing else; PolarDB must match, i.e.
   * hide {@code polar_sys_rowid_attr} but keep the invisible column's unique constraint.
   */
  @Test
  public void testGetIndexInfoMatchesOracleForRowidAndInvisibleColumn() throws SQLException {
    Set<String> cols = indexColumns(oracleCon, SCHEMA, "t", false);
    assertTrue("PK column must be present", cols.contains("ID"));
    assertTrue("invisible column's unique constraint must be present (Oracle-compatible)",
        cols.contains(HIDDEN_COL.toUpperCase(Locale.ROOT)));
    assertFalse("PolarDB internal ROWID column must be hidden",
        containsIgnoreCase(cols, "polar_sys_rowid_attr"));
    assertEquals("exactly {PK column, invisible-UK column} like Oracle", 2, cols.size());
  }

  @Test
  public void testGetIndexInfoUniqueHidesRowidKeepsInvisible() throws SQLException {
    Set<String> cols = indexColumns(oracleCon, SCHEMA, "t", true);
    assertTrue(cols.contains("ID"));
    assertTrue(cols.contains(HIDDEN_COL.toUpperCase(Locale.ROOT)));
    assertFalse(containsIgnoreCase(cols, "polar_sys_rowid_attr"));
  }

  /** tableoid embedded in a partitioned global unique index must not appear as an index column. */
  @Test
  public void testGetIndexInfoExcludesTableoidForPartitionedTable() throws SQLException {
    Set<String> cols = indexColumns(oracleCon, SCHEMA, "part_t", false);
    assertTrue(cols.contains("ID"));
    assertFalse("tableoid system column must be hidden", containsIgnoreCase(cols, "tableoid"));
    assertEquals(1, cols.size());
  }

  /**
   * The system-column/rowid filters are unconditional defect fixes (wrong metadata is not a case
   * option): even with oracleMetadataCase off (default) the partitioned global index must not
   * leak tableoid through getIndexInfo or getPrimaryKeys.
   */
  @Test
  public void testTableoidFilteredEvenWithSwitchOff() throws SQLException {
    Set<String> cols = indexColumns(con, SCHEMA, "part_t", false);
    assertTrue(cols.contains("id"));
    assertFalse("tableoid must be filtered even with oracleMetadataCase off",
        containsIgnoreCase(cols, "tableoid"));
    assertFalse(containsIgnoreCase(primaryKeyColumns(con, SCHEMA, "part_t"), "tableoid"));
  }

  /**
   * getIndexInfo honours Oracle-style case folding for schema/table names when oracleCase=true,
   * and folds the returned identifier values to upper case as well.
   */
  @Test
  public void testGetIndexInfoResolvesUpperCaseNames() throws SQLException {
    Set<String> cols = indexColumns(oracleCon, "IDXROWID_REPRO", "T", false);
    assertTrue("upper-case schema/table must resolve, PK column returned upper case",
        cols.contains("ID"));
    assertTrue("invisible column's unique constraint returned upper case",
        cols.contains(HIDDEN_COL.toUpperCase(Locale.ROOT)));
    assertFalse(cols.contains("polar_sys_rowid_attr"));
    assertFalse(cols.contains("POLAR_SYS_ROWID_ATTR"));
  }

  /** getPrimaryKeys must return only the visible PK column, never the ROWID system column. */
  @Test
  public void testGetPrimaryKeysHidesRowidColumn() throws SQLException {
    Set<String> pk = primaryKeyColumns(oracleCon, SCHEMA, "t");
    assertEquals(1, pk.size());
    assertTrue(pk.contains("ID"));
    assertFalse(containsIgnoreCase(pk, "polar_sys_rowid_attr"));
  }

  /**
   * Oracle exposes a materialized view's indexes via getIndexInfo, so name resolution must reach
   * materialized views and honour Oracle-style upper-case names when oracleCase is enabled.
   */
  @Test
  public void testGetIndexInfoResolvesMaterializedView() throws SQLException {
    assertTrue("MV unique index must be visible (lower-case)",
        indexColumns(con, SCHEMA, "mv", true).contains("id"));
    // oracleCase=true resolves the upper-case name AND folds the returned column to upper case.
    assertTrue("MV unique index must be visible via upper-case name (oracleCase)",
        indexColumns(oracleCon, "IDXROWID_REPRO", "MV", true).contains("ID"));
  }

  /**
   * Expression / function-based indexes must still be reported (Oracle returns them too). This
   * guards against over-filtering system columns with a bare attnum &gt; 0 check (expression
   * columns have attnum 0), while confirming genuine system columns stay hidden.
   */
  @Test
  public void testGetIndexInfoKeepsExpressionIndex() throws SQLException {
    Set<String> cols = indexColumns(oracleCon, SCHEMA, "fx", true);
    assertTrue("functional index expression column must be kept",
        containsIgnoreCase(cols, "lower((name)::text)"));
    assertNoSystemColumns(cols);
  }

  private static boolean containsIgnoreCase(Set<String> cols, String expected) {
    for (String col : cols) {
      if (expected.equalsIgnoreCase(col)) {
        return true;
      }
    }
    return false;
  }

  /** No metadata result may ever expose a PolarDB-internal or PostgreSQL system column. */
  private static void assertNoSystemColumns(Set<String> cols) {
    for (String sysCol : new String[]{"polar_sys_rowid_attr", "tableoid", "ctid", "xmin",
        "xmax", "cmin", "cmax"}) {
      assertFalse("system column must never appear: " + sysCol,
          containsIgnoreCase(cols, sysCol));
    }
  }

  /**
   * Ordinary (non-partitioned) table: getPrimaryKeys / getIndexInfo return the real PK column
   * and never a PolarDB-internal or system column, matching Oracle.
   */
  @Test
  public void testOrdinaryTablePrimaryKeyAndIndex() throws SQLException {
    Set<String> pk = primaryKeyColumns(oracleCon, SCHEMA, "base");
    assertEquals(1, pk.size());
    assertTrue(pk.contains("ID"));
    assertFalse(containsIgnoreCase(pk, "polar_sys_rowid_attr"));

    Set<String> idx = indexColumns(oracleCon, SCHEMA, "base", false);
    assertTrue("ordinary table index must expose its PK column", idx.contains("ID"));
    assertNoSystemColumns(idx);
  }

  /**
   * A partition (child of a partitioned table) is not an independent table in Oracle, where
   * getPrimaryKeys / getIndexInfo on a partition name return nothing. PolarDB-O keeps the
   * partitioned PK in a global index on the parent, so the child partition likewise exposes no
   * primary key or (user) index - matching Oracle. (The parent is covered separately and does
   * return its PK without tableoid.)
   */
  @Test
  public void testChildPartitionMatchesOracle() throws SQLException {
    String child = firstChildPartition("part_t");
    assertNotNull("expected a child partition of part_t", child);
    assertTrue("partition must expose no primary key, like Oracle",
        primaryKeyColumns(con, SCHEMA, child).isEmpty());
    assertTrue("partition must expose no user index, like Oracle",
        indexColumns(con, SCHEMA, child, false).isEmpty());
  }

  /** Resolve the actual name of a child partition of {@code parent} within {@link #SCHEMA}. */
  private String firstChildPartition(String parent) throws SQLException {
    String sql = "SELECT c.relname FROM pg_catalog.pg_inherits i "
        + "JOIN pg_catalog.pg_class c ON c.oid = i.inhrelid "
        + "JOIN pg_catalog.pg_class pr ON pr.oid = i.inhparent "
        + "JOIN pg_catalog.pg_namespace n ON n.oid = pr.relnamespace "
        + "WHERE n.nspname = '" + SCHEMA + "' AND pr.relname = '" + parent + "' "
        + "ORDER BY c.relname LIMIT 1";
    Statement stmt = con.createStatement();
    try {
      ResultSet rs = stmt.executeQuery(sql);
      try {
        return rs.next() ? rs.getString(1) : null;
      } finally {
        rs.close();
      }
    } finally {
      stmt.close();
    }
  }
}
