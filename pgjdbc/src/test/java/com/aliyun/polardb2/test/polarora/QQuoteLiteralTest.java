/*
 * Portions Copyright (c) 2026, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Verifies Oracle q-quote string literal handling by the PolarDB JDBC parser.
 *
 * <p>Oracle's alternative quoting mechanism {@code q'[ ... ]'} allows embedding
 * single quotes, JDBC {@code ?} positional markers and {@code :name} named
 * parameter markers as <b>literal characters</b> inside a string.  The parser
 * must NOT interpret these as a string terminator, a bind parameter or a named
 * parameter.
 *
 * <p>Example case under test:
 * <pre>{@code
 *   SELECT q'[te:s't ? :44] ]' FROM dual
 * }</pre>
 *
 * <p>The q-quote opens with {@code q'[} and closes with {@code ]'}, so the
 * literal content is exactly {@code te:s't ? :44] } (14 characters, including
 * the trailing space and the embedded {@code '}, {@code ?} and {@code :44}).
 */
public class QQuoteLiteralTest {

  private static final String Q_QUOTE_SQL =
      "SELECT q'[te:s't ? :44] ]' FROM dual";

  /** Literal content between the opening {@code q'[} and the closing {@code ]'}. */
  private static final String EXPECTED = "te:s't ? :44] ";

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    conn = TestUtil.openDB(props);
  }

  @After
  public void tearDown() throws SQLException {
    if (conn != null) {
      conn.close();
    }
  }

  /** Execute through {@link Statement} — no bind parsing involved. */
  @Test
  public void testQQuoteViaStatement() throws SQLException {
    try (Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery(Q_QUOTE_SQL)) {
      Assert.assertTrue("result set should have a row", rs.next());
      String actual = rs.getString(1);
      Assert.assertEquals("q-quote literal content mismatch", EXPECTED, actual);
      Assert.assertFalse("only one row expected", rs.next());
    }
  }

  /**
   * Execute through {@link PreparedStatement} with default parser settings.
   *
   * <p>The {@code ?} and {@code :44} inside the q-quote literal must NOT be
   * recognised as bind parameters — {@code getParameterMetaData()} should
   * report zero parameters, and the query should execute without any
   * {@code setXxx} calls.
   */
  @Test
  public void testQQuoteViaPreparedStatement() throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(Q_QUOTE_SQL)) {
      Assert.assertEquals(
          "q-quote content must not expose any JDBC bind parameter",
          0, ps.getParameterMetaData().getParameterCount());
      try (ResultSet rs = ps.executeQuery()) {
        Assert.assertTrue(rs.next());
        Assert.assertEquals(EXPECTED, rs.getString(1));
      }
    }
  }

  /**
   * Execute through {@link PreparedStatement} with {@code namedParam=true}.
   *
   * <p>With named-parameter parsing enabled, {@code :44} would normally be
   * treated as a named parameter placeholder.  Inside a q-quote literal it
   * must remain a literal sequence.
   */
  @Test
  public void testQQuoteViaPreparedStatementNamedParam() throws SQLException {
    conn.close();
    Properties props = new Properties();
    props.put("namedParam", "true");
    conn = TestUtil.openDB(props);

    try (PreparedStatement ps = conn.prepareStatement(Q_QUOTE_SQL)) {
      Assert.assertEquals(
          "q-quote content must not expose any named parameter",
          0, ps.getParameterMetaData().getParameterCount());
      try (ResultSet rs = ps.executeQuery()) {
        Assert.assertTrue(rs.next());
        Assert.assertEquals(EXPECTED, rs.getString(1));
      }
    }
  }

  // ------------------------------------------------------------------
  // Cases where the q-quote delimiter is the single quote itself: q''..''
  // Oracle accepts this form. The opening sequence is q'' (q + opening
  // quote + delimiter '), the closing sequence is '' (delimiter ' +
  // closing quote). A lone ' inside the body is literal as long as the
  // following char is not another '.
  //
  //   SELECT q''te:s't ? :44'' FROM dual
  //
  // expands to the literal:  te:s't ? :44
  // ------------------------------------------------------------------

  private static final String Q_QUOTE_SQUOTE_SQL =
      "SELECT q''te:s't ? :44'' FROM dual";

  private static final String EXPECTED_SQUOTE = "te:s't ? :44";

  /** Single-quote-delimited q-quote via {@link Statement}. */
  @Test
  public void testQQuoteSingleQuoteDelimViaStatement() throws SQLException {
    try (Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery(Q_QUOTE_SQUOTE_SQL)) {
      Assert.assertTrue("result set should have a row", rs.next());
      Assert.assertEquals(
          "q-quote (single-quote delim) literal content mismatch",
          EXPECTED_SQUOTE, rs.getString(1));
      Assert.assertFalse("only one row expected", rs.next());
    }
  }

  /** Single-quote-delimited q-quote via {@link PreparedStatement}. */
  @Test
  public void testQQuoteSingleQuoteDelimViaPreparedStatement() throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(Q_QUOTE_SQUOTE_SQL)) {
      Assert.assertEquals(
          "? inside q'' .. '' must not be exposed as a bind parameter",
          0, ps.getParameterMetaData().getParameterCount());
      try (ResultSet rs = ps.executeQuery()) {
        Assert.assertTrue(rs.next());
        Assert.assertEquals(EXPECTED_SQUOTE, rs.getString(1));
      }
    }
  }

  /** Single-quote-delimited q-quote via {@link PreparedStatement} with namedParam=true. */
  @Test
  public void testQQuoteSingleQuoteDelimViaPreparedStatementNamedParam() throws SQLException {
    conn.close();
    Properties props = new Properties();
    props.put("namedParam", "true");
    conn = TestUtil.openDB(props);

    try (PreparedStatement ps = conn.prepareStatement(Q_QUOTE_SQUOTE_SQL)) {
      Assert.assertEquals(
          ":44 inside q'' .. '' must not be exposed as a named parameter",
          0, ps.getParameterMetaData().getParameterCount());
      try (ResultSet rs = ps.executeQuery()) {
        Assert.assertTrue(rs.next());
        Assert.assertEquals(EXPECTED_SQUOTE, rs.getString(1));
      }
    }
  }
}
