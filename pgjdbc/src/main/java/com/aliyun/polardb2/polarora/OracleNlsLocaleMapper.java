/*
 * Portions Copyright (c) 2024, Alibaba Group Holding Limited
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.polarora;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a Java {@link Locale} to Oracle {@code NLS_LANGUAGE}/{@code NLS_TERRITORY} values.
 *
 * <p>This reproduces the observable behavior of the Oracle JDBC Thin driver, which reads the JVM
 * FORMAT {@link Locale} ({@code Locale.getDefault(Locale.Category.FORMAT)}) when a physical
 * connection is created and initializes the session {@code NLS_LANGUAGE}/{@code NLS_TERRITORY}
 * accordingly. The mapping table below is implemented independently from public Locale standards,
 * Oracle documentation and black-box testing; it does not copy any Oracle proprietary code.</p>
 *
 * <p>Confirmed matrix (JVM FORMAT Locale -&gt; NLS_LANGUAGE / NLS_TERRITORY):</p>
 * <pre>
 *   en_US -&gt; AMERICAN            / AMERICA
 *   en_GB -&gt; AMERICAN            / UNITED KINGDOM
 *   zh_CN -&gt; SIMPLIFIED CHINESE  / CHINA
 *   zh_TW -&gt; TRADITIONAL CHINESE / TAIWAN
 *   ja_JP -&gt; JAPANESE            / JAPAN
 *   de_DE -&gt; GERMAN              / GERMANY
 *   fr_CA -&gt; CANADIAN FRENCH     / CANADA
 *   pt_BR -&gt; BRAZILIAN PORTUGUESE/ BRAZIL
 *   es_ES -&gt; SPANISH             / SPAIN
 * </pre>
 *
 * <p>Note: Oracle JDBC maps {@code en_GB} language to {@code AMERICAN} (not {@code ENGLISH}), which
 * differs from the OCI {@code ENGLISH/UNITED KINGDOM/GB} behavior.</p>
 *
 * <p>Unknown languages/countries fall back to Oracle's ultimate default
 * {@code AMERICAN}/{@code AMERICA}. The tables can be extended without changing the callers.</p>
 */
public final class OracleNlsLocaleMapper {

  /** Oracle ultimate default language. */
  public static final String DEFAULT_LANGUAGE = "AMERICAN";
  /** Oracle ultimate default territory. */
  public static final String DEFAULT_TERRITORY = "AMERICA";

  /** Language overrides that depend on the full {@code language_COUNTRY} locale. */
  private static final Map<String, String> LANGUAGE_BY_LOCALE = new HashMap<>();
  /** Language mapping keyed by the (lower-cased) ISO language code. */
  private static final Map<String, String> LANGUAGE_BY_LANG = new HashMap<>();
  /** Territory mapping keyed by the (upper-cased) ISO country code. */
  private static final Map<String, String> TERRITORY_BY_COUNTRY = new HashMap<>();

  static {
    // Full-locale overrides: the NLS_LANGUAGE depends on the country as well.
    LANGUAGE_BY_LOCALE.put("zh_TW", "TRADITIONAL CHINESE");
    LANGUAGE_BY_LOCALE.put("zh_HK", "TRADITIONAL CHINESE");
    LANGUAGE_BY_LOCALE.put("zh_MO", "TRADITIONAL CHINESE");
    LANGUAGE_BY_LOCALE.put("fr_CA", "CANADIAN FRENCH");
    LANGUAGE_BY_LOCALE.put("pt_BR", "BRAZILIAN PORTUGUESE");

    // Mapping by ISO language code. Oracle JDBC maps every English variant to AMERICAN.
    LANGUAGE_BY_LANG.put("en", "AMERICAN");
    LANGUAGE_BY_LANG.put("zh", "SIMPLIFIED CHINESE");
    LANGUAGE_BY_LANG.put("ja", "JAPANESE");
    LANGUAGE_BY_LANG.put("ko", "KOREAN");
    LANGUAGE_BY_LANG.put("de", "GERMAN");
    LANGUAGE_BY_LANG.put("fr", "FRENCH");
    LANGUAGE_BY_LANG.put("es", "SPANISH");
    LANGUAGE_BY_LANG.put("pt", "PORTUGUESE");
    LANGUAGE_BY_LANG.put("it", "ITALIAN");
    LANGUAGE_BY_LANG.put("ru", "RUSSIAN");
    LANGUAGE_BY_LANG.put("nl", "DUTCH");
    LANGUAGE_BY_LANG.put("pl", "POLISH");
    LANGUAGE_BY_LANG.put("tr", "TURKISH");
    LANGUAGE_BY_LANG.put("ar", "ARABIC");
    LANGUAGE_BY_LANG.put("th", "THAI");
    LANGUAGE_BY_LANG.put("sv", "SWEDISH");
    LANGUAGE_BY_LANG.put("da", "DANISH");
    LANGUAGE_BY_LANG.put("fi", "FINNISH");
    LANGUAGE_BY_LANG.put("no", "NORWEGIAN");
    LANGUAGE_BY_LANG.put("cs", "CZECH");
    LANGUAGE_BY_LANG.put("hu", "HUNGARIAN");
    LANGUAGE_BY_LANG.put("el", "GREEK");
    LANGUAGE_BY_LANG.put("he", "HEBREW");
    LANGUAGE_BY_LANG.put("vi", "VIETNAMESE");

    // Mapping by ISO country code.
    TERRITORY_BY_COUNTRY.put("US", "AMERICA");
    TERRITORY_BY_COUNTRY.put("GB", "UNITED KINGDOM");
    TERRITORY_BY_COUNTRY.put("CN", "CHINA");
    TERRITORY_BY_COUNTRY.put("TW", "TAIWAN");
    TERRITORY_BY_COUNTRY.put("HK", "HONG KONG");
    TERRITORY_BY_COUNTRY.put("MO", "MACAO");
    TERRITORY_BY_COUNTRY.put("JP", "JAPAN");
    TERRITORY_BY_COUNTRY.put("KR", "KOREA");
    TERRITORY_BY_COUNTRY.put("DE", "GERMANY");
    TERRITORY_BY_COUNTRY.put("FR", "FRANCE");
    TERRITORY_BY_COUNTRY.put("CA", "CANADA");
    TERRITORY_BY_COUNTRY.put("BR", "BRAZIL");
    TERRITORY_BY_COUNTRY.put("ES", "SPAIN");
    TERRITORY_BY_COUNTRY.put("IT", "ITALY");
    TERRITORY_BY_COUNTRY.put("RU", "RUSSIA");
    TERRITORY_BY_COUNTRY.put("NL", "THE NETHERLANDS");
    TERRITORY_BY_COUNTRY.put("PL", "POLAND");
    TERRITORY_BY_COUNTRY.put("TR", "TURKEY");
    TERRITORY_BY_COUNTRY.put("TH", "THAILAND");
    TERRITORY_BY_COUNTRY.put("SE", "SWEDEN");
    TERRITORY_BY_COUNTRY.put("DK", "DENMARK");
    TERRITORY_BY_COUNTRY.put("FI", "FINLAND");
    TERRITORY_BY_COUNTRY.put("NO", "NORWAY");
    TERRITORY_BY_COUNTRY.put("CZ", "CZECH REPUBLIC");
    TERRITORY_BY_COUNTRY.put("HU", "HUNGARY");
    TERRITORY_BY_COUNTRY.put("GR", "GREECE");
    TERRITORY_BY_COUNTRY.put("IL", "ISRAEL");
    TERRITORY_BY_COUNTRY.put("VN", "VIETNAM");
    TERRITORY_BY_COUNTRY.put("AU", "AUSTRALIA");
    TERRITORY_BY_COUNTRY.put("MX", "MEXICO");
    TERRITORY_BY_COUNTRY.put("IN", "INDIA");
    TERRITORY_BY_COUNTRY.put("SG", "SINGAPORE");
    TERRITORY_BY_COUNTRY.put("CH", "SWITZERLAND");
    TERRITORY_BY_COUNTRY.put("AT", "AUSTRIA");
    TERRITORY_BY_COUNTRY.put("BE", "BELGIUM");
    TERRITORY_BY_COUNTRY.put("PT", "PORTUGAL");
    TERRITORY_BY_COUNTRY.put("IE", "IRELAND");
    TERRITORY_BY_COUNTRY.put("NZ", "NEW ZEALAND");
  }

  private OracleNlsLocaleMapper() {
  }

  /**
   * Maps the given locale to an Oracle {@code NLS_LANGUAGE} value.
   *
   * @param locale JVM FORMAT locale (may be {@code null})
   * @return the mapped Oracle language, never {@code null} ({@link #DEFAULT_LANGUAGE} fallback)
   */
  public static String getNlsLanguage(@Nullable Locale locale) {
    if (locale == null) {
      return DEFAULT_LANGUAGE;
    }
    String lang = locale.getLanguage();
    if (lang == null || lang.isEmpty()) {
      return DEFAULT_LANGUAGE;
    }
    lang = lang.toLowerCase(Locale.ROOT);
    String country = locale.getCountry();
    country = country == null ? "" : country.toUpperCase(Locale.ROOT);

    String byLocale = LANGUAGE_BY_LOCALE.get(lang + "_" + country);
    if (byLocale != null) {
      return byLocale;
    }
    String byLang = LANGUAGE_BY_LANG.get(lang);
    return byLang != null ? byLang : DEFAULT_LANGUAGE;
  }

  /**
   * Maps the given locale to an Oracle {@code NLS_TERRITORY} value.
   *
   * @param locale JVM FORMAT locale (may be {@code null})
   * @return the mapped Oracle territory, never {@code null} ({@link #DEFAULT_TERRITORY} fallback)
   */
  public static String getNlsTerritory(@Nullable Locale locale) {
    if (locale == null) {
      return DEFAULT_TERRITORY;
    }
    String country = locale.getCountry();
    if (country == null || country.isEmpty()) {
      return DEFAULT_TERRITORY;
    }
    String territory = TERRITORY_BY_COUNTRY.get(country.toUpperCase(Locale.ROOT));
    return territory != null ? territory : DEFAULT_TERRITORY;
  }
}
