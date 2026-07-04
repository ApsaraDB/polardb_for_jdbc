/*
 * Test: SYNONYM for OBJECT TYPE resolution through TypeInfoCache.
 *
 * Issue: JDBC driver resolves types only via pg_type. Synonyms don't appear in pg_type,
 * so createStruct/createArrayOf with synonym names fails with "type unknown".
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeNoException;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Properties;

/**
 * Reproduces the issue where JDBC driver cannot resolve Oracle SYNONYM references
 * to OBJECT TYPEs because TypeInfoCache only looks up pg_type.
 */
public class SynonymTypeResolutionTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);

    try (Statement stmt = conn.createStatement()) {
      // Check if SYNONYM is supported
      try {
        stmt.execute("SELECT 1 FROM all_synonyms WHERE 1=0");
      } catch (SQLException e) {
        assumeNoException("SYNONYM not supported on this server", e);
      }

      // Create base types
      try {
        stmt.execute("DROP SYNONYM IF EXISTS syn_rec_test");
      } catch (SQLException ignore) {
        // ignore
      }
      try {
        stmt.execute("DROP SYNONYM IF EXISTS syn_tbl_test");
      } catch (SQLException ignore) {
        // ignore
      }
      try {
        stmt.execute("DROP TYPE IF EXISTS tbl_syn_test");
      } catch (SQLException ignore) {
        // ignore
      }
      try {
        stmt.execute("DROP TYPE IF EXISTS rec_syn_test");
      } catch (SQLException ignore) {
        // ignore
      }

      stmt.execute("CREATE OR REPLACE TYPE rec_syn_test AS OBJECT ("
          + "id NUMBER, name VARCHAR2(100))");
      stmt.execute("CREATE OR REPLACE TYPE tbl_syn_test AS TABLE OF rec_syn_test");

      // Create synonyms pointing to the types
      stmt.execute("CREATE OR REPLACE SYNONYM syn_rec_test FOR rec_syn_test");
      stmt.execute("CREATE OR REPLACE SYNONYM syn_tbl_test FOR tbl_syn_test");

      // Create a simple procedure that accepts the collection type
      stmt.execute("CREATE OR REPLACE PROCEDURE p_syn_test("
          + "p_data IN tbl_syn_test) IS "
          + "BEGIN NULL; END;");

    } catch (SQLException e) {
      assumeNoException("Cannot set up synonym test objects", e);
    }
  }

  @After
  public void tearDown() throws Exception {
    if (conn != null) {
      try (Statement stmt = conn.createStatement()) {
        try {
          stmt.execute("DROP PROCEDURE IF EXISTS p_syn_test");
        } catch (SQLException ignore) {
          // ignore
        }
        try {
          stmt.execute("DROP SYNONYM IF EXISTS syn_rec_test");
        } catch (SQLException ignore) {
          // ignore
        }
        try {
          stmt.execute("DROP SYNONYM IF EXISTS syn_tbl_test");
        } catch (SQLException ignore) {
          // ignore
        }
        try {
          stmt.execute("DROP TYPE IF EXISTS tbl_syn_test");
        } catch (SQLException ignore) {
          // ignore
        }
        try {
          stmt.execute("DROP TYPE IF EXISTS rec_syn_test");
        } catch (SQLException ignore) {
          // ignore
        }
      } catch (SQLException ignore) {
        // ignore
      }
      conn.close();
    }
  }

  /**
   * Diagnostic: dump all_synonyms structure and our specific synonym entries.
   */
  @Test
  public void testDiag_allSynonymsStructure() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      ResultSet rs = stmt.executeQuery(
          "SELECT * FROM all_synonyms WHERE synonym_name LIKE '%syn_%' OR synonym_name LIKE '%SYN_%'");
      ResultSetMetaData md = rs.getMetaData();
      System.out.println("[diag] all_synonyms columns:");
      for (int i = 1; i <= md.getColumnCount(); i++) {
        System.out.println("  " + i + ": " + md.getColumnName(i));
      }
      while (rs.next()) {
        StringBuilder sb = new StringBuilder("[diag] ROW: ");
        for (int i = 1; i <= md.getColumnCount(); i++) {
          sb.append(md.getColumnName(i)).append("=").append(rs.getString(i)).append(", ");
        }
        System.out.println(sb);
      }
      rs.close();
    }
  }

  /**
   * Baseline: createStruct with the REAL type name should work.
   */
  @Test
  public void testCreateStructWithRealTypeName() throws SQLException {
    Struct struct = conn.createStruct("rec_syn_test", new Object[]{1, "hello"});
    assertNotNull("createStruct with real type name should succeed", struct);
  }

  /**
   * Issue: createStruct with SYNONYM name — expected to fail if TypeInfoCache
   * doesn't resolve synonyms.
   */
  @Test
  public void testCreateStructWithSynonymName() throws SQLException {
    Struct struct = conn.createStruct("syn_rec_test", new Object[]{1, "hello"});
    assertNotNull("createStruct with synonym name should succeed", struct);
  }

  /**
   * Baseline: createArrayOf with real type name.
   */
  @Test
  public void testCreateArrayOfWithRealTypeName() throws SQLException {
    Struct struct = conn.createStruct("rec_syn_test", new Object[]{1, "test"});
    Array array = conn.createArrayOf("tbl_syn_test", new Struct[]{struct});
    assertNotNull("createArrayOf with real type name should succeed", array);
  }

  /**
   * Issue: createArrayOf with SYNONYM name.
   */
  @Test
  public void testCreateArrayOfWithSynonymName() throws SQLException {
    Struct struct = conn.createStruct("syn_rec_test", new Object[]{1, "test"});
    Array array = conn.createArrayOf("syn_tbl_test", new Struct[]{struct});
    assertNotNull("createArrayOf with synonym name should succeed", array);
  }

  /**
   * End-to-end: use synonyms for both Struct and Array, call a procedure.
   */
  @Test
  public void testCallProcedureWithSynonymTypes() throws SQLException {
    Struct struct = conn.createStruct("syn_rec_test", new Object[]{1, "e2e"});
    Array array = conn.createArrayOf("syn_tbl_test", new Struct[]{struct});

    try (CallableStatement cs = conn.prepareCall("{ call p_syn_test(?) }")) {
      cs.setArray(1, array);
      cs.execute();
    }
  }
}
