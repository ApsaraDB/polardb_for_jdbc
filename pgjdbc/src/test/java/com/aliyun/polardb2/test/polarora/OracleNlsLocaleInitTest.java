/*
 * Portions Copyright (c) 2024, Alibaba Group Holding Limited
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.PGProperty;
import com.aliyun.polardb2.polarora.OracleNlsLocaleMapper;
import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;

/**
 * Tests for the Oracle JDBC Thin compatible NLS locale initialization
 * (requirement: 84634629 - 兼容 Oracle JDBC Locale 初始化 USERENV 语言默认值).
 *
 * <p>The pure mapping tests always run. The end-to-end tests connect to the configured PolarDB
 * instance; they are automatically skipped when the server does not expose the
 * {@code nls_language}/{@code nls_territory} GUCs (older kernels).</p>
 */
public class OracleNlsLocaleInitTest {

  private Locale savedFormatLocale;

  @Before
  public void setUp() {
    savedFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
  }

  @After
  public void tearDown() {
    // Always restore the JVM FORMAT locale so other tests are not affected.
    Locale.setDefault(Locale.Category.FORMAT, savedFormatLocale);
  }

  // ---------------------------------------------------------------------------
  // Pure mapping tests (no database required)
  // ---------------------------------------------------------------------------

  @Test
  public void testConfirmedMappingMatrix() {
    assertMapping("en", "US", "AMERICAN", "AMERICA");
    // Oracle JDBC maps en_GB language to AMERICAN (not ENGLISH), territory UNITED KINGDOM.
    assertMapping("en", "GB", "AMERICAN", "UNITED KINGDOM");
    assertMapping("zh", "CN", "SIMPLIFIED CHINESE", "CHINA");
    assertMapping("zh", "TW", "TRADITIONAL CHINESE", "TAIWAN");
    assertMapping("ja", "JP", "JAPANESE", "JAPAN");
    assertMapping("de", "DE", "GERMAN", "GERMANY");
    assertMapping("fr", "CA", "CANADIAN FRENCH", "CANADA");
    assertMapping("pt", "BR", "BRAZILIAN PORTUGUESE", "BRAZIL");
    assertMapping("es", "ES", "SPANISH", "SPAIN");
  }

  @Test
  public void testUnknownLocaleFallsBackToOracleDefault() {
    // Unknown language/country falls back to Oracle's ultimate default AMERICAN/AMERICA.
    assertMapping("xx", "YY", "AMERICAN", "AMERICA");
    assertEquals("AMERICAN", OracleNlsLocaleMapper.getNlsLanguage(null));
    assertEquals("AMERICA", OracleNlsLocaleMapper.getNlsTerritory(null));
    // No country -> default territory.
    assertEquals("AMERICA", OracleNlsLocaleMapper.getNlsTerritory(new Locale("de")));
  }

  private static void assertMapping(String lang, String country,
      String expectedLanguage, String expectedTerritory) {
    Locale locale = new Locale(lang, country);
    assertEquals("NLS_LANGUAGE for " + locale,
        expectedLanguage, OracleNlsLocaleMapper.getNlsLanguage(locale));
    assertEquals("NLS_TERRITORY for " + locale,
        expectedTerritory, OracleNlsLocaleMapper.getNlsTerritory(locale));
  }

  // ---------------------------------------------------------------------------
  // End-to-end tests against a live PolarDB instance
  // ---------------------------------------------------------------------------

  @Test
  public void testDisabledByDefault() throws Exception {
    assumeNlsLocaleGucsSupported();
    // The feature is opt-in: without nlsLocaleInit=true a zh_CN FORMAT locale must NOT change the
    // session, which keeps the instance default (verified against the same connection with the
    // feature explicitly disabled).
    Locale.setDefault(Locale.Category.FORMAT, new Locale("en", "US"));
    String instanceLanguage;
    Connection baseline = TestUtil.openDB(new Properties());
    try {
      instanceLanguage = showParam(baseline, "nls_language");
    } finally {
      TestUtil.closeDB(baseline);
    }

    Locale.setDefault(Locale.Category.FORMAT, new Locale("zh", "CN"));
    Connection conn = TestUtil.openDB(new Properties());
    try {
      assertEquals(instanceLanguage, showParam(conn, "nls_language"));
    } finally {
      TestUtil.closeDB(conn);
    }
  }

  @Test
  public void testEnabledLocaleInitializesSession() throws Exception {
    assumeNlsLocaleGucsSupported();
    Locale.setDefault(Locale.Category.FORMAT, new Locale("zh", "CN"));
    Connection conn = TestUtil.openDB(enabled());
    try {
      assertEquals("SIMPLIFIED CHINESE", showParam(conn, "nls_language"));
      assertEquals("CHINA", showParam(conn, "nls_territory"));
    } finally {
      TestUtil.closeDB(conn);
    }
  }

  @Test
  public void testLocaleMatrixOverPhysicalConnections() throws Exception {
    assumeNlsLocaleGucsSupported();
    assertSessionForLocale(new Locale("en", "US"), "AMERICAN", "AMERICA");
    assertSessionForLocale(new Locale("ja", "JP"), "JAPANESE", "JAPAN");
    assertSessionForLocale(new Locale("de", "DE"), "GERMAN", "GERMANY");
    assertSessionForLocale(new Locale("fr", "CA"), "CANADIAN FRENCH", "CANADA");
    assertSessionForLocale(new Locale("zh", "TW"), "TRADITIONAL CHINESE", "TAIWAN");
  }

  @Test
  public void testFormatLocaleWinsOverDisplayLocale() throws Exception {
    assumeNlsLocaleGucsSupported();
    // DISPLAY is en_US while FORMAT is zh_CN: the driver must only use FORMAT.
    Locale.setDefault(Locale.Category.DISPLAY, new Locale("en", "US"));
    Locale.setDefault(Locale.Category.FORMAT, new Locale("zh", "CN"));
    Connection conn = TestUtil.openDB(enabled());
    try {
      assertEquals("SIMPLIFIED CHINESE", showParam(conn, "nls_language"));
      assertEquals("CHINA", showParam(conn, "nls_territory"));
    } finally {
      TestUtil.closeDB(conn);
    }
  }

  @Test
  public void testRuntimeFormatChangeAffectsNewPhysicalConnections() throws Exception {
    assumeNlsLocaleGucsSupported();

    Locale.setDefault(Locale.Category.FORMAT, new Locale("de", "DE"));
    Connection first = TestUtil.openDB(enabled());
    try {
      assertEquals("GERMAN", showParam(first, "nls_language"));
      assertEquals("GERMANY", showParam(first, "nls_territory"));

      // Change FORMAT at runtime: the already-open connection must not change.
      Locale.setDefault(Locale.Category.FORMAT, new Locale("ja", "JP"));
      assertEquals("GERMAN", showParam(first, "nls_language"));

      // A new physical connection must pick up the new FORMAT locale.
      Connection second = TestUtil.openDB(enabled());
      try {
        assertEquals("JAPANESE", showParam(second, "nls_language"));
        assertEquals("JAPAN", showParam(second, "nls_territory"));
      } finally {
        TestUtil.closeDB(second);
      }
    } finally {
      TestUtil.closeDB(first);
    }
  }

  @Test
  public void testAssumeMinServerVersionDoesNotSkipNlsInit() throws Exception {
    assumeNlsLocaleGucsSupported();
    // With assumeMinServerVersion >= 9.0, runInitialQueries() early-returns; the NLS init must
    // still run (requirement: not skipped by the assumeMinServerVersion early-return path).
    Locale.setDefault(Locale.Category.FORMAT, new Locale("zh", "CN"));
    Properties props = enabled();
    PGProperty.ASSUME_MIN_SERVER_VERSION.set(props, "9.4");
    Connection conn = TestUtil.openDB(props);
    try {
      assertEquals("SIMPLIFIED CHINESE", showParam(conn, "nls_language"));
      assertEquals("CHINA", showParam(conn, "nls_territory"));
    } finally {
      TestUtil.closeDB(conn);
    }
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  /** Properties with the opt-in feature explicitly enabled. */
  private static Properties enabled() {
    Properties props = new Properties();
    PGProperty.NLS_LOCALE_INIT.set(props, "true");
    return props;
  }

  private void assertSessionForLocale(Locale locale, String expectedLanguage,
      String expectedTerritory) throws SQLException {
    Locale.setDefault(Locale.Category.FORMAT, locale);
    Connection conn = TestUtil.openDB(enabled());
    try {
      assertEquals("nls_language for " + locale, expectedLanguage,
          showParam(conn, "nls_language"));
      assertEquals("nls_territory for " + locale, expectedTerritory,
          showParam(conn, "nls_territory"));
    } finally {
      TestUtil.closeDB(conn);
    }
  }

  private static String showParam(Connection conn, String name) throws SQLException {
    Statement st = conn.createStatement();
    try {
      ResultSet rs = st.executeQuery("SHOW " + name);
      assertTrue("expected a row from SHOW " + name, rs.next());
      return rs.getString(1);
    } finally {
      st.close();
    }
  }

  private void assumeNlsLocaleGucsSupported() throws SQLException {
    // The feature is off by default, so a plain connection performs no NLS init.
    Connection conn = TestUtil.openDB(new Properties());
    try {
      Statement st = conn.createStatement();
      try {
        ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_catalog.pg_settings "
            + "WHERE name IN ('nls_language', 'nls_territory')");
        rs.next();
        Assume.assumeTrue("server does not expose nls_language/nls_territory GUCs",
            rs.getInt(1) >= 2);
      } finally {
        st.close();
      }
    } finally {
      TestUtil.closeDB(conn);
    }
  }
}
