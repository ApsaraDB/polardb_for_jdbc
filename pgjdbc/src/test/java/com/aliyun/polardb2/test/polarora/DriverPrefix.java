package com.aliyun.polardb2.test.polarora;

import com.aliyun.polardb2.jdbc.PgConnection;
import com.aliyun.polardb2.test.TestUtil;

import org.junit.Assert;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;

public class DriverPrefix {
  private Connection conn;

  @Test
  public void testGetURL1() throws Exception {
    conn = DriverManager.getConnection(String.format("jdbc:polardb://%s:%s/%s?" + "user=%s"
        + "&password=%s", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(),
        TestUtil.getUser(), TestUtil.getPassword()));

    Assert.assertNotNull(conn);
    Assert.assertTrue(((PgConnection) conn).getURL().startsWith("jdbc:polardb://"));
  }

  @Test
  public void testGetURL2() throws Exception {
    try {
      conn = DriverManager.getConnection(String.format("jdbc:oracle://%s:%s/%s?" + "user=%s"
          + "&password=%s", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(),
          TestUtil.getUser(), TestUtil.getPassword()));
    } catch (Exception exp) {
      System.out.println(exp.getMessage().toString());
      Assert.assertTrue(exp.getMessage().startsWith("No suitable driver found for jdbc:oracle://"));
    }
  }

  @Test
  public void testGetURL3() throws Exception {
    try {
      conn = DriverManager.getConnection(String.format("jdbc:oracle:thin://%s:%s/%s?" + "user=%s"
          + "&password=%s", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(),
          TestUtil.getUser(), TestUtil.getPassword()));
    } catch (Exception exp) {
      System.out.println(exp.getMessage().toString());
      Assert.assertTrue(exp.getMessage().startsWith("No suitable driver found for jdbc:oracle:thin://"));
    }
  }

  @Test
  public void testGetURL4() throws Exception {
    try {
      conn = DriverManager.getConnection(String.format("jdbc:postgresql://%s:%s/%s?" + "user=%s"
              + "&password=%s", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(),
          TestUtil.getUser(), TestUtil.getPassword()));
      Assert.fail("------------");
    } catch (Exception exp) {
      System.out.println(exp.getMessage().toString());
      Assert.assertTrue(exp.getMessage().startsWith("No suitable driver found for jdbc:postgresql://"));
    }
  }

  @Test
  public void testGetURL5() throws Exception  {
    try {
      conn = DriverManager.getConnection(String.format("jdbc:postgresql://%s:%s/%s?" + "user=%s"
              + "&password=%s&forceDriverType=pg", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(),
          TestUtil.getUser(), TestUtil.getPassword()));
      Assert.fail("------------");
    } catch (Exception exp) {
      System.out.println(exp.getMessage().toString());
      Assert.assertTrue(exp.getMessage().startsWith("No suitable driver found for jdbc:postgresql://"));
    }
  }

  @Test
  public void testGetURL6() throws Exception {
    conn = DriverManager.getConnection(String.format("jdbc:polardb://%s:%s/%s?" + "user=%s"
        + "&password=%s&forceDriverType=ora", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(),
        TestUtil.getUser(), TestUtil.getPassword()));

    Assert.assertNotNull(conn);
    Assert.assertTrue(((PgConnection) conn).getURL().startsWith("jdbc:polardb://"));
  }

  @Test
  public void testGetURL7() {
    try {
      conn = DriverManager.getConnection(String.format("jdbc:postgresql://%s:%s/%s?" + "user=%s"
              + "&password=%s&forceDriverType=ora11", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(),
          TestUtil.getUser(), TestUtil.getPassword()));
      Assert.fail("------------");
    } catch (Exception exp) {
      System.out.println(exp.getMessage().toString());
      Assert.assertTrue(exp.getMessage().startsWith("No suitable driver found for jdbc:postgresql://"));
    }
  }

  @Test
  public void testGetURL8() throws Exception {
    conn = DriverManager.getConnection(String.format("jdbc:polardb://%s:%s/%s?" + "user=%s"
        + "&password=%s&forceDriverType=ora14", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(),
        TestUtil.getUser(), TestUtil.getPassword()));

    Assert.assertNotNull(conn);
    Assert.assertTrue(((PgConnection) conn).getURL().startsWith("jdbc:polardb://"));
  }

  @Test
  public void testGetURL10() {
    try {
      conn = DriverManager.getConnection(String.format("jdbc:polardb1://%s:%s/%s?" + "user=%s"
              + "&password=%s", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(),
          TestUtil.getUser(), TestUtil.getPassword()));
      Assert.fail("------------");
    } catch (Exception exp) {
      System.out.println(exp.getMessage().toString());
      Assert.assertTrue(exp.getMessage().startsWith("No suitable driver found for jdbc:polardb1://"));
    }
  }

  @Test
  public void testGetURL11() throws Exception {
    conn = DriverManager.getConnection(String.format("jdbc:postgresql://%s:%s/%s?" + "user=%s"
          + "&password=%s&forceDriverType=True", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(),
      TestUtil.getUser(), TestUtil.getPassword()));

    Assert.assertNotNull(conn);
    Assert.assertTrue(((PgConnection) conn).getURL().startsWith("jdbc:postgresql://"));
  }
}
