/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.test.jdbc2;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

@RunWith(Parameterized.class)
public class DateStyleTest extends BaseTest4 {

  @Parameterized.Parameter(0)
  public String dateStyle;

  @Parameterized.Parameters(name = "dateStyle={0}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {"iso, mdy"},
        {"ISO"},
        {"ISO,ymd"},
        {"PostgreSQL"}
    });
  }

  /**
   * POLAR: Driver no longer enforces DateStyle=ISO, so all valid server DateStyles should be
   * accepted without error.
   */
  @Test
  public void connect() throws SQLException {
    Statement st = con.createStatement();
    try {
      st.execute("set DateStyle='" + dateStyle + "'");
    } catch (SQLException e) {
      throw new IllegalStateException("Set DateStyle=" + dateStyle
          + " should be fine, however received " + e.getMessage(), e);
    } finally {
      TestUtil.closeQuietly(st);
    }
  }
}
