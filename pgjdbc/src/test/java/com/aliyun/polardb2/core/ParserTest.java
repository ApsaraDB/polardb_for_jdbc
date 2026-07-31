/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.jdbc.EscapeSyntaxCallMode;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.sql.SQLException;
import java.util.List;

/**
 * Test cases for the Parser.
 * @author Jeremy Whiting jwhiting@redhat.com
 */
public class ParserTest {

  /**
   * Test to make sure delete command is detected by parser and detected via
   * api. Mix up the case of the command to check detection continues to work.
   */
  @Test
  public void testDeleteCommandParsing() {
    char[] command = new char[6];
    "DELETE".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse upper case command.", Parser.parseDeleteKeyword(command, 0));
    "DelEtE".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseDeleteKeyword(command, 0));
    "deleteE".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseDeleteKeyword(command, 0));
    "delete".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse lower case command.", Parser.parseDeleteKeyword(command, 0));
    "Delete".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseDeleteKeyword(command, 0));
  }

  /**
   * Test UPDATE command parsing.
   */
  @Test
  public void testUpdateCommandParsing() {
    char[] command = new char[6];
    "UPDATE".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse upper case command.", Parser.parseUpdateKeyword(command, 0));
    "UpDateE".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseUpdateKeyword(command, 0));
    "updatE".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseUpdateKeyword(command, 0));
    "Update".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseUpdateKeyword(command, 0));
    "update".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse lower case command.", Parser.parseUpdateKeyword(command, 0));
  }

  /**
   * Test MOVE command parsing.
   */
  @Test
  public void testMoveCommandParsing() {
    char[] command = new char[4];
    "MOVE".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse upper case command.", Parser.parseMoveKeyword(command, 0));
    "mOVe".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseMoveKeyword(command, 0));
    "movE".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseMoveKeyword(command, 0));
    "Move".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseMoveKeyword(command, 0));
    "move".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse lower case command.", Parser.parseMoveKeyword(command, 0));
  }

  /**
   * Test WITH command parsing.
   */
  @Test
  public void testWithCommandParsing() {
    char[] command = new char[4];
    "WITH".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse upper case command.", Parser.parseWithKeyword(command, 0));
    "wITh".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseWithKeyword(command, 0));
    "witH".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseWithKeyword(command, 0));
    "With".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseWithKeyword(command, 0));
    "with".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse lower case command.", Parser.parseWithKeyword(command, 0));
  }

  /**
   * Test SELECT command parsing.
   */
  @Test
  public void testSelectCommandParsing() {
    char[] command = new char[6];
    "SELECT".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse upper case command.", Parser.parseSelectKeyword(command, 0));
    "sELect".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseSelectKeyword(command, 0));
    "selecT".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseSelectKeyword(command, 0));
    "Select".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseSelectKeyword(command, 0));
    "select".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse lower case command.", Parser.parseSelectKeyword(command, 0));
  }

  @Test
  public void testEscapeProcessing() throws Exception {
    assertEquals("DATE '1999-01-09'", Parser.replaceProcessing("{d '1999-01-09'}", true, false));
    assertEquals("DATE '1999-01-09'", Parser.replaceProcessing("{D  '1999-01-09'}", true, false));
    assertEquals("TIME '20:00:03'", Parser.replaceProcessing("{t '20:00:03'}", true, false));
    assertEquals("TIME '20:00:03'", Parser.replaceProcessing("{T '20:00:03'}", true, false));
    assertEquals("TIMESTAMP '1999-01-09 20:11:11.123455'", Parser.replaceProcessing("{ts '1999-01-09 20:11:11.123455'}", true, false));
    assertEquals("TIMESTAMP '1999-01-09 20:11:11.123455'", Parser.replaceProcessing("{Ts '1999-01-09 20:11:11.123455'}", true, false));

    assertEquals("user", Parser.replaceProcessing("{fn user()}", true, false));
    assertEquals("cos(1)", Parser.replaceProcessing("{fn cos(1)}", true, false));
    assertEquals("extract(week from DATE '2005-01-24')", Parser.replaceProcessing("{fn week({d '2005-01-24'})}", true, false));

    assertEquals("\"T1\" LEFT OUTER JOIN t2 ON \"T1\".id = t2.id",
            Parser.replaceProcessing("{oj \"T1\" LEFT OUTER JOIN t2 ON \"T1\".id = t2.id}", true, false));

    assertEquals("ESCAPE '_'", Parser.replaceProcessing("{escape '_'}", true, false));

    // nothing should be changed in that case, no valid escape code
    assertEquals("{obj : 1}", Parser.replaceProcessing("{obj : 1}", true, false));
  }

  @Test
  public void testModifyJdbcCall() throws SQLException {
    assertEquals("select * from pack_getValue(?) as result", Parser.modifyJdbcCall("{ ? = call pack_getValue}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT, false).getSql());
    assertEquals("select * from pack_getValue(?,?)  as result", Parser.modifyJdbcCall("{ ? = call pack_getValue(?) }", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT, false).getSql());
    assertEquals("select * from pack_getValue(?) as result", Parser.modifyJdbcCall("{ ? = call pack_getValue()}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT, false).getSql());
    assertEquals("select * from pack_getValue(?,?,?,?)  as result", Parser.modifyJdbcCall("{ ? = call pack_getValue(?,?,?) }", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT, false).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT, false).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.CALL_IF_NO_RETURN, false).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.CALL, false).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT, false).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.CALL_IF_NO_RETURN, false).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.CALL, false).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT, false).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.CALL_IF_NO_RETURN, false).getSql());
    assertEquals("call lower(?,?)", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.CALL, false).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT, false).getSql());
    assertEquals("call lower(?,?)", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.CALL_IF_NO_RETURN, false).getSql());
    assertEquals("call lower(?,?)", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.CALL, false).getSql());

    // POLAR: design.md contract - callFunctionMode=true + return placeholder "? =" -> EXEC
    assertEquals("exec mysumfunc(?,?)", Parser.modifyJdbcCall("{ ? = call mysumfunc(?,?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.CALL, true).getSql());
    assertEquals("exec mysumfunc(?,?)", Parser.modifyJdbcCall("{ ? = call mysumfunc(?,?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.CALL_IF_NO_RETURN, true).getSql());
    assertEquals("exec pack_getValue()", Parser.modifyJdbcCall("{ ? = call pack_getValue}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.CALL, true).getSql());
    // No return placeholder -> CALL
    assertEquals("call myioproc(?,?)", Parser.modifyJdbcCall("{call myioproc(?,?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.CALL, true).getSql());
    // callFunctionMode=false: legacy path keeps CALL with placeholder injection
    assertEquals("call mysumfunc(?,?,?)", Parser.modifyJdbcCall("{ ? = call mysumfunc(?,?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.CALL, false).getSql());
    // SELECT mode is unaffected by callFunctionMode
    assertEquals("select * from mysumfunc(?,?) as result", Parser.modifyJdbcCall("{ ? = call mysumfunc(?,?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT, true).getSql());
  }

  @Test
  public void testUnterminatedEscape() throws Exception {
    assertEquals("{oj ", Parser.replaceProcessing("{oj ", true, false));
  }

  @Test
  @Ignore(value = "returning in the select clause is hard to distinguish from insert ... returning *")
  public void insertSelectFakeReturning() throws SQLException {
    String query =
        "insert test(id, name) select 1, 'value' as RETURNING from test2";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(
            query, true, true, true, true, true, false, false);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();
    Assert.assertFalse("Query does not have returning clause " + query, returningKeywordPresent);
  }

  @Test
  public void insertSelectReturning() throws SQLException {
    String query =
        "insert test(id, name) select 1, 'value' from test2 RETURNING id";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(
            query, true, true, true, true, true, false, false);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();
    Assert.assertTrue("Query has a returning clause " + query, returningKeywordPresent);
  }

  @Test
  public void insertReturningInWith() throws SQLException {
    String query =
        "with x as (insert into mytab(x) values(1) returning x) insert test(id, name) select 1, 'value' from test2";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(
            query, true, true, true, true, true, false, false);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();
    Assert.assertFalse("There's no top-level <<returning>> clause " + query, returningKeywordPresent);
  }

  @Test
  public void insertBatchedReWriteOnConflict() throws SQLException {
    String query = "insert into test(id, name) values (:id,:name) ON CONFLICT (id) DO NOTHING";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, false, false);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertEquals(34, command.getBatchRewriteValuesBraceOpenPosition());
    Assert.assertEquals(44, command.getBatchRewriteValuesBraceClosePosition());
  }

  @Test
  public void insertBatchedReWriteOnConflictUpdateBind() throws SQLException {
    String query = "insert into test(id, name) values (?,?) ON CONFLICT (id) UPDATE SET name=?";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, false, false);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertFalse("update set name=? is NOT compatible with insert rewrite", command.isBatchedReWriteCompatible());
  }

  @Test
  public void insertBatchedReWriteOnConflictUpdateConstant() throws SQLException {
    String query = "insert into test(id, name) values (?,?) ON CONFLICT (id) UPDATE SET name='default'";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, false, false);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertTrue("update set name='default' is compatible with insert rewrite", command.isBatchedReWriteCompatible());
  }

  @Test
  public void insertMultiInsert() throws SQLException {
    String query =
        "insert into test(id, name) values (:id,:name),(:id,:name) ON CONFLICT (id) DO NOTHING";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, false, false);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertEquals(34, command.getBatchRewriteValuesBraceOpenPosition());
    Assert.assertEquals(56, command.getBatchRewriteValuesBraceClosePosition());
  }

  @Test
  public void valuesTableParse() throws SQLException {
    String query = "insert into values_table (id, name) values (?,?)";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, false, false);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertEquals(43,command.getBatchRewriteValuesBraceOpenPosition());
    Assert.assertEquals(49,command.getBatchRewriteValuesBraceClosePosition());

    query = "insert into table_values (id, name) values (?,?)";
    qry = Parser.parseJdbcSql(query, true, true, true, true, true, false, false);
    command = qry.get(0).getCommand();
    Assert.assertEquals(43,command.getBatchRewriteValuesBraceOpenPosition());
    Assert.assertEquals(49,command.getBatchRewriteValuesBraceClosePosition());
  }

  @Test
  public void createTableParseWithOnDeleteClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "create table \"testTable\" (\"id\" INT SERIAL NOT NULL PRIMARY KEY, \"foreignId\" INT REFERENCES \"otherTable\" (\"id\") ON DELETE NO ACTION)";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, false, false, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertFalse("No returning keyword should be present", command.isReturningKeywordPresent());
    Assert.assertEquals(SqlCommandType.CREATE, command.getType());
  }

  @Test
  public void createTableParseWithOnUpdateClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "create table \"testTable\" (\"id\" INT SERIAL NOT NULL PRIMARY KEY, \"foreignId\" INT REFERENCES \"otherTable\" (\"id\")) ON UPDATE NO ACTION";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, false, false, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertFalse("No returning keyword should be present", command.isReturningKeywordPresent());
    Assert.assertEquals(SqlCommandType.CREATE, command.getType());
  }

  @Test
  public void alterTableParseWithOnDeleteClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "alter table \"testTable\" ADD \"foreignId\" INT REFERENCES \"otherTable\" (\"id\") ON DELETE NO ACTION";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, false, false, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertFalse("No returning keyword should be present", command.isReturningKeywordPresent());
    Assert.assertEquals(SqlCommandType.ALTER, command.getType());
  }

  @Test
  public void alterTableParseWithOnUpdateClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "alter table \"testTable\" ADD \"foreignId\" INT REFERENCES \"otherTable\" (\"id\") ON UPDATE RESTRICT";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, false, false, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertFalse("No returning keyword should be present", command.isReturningKeywordPresent());
    Assert.assertEquals(SqlCommandType.ALTER, command.getType());
  }

  @Test
  public void testParseV14functions() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "CREATE OR REPLACE FUNCTION asterisks(n int)\n"
        + "  RETURNS SETOF text\n"
        + "  LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE\n"
        + "BEGIN ATOMIC\n"
        + "SELECT repeat('*', g) FROM generate_series (1, n) g; \n"
        + "END;";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, false, false, returningColumns);
    Assert.assertNotNull(qry);
    Assert.assertEquals("There should only be one query returned here", 1, qry.size());
  }

  /**
   * POLAR: Test that COMPOUND TRIGGER with trailing slash is parsed correctly.
   * The slash should be stripped and not sent to the server.
   */
  @Test
  public void testCompoundTriggerWithTrailingSlash() throws SQLException {
    String query = "CREATE OR REPLACE TRIGGER test_trigger\n"
        + "FOR INSERT OR UPDATE ON test_table\n"
        + "COMPOUND TRIGGER\n"
        + "BEFORE EACH ROW IS\n"
        + "  v_val number;\n"
        + "  begin\n"
        + "    v_val := 1;\n"
        + "END BEFORE EACH ROW;\n"
        + "AFTER STATEMENT IS\n"
        + "BEGIN\n"
        + "  NULL;\n"
        + "END AFTER STATEMENT;\n"
        + "end test_trigger;\n"
        + "\n"
        + "/";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, false, true, true, false, false);
    Assert.assertNotNull(qry);
    Assert.assertEquals("COMPOUND TRIGGER + slash should produce one query", 1, qry.size());
    String sql = qry.get(0).nativeSql;
    Assert.assertFalse("Slash should not appear in parsed SQL", sql.trim().endsWith("/"));
  }

  /**
   * POLAR: Test that COMPOUND TRIGGER DDL + slash + ALTER TRIGGER is split into two queries.
   */
  @Test
  public void testCompoundTriggerWithSlashAndAlter() throws SQLException {
    String query = "CREATE OR REPLACE TRIGGER test_trigger\n"
        + "FOR INSERT OR UPDATE ON test_table\n"
        + "COMPOUND TRIGGER\n"
        + "BEFORE EACH ROW IS\n"
        + "  v_val number;\n"
        + "  begin\n"
        + "    v_val := 1;\n"
        + "END BEFORE EACH ROW;\n"
        + "AFTER STATEMENT IS\n"
        + "BEGIN\n"
        + "  NULL;\n"
        + "END AFTER STATEMENT;\n"
        + "end test_trigger;\n"
        + "\n"
        + "/\n"
        + "ALTER TRIGGER test_trigger ENABLE";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, false, false);
    Assert.assertNotNull(qry);
    Assert.assertEquals("DDL + slash + ALTER should produce two queries", 2, qry.size());
    String firstSql = qry.get(0).nativeSql;
    String secondSql = qry.get(1).nativeSql;
    Assert.assertFalse("First query should not contain slash", firstSql.contains("\n/\n"));
    Assert.assertTrue("Second query should be ALTER TRIGGER",
        secondSql.trim().toUpperCase(java.util.Locale.ROOT).startsWith("ALTER"));
  }

  /**
   * POLAR: Test simple trigger with trailing slash (regression check).
   */
  @Test
  public void testSimpleTriggerWithTrailingSlash() throws SQLException {
    String query = "CREATE OR REPLACE TRIGGER test_trigger\n"
        + "BEFORE INSERT ON test_table\n"
        + "FOR EACH ROW\n"
        + "BEGIN\n"
        + "  NULL;\n"
        + "END test_trigger;\n"
        + "\n"
        + "/";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, false, true, true, false, false);
    Assert.assertNotNull(qry);
    Assert.assertEquals("Simple trigger + slash should produce one query", 1, qry.size());
    String sql = qry.get(0).nativeSql;
    Assert.assertFalse("Slash should not appear in parsed SQL", sql.trim().endsWith("/"));
  }

  /**
   * POLAR: Test procedure with END label_name; and trailing slash.
   */
  @Test
  public void testProcedureEndLabelWithSlash() throws SQLException {
    String query = "CREATE OR REPLACE PROCEDURE my_proc IS\n"
        + "BEGIN\n"
        + "  NULL;\n"
        + "END my_proc;\n"
        + "/";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, false, true, true, false, false);
    Assert.assertNotNull(qry);
    Assert.assertEquals("Procedure with END label + slash should produce one query", 1, qry.size());
    String sql = qry.get(0).nativeSql;
    Assert.assertFalse("Slash should not appear in parsed SQL", sql.trim().endsWith("/"));
  }

  /**
   * POLAR: Test that division operator inside PL/SQL is not affected by slash handling.
   */
  @Test
  public void testDivisionOperatorNotAffected() throws SQLException {
    String query = "CREATE OR REPLACE PROCEDURE div_test IS\n"
        + "  v_result number;\n"
        + "BEGIN\n"
        + "  v_result := 10 / 2;\n"
        + "END div_test;\n"
        + "/";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, false, true, true, false, false);
    Assert.assertNotNull(qry);
    Assert.assertEquals("Division inside procedure should not cause split", 1, qry.size());
    String sql = qry.get(0).nativeSql;
    Assert.assertTrue("Division operator should be preserved", sql.contains("10 / 2"));
  }

  /**
   * POLAR: CREATE PACKAGE preceded by line comments must still be recognized as a
   * single block and not be split on the semicolons of its variable declarations.
   */
  @Test
  public void testCreatePackageWithLeadingComments() throws SQLException {
    String query = "-----------------------------------------\n"
        + "-- MOD. DATE : 20 Dec 2020\n"
        + "-- MOD. DESC : Clone NFO_CD for CWS Revamp\n"
        + "-----------------------------------------\n"
        + "CREATE OR REPLACE PACKAGE PCF_CD\n"
        + "IS\n"
        + "   nfo_pol_num   VARCHAR2(10) := ' ';\n"
        + "   nfo_date      DATE := null;\n"
        + "   nfo_gross_cv  NUMBER(15,2) := 0;  -- trailing comment\n"
        + "END;";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(query, true, false, true, false, true, false, false);
    Assert.assertNotNull(qry);
    Assert.assertEquals("Package with leading comments should produce one query", 1, qry.size());
    String sql = qry.get(0).nativeSql;
    Assert.assertTrue("All declarations should stay in the single query",
        sql.contains("nfo_pol_num") && sql.contains("nfo_gross_cv") && sql.contains("END"));
  }

  /**
   * POLAR: comment stripping for the keyword scan must not eat semicolons in string
   * literals containing dashes, i.e. plain multi-statement split still works.
   */
  @Test
  public void testMultiStatementSplitWithDashInLiteral() throws SQLException {
    String query = "select '--not a comment; still text' ; select 2";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(query, true, false, true, false, true, false, false);
    Assert.assertNotNull(qry);
    Assert.assertEquals("Two statements expected", 2, qry.size());
    Assert.assertTrue("Literal must be preserved",
        qry.get(0).nativeSql.contains("--not a comment; still text"));
  }
}
