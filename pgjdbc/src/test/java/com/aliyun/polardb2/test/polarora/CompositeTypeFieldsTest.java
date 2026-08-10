/*
 * Test: PGConnection.getCompositeTypeFields - composite type field metadata API.
 *
 * Background: the standard JDBC API (createStruct / Struct.getAttributes) carries
 * only positionally ordered values, so the driver cannot reorder fields by name.
 * This API exposes the database-side field definitions (attname/atttypid/attnum)
 * so application frameworks can combine them with Java reflection and map Java
 * bean fields to composite-type fields BY NAME, for both writes and reads.
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

import com.aliyun.polardb2.PGCompositeField;
import com.aliyun.polardb2.PGConnection;
import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Verifies {@link PGConnection#getCompositeTypeFields(String)}: ordered field
 * metadata, synonym resolution, name-based value reordering (framework scenario)
 * and negative cases.
 */
public class CompositeTypeFieldsTest {

  // Unique names: test cases run in parallel, sharing fixed names would race.
  private final String suffix = Long.toHexString(System.nanoTime());
  private final String typeName = "cmpfld_addr_" + suffix;
  private final String synonymName = "cmpfld_syn_" + suffix;

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    conn = TestUtil.openDB(props);

    try (Statement stmt = conn.createStatement()) {
      dropQuietly(stmt);

      // Composite type: DB field order is (zip_code, city, street) - deliberately
      // different from the Java "bean" field order (street, city, zip_code) used
      // in the name-based mapping test below.
      stmt.execute("CREATE TYPE " + typeName + " AS OBJECT ("
          + "zip_code VARCHAR2(10), "
          + "city VARCHAR2(50), "
          + "street VARCHAR2(100))");

      try {
        stmt.execute("CREATE SYNONYM " + synonymName + " FOR " + typeName);
      } catch (SQLException e) {
        assumeNoException("SYNONYM not supported on this server", e);
      }
    }
  }

  @After
  public void tearDown() throws Exception {
    try (Statement stmt = conn.createStatement()) {
      dropQuietly(stmt);
    } finally {
      TestUtil.closeDB(conn);
    }
  }

  private void dropQuietly(Statement stmt) {
    try {
      stmt.execute("DROP SYNONYM IF EXISTS " + synonymName);
    } catch (SQLException ignore) {
      // ignore
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS " + typeName);
    } catch (SQLException ignore) {
      // ignore
    }
  }

  @Test
  public void testFieldsReturnedInServerOrder() throws SQLException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    List<PGCompositeField> fields = pgConn.getCompositeTypeFields(typeName);

    assertNotNull("fields of an existing composite type must not be null", fields);
    assertEquals(3, fields.size());
    // attnum order = DB definition order, regardless of the caller's assumption
    assertEquals("zip_code", fields.get(0).getFieldName());
    assertEquals("city", fields.get(1).getFieldName());
    assertEquals("street", fields.get(2).getFieldName());
    assertEquals(1, fields.get(0).getFieldNumber());
    assertEquals(2, fields.get(1).getFieldNumber());
    assertEquals(3, fields.get(2).getFieldNumber());
    assertTrue("field type OID must be a valid OID", fields.get(0).getFieldTypeOid() > 0);
  }

  @Test
  public void testCachedResultIsStable() throws SQLException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    List<PGCompositeField> first = pgConn.getCompositeTypeFields(typeName);
    List<PGCompositeField> second = pgConn.getCompositeTypeFields(typeName);
    assertNotNull(first);
    assertNotNull(second);
    assertEquals(first.size(), second.size());
    for (int i = 0; i < first.size(); i++) {
      assertEquals(first.get(i).getFieldName(), second.get(i).getFieldName());
      assertEquals(first.get(i).getFieldNumber(), second.get(i).getFieldNumber());
      assertEquals(first.get(i).getFieldTypeOid(), second.get(i).getFieldTypeOid());
    }
  }

  @Test
  public void testSynonymIsResolved() throws SQLException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    List<PGCompositeField> bySynonym = pgConn.getCompositeTypeFields(synonymName);
    List<PGCompositeField> byTypeName = pgConn.getCompositeTypeFields(typeName);
    assertNotNull("synonym must resolve to the composite type", bySynonym);
    assertNotNull(byTypeName);
    assertEquals(byTypeName.size(), bySynonym.size());
    for (int i = 0; i < byTypeName.size(); i++) {
      assertEquals(byTypeName.get(i).getFieldName(), bySynonym.get(i).getFieldName());
    }
  }

  /**
   * The framework scenario: the Java bean declares fields in a DIFFERENT order
   * than the database type. Using the driver metadata the framework reorders
   * the values by field NAME before createStruct, so no silent data corruption
   * occurs. This test simulates that flow end to end.
   */
  @Test
  public void testNameBasedReorderingForCreateStruct() throws SQLException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);

    // Java bean side: values keyed by Java field name (deliberately NOT in DB order)
    Map<String, Object> javaBean = new HashMap<String, Object>();
    javaBean.put("street", "Nanjing East Rd");
    javaBean.put("city", "Shanghai");
    javaBean.put("zip_code", "200001");

    // Framework logic: DB field order from the driver + Java field names -> ordered array
    List<PGCompositeField> dbFields = pgConn.getCompositeTypeFields(typeName);
    assertNotNull(dbFields);
    Object[] reordered = new Object[dbFields.size()];
    for (int i = 0; i < dbFields.size(); i++) {
      assertTrue("Java bean must contain field " + dbFields.get(i).getFieldName(),
          javaBean.containsKey(dbFields.get(i).getFieldName()));
      reordered[i] = javaBean.get(dbFields.get(i).getFieldName());
    }

    // Write via the standard positional API - now safe because the order matches the schema
    Struct struct = conn.createStruct(typeName, reordered);

    // Read back: the positional values must come out in DB field order
    // (zip_code, city, street), i.e. no silent misalignment happened.
    Object[] readBack = struct.getAttributes();
    assertEquals(3, readBack.length);
    assertEquals("200001", readBack[0].toString());
    assertEquals("Shanghai", readBack[1].toString());
    assertEquals("Nanjing East Rd", readBack[2].toString());
  }

  @Test
  public void testUnknownTypeReturnsNull() throws SQLException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    assertNull("unknown type must yield null",
        pgConn.getCompositeTypeFields("cmpfld_no_such_type_xyz"));
  }

  @Test
  public void testNonCompositeTypeReturnsNull() throws SQLException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    // varchar is a scalar type, not composite: no fields
    assertNull("scalar type must yield null", pgConn.getCompositeTypeFields("varchar"));
  }

  @Test(expected = SQLException.class)
  public void testNullTypeNameThrows() throws SQLException {
    conn.unwrap(PGConnection.class).getCompositeTypeFields(null);
  }
}
