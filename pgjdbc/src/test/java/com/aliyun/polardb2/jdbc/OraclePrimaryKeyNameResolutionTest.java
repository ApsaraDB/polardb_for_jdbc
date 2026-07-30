/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * White-box unit tests for {@link PgDatabaseMetaData#resolveOracleName(String, List)}.
 *
 * <p>These cover the Oracle-style case-folding rules used by {@code getPrimaryKeys} when a
 * customer keeps their original (Oracle) upper-case habit while DTS has migrated the objects
 * to lower-case. The resolver never touches the server, so every extreme case is exercised
 * deterministically here.
 */
class OraclePrimaryKeyNameResolutionTest {

  /**
   * Customer scenario: an Oracle upper-case name resolves to the lower-case name that DTS
   * stored, so {@code getPrimaryKeys("XPENG_PROD", "HLS_...")} keeps working.
   */
  @Test
  void upperCaseInputResolvesToStoredLowerCase() {
    assertEquals("hls_fin_calculator_hd",
        PgDatabaseMetaData.resolveOracleName("HLS_FIN_CALCULATOR_HD",
            Collections.singletonList("hls_fin_calculator_hd")));
  }

  /** Only a lower-case object exists: both pg-style and Oracle-style input must find it. */
  @Test
  void onlyLowerCaseObject() {
    List<String> stored = Collections.singletonList("abc");
    assertEquals("abc", PgDatabaseMetaData.resolveOracleName("abc", stored));
    assertEquals("abc", PgDatabaseMetaData.resolveOracleName("ABC", stored));
    assertEquals("abc", PgDatabaseMetaData.resolveOracleName("Abc", stored));
  }

  /** Only an upper-case object exists: both pg-style and Oracle-style input must find it. */
  @Test
  void onlyUpperCaseObject() {
    List<String> stored = Collections.singletonList("ABC");
    assertEquals("ABC", PgDatabaseMetaData.resolveOracleName("ABC", stored));
    assertEquals("ABC", PgDatabaseMetaData.resolveOracleName("abc", stored));
    assertEquals("ABC", PgDatabaseMetaData.resolveOracleName("aBc", stored));
  }

  /**
   * Both {@code ABC} and {@code abc} exist: an exact match wins so exactly one table is
   * returned - never both.
   */
  @Test
  void bothCasesExistExactMatchWins() {
    List<String> stored = Arrays.asList("abc", "ABC");
    assertEquals("ABC", PgDatabaseMetaData.resolveOracleName("ABC", stored));
    assertEquals("abc", PgDatabaseMetaData.resolveOracleName("abc", stored));
  }

  /**
   * Both {@code ABC} and {@code abc} exist and the input matches neither exactly: the input is
   * returned unchanged so the downstream lookup matches strictly and returns nothing, rather
   * than ambiguously returning two tables.
   */
  @Test
  void bothCasesExistNoExactMatchIsStrict() {
    List<String> stored = Arrays.asList("abc", "ABC");
    assertEquals("Abc", PgDatabaseMetaData.resolveOracleName("Abc", stored));
  }

  /** A mixed-case object is matched strictly on an exact hit. */
  @Test
  void mixedCaseObjectExactMatch() {
    List<String> stored = Collections.singletonList("MixCase");
    assertEquals("MixCase", PgDatabaseMetaData.resolveOracleName("MixCase", stored));
  }

  /** A lone mixed-case object is still reachable when it is the only case-insensitive match. */
  @Test
  void mixedCaseObjectUniqueFallback() {
    List<String> stored = Collections.singletonList("MixCase");
    assertEquals("MixCase", PgDatabaseMetaData.resolveOracleName("MIXCASE", stored));
    assertEquals("MixCase", PgDatabaseMetaData.resolveOracleName("mixcase", stored));
  }

  /**
   * Several mixed-case variants exist and none matches exactly: stay strict (return the input)
   * so no arbitrary table is picked.
   */
  @Test
  void multipleMixedCaseVariantsNoExactMatchIsStrict() {
    List<String> stored = Arrays.asList("MixCase", "mixcase", "MIXCASE");
    assertEquals("MiXcAsE", PgDatabaseMetaData.resolveOracleName("MiXcAsE", stored));
  }

  /** No candidate at all: the input is returned unchanged (lookup will find nothing). */
  @Test
  void noCandidateReturnsInput() {
    assertEquals("nope",
        PgDatabaseMetaData.resolveOracleName("nope", Collections.<String>emptyList()));
  }
}
