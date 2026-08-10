/*
 * Copyright (c) 2026, Alibaba Cloud
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2;

/**
 * Describes a single field (attribute) of a composite type, in the order it is
 * defined on the server ({@code pg_attribute.attnum}).
 *
 * <p>POLAR: returned by {@link PGConnection#getCompositeTypeFields(String)} so that
 * application frameworks can map Java bean fields to database composite-type fields
 * <b>by name</b>. The standard JDBC API ({@code createStruct}/{@code getAttributes})
 * only carries positionally ordered values, so the driver cannot reorder them on its
 * own; combining this metadata with Java reflection lets the framework produce a
 * value array ordered according to the database schema (both for writes and reads).</p>
 */
public final class PGCompositeField {

  private final String fieldName;
  private final int fieldNumber;
  private final int fieldTypeOid;

  /**
   * Create a composite field description.
   *
   * @param fieldName    field name as declared in the composite type ({@code pg_attribute.attname})
   * @param fieldNumber  1-based field position ({@code pg_attribute.attnum}), dropped fields excluded
   * @param fieldTypeOid OID of the field's data type ({@code pg_attribute.atttypid})
   */
  public PGCompositeField(String fieldName, int fieldNumber, int fieldTypeOid) {
    this.fieldName = fieldName;
    this.fieldNumber = fieldNumber;
    this.fieldTypeOid = fieldTypeOid;
  }

  /**
   * Returns the field name as declared in the composite type.
   *
   * @return field name ({@code attname})
   */
  public String getFieldName() {
    return fieldName;
  }

  /**
   * Returns the 1-based position of the field within the composite type.
   *
   * @return field position ({@code attnum})
   */
  public int getFieldNumber() {
    return fieldNumber;
  }

  /**
   * Returns the OID of the field's data type.
   *
   * @return type OID ({@code atttypid})
   */
  public int getFieldTypeOid() {
    return fieldTypeOid;
  }

  @Override
  public String toString() {
    return "PGCompositeField[" + fieldNumber + ": " + fieldName + " oid=" + fieldTypeOid + "]";
  }
}
