/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Struct;
import java.sql.Types;
import java.util.Properties;

/**
 * Test for custom type array with actor_name and actor_name_array.
 * SQL Definition (DO NOT MODIFY):
 * create type actor_name as object (name varchar(20));
 * CREATE OR REPLACE TYPE actor_name_array AS VARRAY(100) OF actor_name;
 */
public class ActorNameArrayTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("boolAsInt", "true");
    conn = TestUtil.openDB(props);

    // Create custom types - using user's SQL definition
    TestUtil.execute(conn,
        "create type actor_name as object (name varchar(20))");
    TestUtil.execute(conn,
        "CREATE OR REPLACE TYPE actor_name_array AS VARRAY(100) OF actor_name");

    // Create a test function that accepts actor_name_array
    TestUtil.execute(conn,
        "CREATE OR REPLACE FUNCTION test_actor_array(names actor_name_array) RETURN INTEGER AS\n"
            + "BEGIN\n"
            + "  RETURN names.count;\n"
            + "END;");
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.execute(conn, "DROP FUNCTION IF EXISTS test_actor_array");
    TestUtil.execute(conn, "DROP TYPE IF EXISTS actor_name_array");
    TestUtil.execute(conn, "DROP TYPE IF EXISTS actor_name");
    conn.close();
  }

  /**
   * Test creating and passing actor_name_array to a stored function.
   * For PolarDB/PostgreSQL, when using createArrayOf with custom types,
   * we need to use the element type name (e.g., "actor_name") not the array type name.
   * The driver will look for "actor_name[]" in pg_type.
   * However, for VARRAY types created with "CREATE TYPE ... AS VARRAY",
   * the type name in pg_type is the array type name itself (e.g., "actor_name_array").
   */
  @Test
  public void testActorNameArray() throws Exception {
    // Test data
    Object[][] attributesOfArray = new Object[][] {
        {"Actor1"},
        {"Actor2"}
    };

    String structTypeName = "ACTOR_NAME";
    String arrayTypeName = "ACTOR_NAME_ARRAY";

    // Create Struct array
    Struct[] structArr = new Struct[attributesOfArray.length];
    for (int i = 0; i < attributesOfArray.length; i++) {
      structArr[i] = conn.createStruct(structTypeName, attributesOfArray[i]);
    }

    // Use PGConnection.createArrayOf with VARRAY type name
    // After driver fix, this now works for VARRAY types like actor_name_array
    com.aliyun.polardb2.PGConnection pgConn = conn.unwrap(com.aliyun.polardb2.PGConnection.class);
    Array arr = pgConn.createArrayOf(arrayTypeName, structArr);
    assertNotNull("Array should not be null", arr);

    // Call the function
    try (CallableStatement cs = conn.prepareCall("{ ? = call test_actor_array(?) }")) {
      cs.registerOutParameter(1, Types.INTEGER);
      cs.setArray(2, arr);
      cs.execute();

      int result = cs.getInt(1);
      assertEquals("Should return 2 actors", 2, result);
    }
  }

  /**
   * Alternative approach: Use setObject with the Struct array directly.
   * This bypasses the createArrayOf limitation.
   */
  public void testActorNameArrayAlternative() throws Exception {
    // Test data
    Object[][] attributesOfArray = new Object[][] {
        {"Actor1"},
        {"Actor2"}
    };

    String structTypeName = "ACTOR_NAME";

    // Create Struct array
    Struct[] structArr = new Struct[attributesOfArray.length];
    for (int i = 0; i < attributesOfArray.length; i++) {
      structArr[i] = conn.createStruct(structTypeName, attributesOfArray[i]);
    }

    // Try calling the function with setObject
    try (CallableStatement cs = conn.prepareCall("{ ? = call test_actor_array(?) }")) {
      cs.registerOutParameter(1, Types.INTEGER);
      // Set the Struct array directly
      cs.setObject(2, structArr);
      cs.execute();

      int result = cs.getInt(1);
      assertEquals("Should return 2 actors", 2, result);
    }
  }
}
