/*
 * Test: DATE OUT parameters of anonymous PL/SQL blocks must not lose the century
 * when the session nls_date_format uses a two-digit year (DD-Mon-RR).
 *
 * Root cause (kernel + driver): with an unknown-typed bind parameter the server
 * creates an unknown PL/SQL OUT variable and coerces date->text via the session
 * nls_date_format inside pl_exec (CoerceViaIO), rendering '06-Aug-46' for
 * 1946-08-06; the RR pivot then infers 2046. The driver fix binds NULL with the
 * registered OUT type (Types.DATE -> timestamp OID) so the variable stays typed
 * and the value survives end-to-end.
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;

public class DoBlockDateOutParamRRTest {

  private final String suffix = Long.toHexString(System.nanoTime());
  private final String procName = "rr_dob_proc_" + suffix;

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    conn = TestUtil.openDB(props);
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("drop procedure if exists " + procName);
      stmt.execute("create or replace procedure " + procName + "(d out date) is "
          + "begin d := to_date('1946-08-06','YYYY-MM-DD'); end;");
      stmt.execute("alter session set nls_date_format = 'DD-Mon-RR'");
    }
  }

  @After
  public void tearDown() throws Exception {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("alter session set nls_date_format = 'YYYY-MM-DD HH24:MI:SS'");
      stmt.execute("drop procedure if exists " + procName);
    } finally {
      TestUtil.closeDB(conn);
    }
  }

  /**
   * Customer scenario (CSMS phi_search): registerOutParameter(DATE) + execute +
   * getDate under nls_date_format='DD-Mon-RR' must return the stored year 1946,
   * not the RR-pivoted 2046.
   */
  @Test
  public void testDateOutParamKeepsCenturyUnderRR() throws Exception {
    try (CallableStatement cs = conn.prepareCall("begin " + procName + "(?); end;")) {
      cs.registerOutParameter(1, Types.DATE);
      cs.execute();
      Date dob = cs.getDate(1);
      assertEquals("century must survive RR session format", 1946, dob.getYear() + 1900);
      assertEquals(8, dob.getMonth() + 1);
      assertEquals(6, dob.getDate());
    }
  }

  /**
   * Same scenario via Types.TIMESTAMP registration.
   */
  @Test
  public void testTimestampOutParamKeepsCenturyUnderRR() throws Exception {
    try (CallableStatement cs = conn.prepareCall("begin " + procName + "(?); end;")) {
      cs.registerOutParameter(1, Types.TIMESTAMP);
      cs.execute();
      java.sql.Timestamp ts = cs.getTimestamp(1);
      assertEquals(1946, ts.getYear() + 1900);
    }
  }
}
