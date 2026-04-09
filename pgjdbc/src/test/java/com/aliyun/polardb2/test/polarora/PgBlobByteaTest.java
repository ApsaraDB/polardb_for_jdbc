/*
 * Portions Copyright (c) 2023, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import com.aliyun.polardb2.jdbc.PgBlobBytea;
import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;

public class PgBlobByteaTest {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("blobAsBytea", "true");
    conn = TestUtil.openDB(props);
    TestUtil.createTable(conn, "blob_test", "id int,name blob");
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropTable(conn, "blob_test");
  }

  @Test
  public void testBlobBytea1() throws Exception {
    String str = "abcd1234";
    byte[] bytes = str.getBytes();
    Blob blobin = new PgBlobBytea(bytes);

    Assert.assertEquals("abcd1234", new String(bytes));

    PreparedStatement pstmt1 = conn.prepareStatement("INSERT INTO blob_test(id, name) VALUES (?, "
        + "?)");
    pstmt1.setInt(1, 1);
    pstmt1.setObject(2, blobin);
    pstmt1.execute();

    PreparedStatement pstmt2 = conn.prepareStatement("select * from blob_test");
    ResultSet resultSet = pstmt2.executeQuery();
    Assert.assertTrue(resultSet.next());
    Blob blob = (Blob) resultSet.getObject(2);
    bytes = resultSet.getBytes(2);

    // test length()
    Assert.assertEquals(blob.length(), bytes.length);

    // test getBytes()
    Assert.assertEquals(str, new String(blob.getBytes(1, (int) blob.length())));
    Assert.assertEquals("1234", new String(blob.getBytes(5, (int) blob.length() - 4)));
    Assert.assertEquals("12", new String(blob.getBytes(5, (int) blob.length() - 6)));

    // test position()
    Assert.assertEquals(1, blob.position("abcd12".getBytes(), 1));
  }

  /**
   * Test that getString() on a bytea/blob column returns Oracle-style uppercase hex (no \x prefix)
   * when blobUpperHex=true (default).
   */
  @Test
  public void testBlobGetStringOracleHex() throws Exception {
    // Insert known binary data: bytes 0xDE, 0xAD, 0xBE, 0xEF
    byte[] data = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
    Blob blobin = new PgBlobBytea(data);

    PreparedStatement pstmt1 = conn.prepareStatement("INSERT INTO blob_test(id, name) VALUES (?, ?)");
    pstmt1.setInt(1, 2);
    pstmt1.setObject(2, blobin);
    pstmt1.execute();

    // blobUpperHex=true (default): should return Oracle-style "DEADBEEF"
    PreparedStatement pstmt2 = conn.prepareStatement("SELECT name FROM blob_test WHERE id = 2");
    ResultSet rs = pstmt2.executeQuery();
    Assert.assertTrue(rs.next());
    String oracleHex = rs.getString(1);
    Assert.assertEquals("getString() should return Oracle uppercase hex without \\x prefix",
        "DEADBEEF", oracleHex);
    rs.close();
  }

  /**
   * Test that getString() on a bytea/blob column returns PG-style lowercase hex (\xdeadbeef)
   * when blobUpperHex=false.
   */
  @Test
  public void testBlobGetStringPgHex() throws Exception {
    Properties props = new Properties();
    props.put("blobAsBytea", "true");
    props.put("blobUpperHex", "false");
    try (Connection pgConn = TestUtil.openDB(props)) {
      byte[] data = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
      Blob blobin = new PgBlobBytea(data);

      PreparedStatement pstmt1 = pgConn.prepareStatement(
          "INSERT INTO blob_test(id, name) VALUES (?, ?)");
      pstmt1.setInt(1, 3);
      pstmt1.setObject(2, blobin);
      pstmt1.execute();

      PreparedStatement pstmt2 = pgConn.prepareStatement(
          "SELECT name FROM blob_test WHERE id = 3");
      ResultSet rs = pstmt2.executeQuery();
      Assert.assertTrue(rs.next());
      String pgHex = rs.getString(1);
      Assert.assertEquals("getString() should return PG lowercase hex with \\x prefix",
          "\\xdeadbeef", pgHex);
      rs.close();
    }
  }

}
