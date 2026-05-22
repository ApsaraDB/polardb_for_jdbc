/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.jdbc;

import static com.aliyun.polardb2.util.internal.Nullness.castNonNull;

import com.aliyun.polardb2.core.BaseConnection;
import com.aliyun.polardb2.core.BaseStatement;
import com.aliyun.polardb2.core.Field;
import com.aliyun.polardb2.core.Oid;
import com.aliyun.polardb2.core.Tuple;
import com.aliyun.polardb2.jdbc.ArrayDecoding.PgArrayList;
import com.aliyun.polardb2.jdbc2.ArrayAssistantRegistry;
import com.aliyun.polardb2.util.ByteConverter;
import com.aliyun.polardb2.util.GT;
import com.aliyun.polardb2.util.PSQLException;
import com.aliyun.polardb2.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <p>Array is used collect one column of query result data.</p>
 *
 * <p>Read a field of type Array into either a natively-typed Java array object or a ResultSet.
 * Accessor methods provide the ability to capture array slices.</p>
 *
 * <p>Other than the constructor all methods are direct implementations of those specified for
 * java.sql.Array. Please refer to the javadoc for java.sql.Array for detailed descriptions of the
 * functionality and parameters of the methods of this class.</p>
 *
 * @see ResultSet#getArray
 */
public class PgArray implements java.sql.Array {

  static {
    ArrayAssistantRegistry.register(Oid.UUID, new UUIDArrayAssistant());
    ArrayAssistantRegistry.register(Oid.UUID_ARRAY, new UUIDArrayAssistant());
  }

  /**
   * A database connection.
   */
  protected @Nullable BaseConnection connection;

  /**
   * The OID of this field.
   */
  private final int oid;

  /**
   * Returns the OID of this array type.
   * Package-private for use by PgPreparedStatement.setArray.
   */
  int getOID() {
    return oid;
  }

  /**
   * Field value as String.
   */
  protected @Nullable String fieldString;

  /**
   * Value of field as {@link PgArrayList}. Will be initialized only once within
   * {@link #buildArrayList(String)}.
   */
  protected ArrayDecoding.@Nullable PgArrayList arrayList;

  protected byte @Nullable [] fieldBytes;

  private final ResourceLock lock = new ResourceLock();

  private PgArray(BaseConnection connection, int oid) throws SQLException {
    this.connection = connection;
    this.oid = oid;
  }

  /**
   * Create a new Array.
   *
   * @param connection a database connection
   * @param oid the oid of the array datatype
   * @param fieldString the array data in string form
   * @throws SQLException if something wrong happens
   */
  public PgArray(BaseConnection connection, int oid, @Nullable String fieldString)
      throws SQLException {
    this(connection, oid);
    this.fieldString = fieldString;
  }

  /**
   * Create a new Array.
   *
   * @param connection a database connection
   * @param oid the oid of the array datatype
   * @param fieldBytes the array data in byte form
   * @throws SQLException if something wrong happens
   */
  public PgArray(BaseConnection connection, int oid, byte @Nullable [] fieldBytes)
      throws SQLException {
    this(connection, oid);
    this.fieldBytes = fieldBytes;
  }

  private BaseConnection getConnection() {
    return castNonNull(connection);
  }

  @SuppressWarnings("return.type.incompatible")
  public Object getArray() throws SQLException {
    return getArrayImpl(1, 0, null);
  }

  @SuppressWarnings("return.type.incompatible")
  public Object getArray(long index, int count) throws SQLException {
    return getArrayImpl(index, count, null);
  }

  @SuppressWarnings("return.type.incompatible")
  public Object getArrayImpl(Map<String, Class<?>> map) throws SQLException {
    return getArrayImpl(1, 0, map);
  }

  @SuppressWarnings("return.type.incompatible")
  public Object getArray(Map<String, Class<?>> map) throws SQLException {
    return getArrayImpl(map);
  }

  @SuppressWarnings("return.type.incompatible")
  public Object getArray(long index, int count, @Nullable Map<String, Class<?>> map)
      throws SQLException {
    return getArrayImpl(index, count, map);
  }

  public @Nullable Object getArrayImpl(long index, int count, @Nullable Map<String, Class<?>> map)
      throws SQLException {

    // for now maps aren't supported.
    if (map != null && !map.isEmpty()) {
      throw com.aliyun.polardb2.Driver.notImplemented(this.getClass(), "getArrayImpl(long,int,Map)");
    }

    // array index is out of range
    if (index < 1) {
      throw new PSQLException(GT.tr("The array index is out of range: {0}", index),
          PSQLState.DATA_ERROR);
    }

    if (fieldBytes != null) {
      return readBinaryArray(fieldBytes, (int) index, count);
    }

    if (fieldString == null) {
      return null;
    }

    final PgArrayList arrayList = buildArrayList(fieldString);

    if (count == 0) {
      count = arrayList.size();
    }

    // array index out of range
    if ((index - 1) + count > arrayList.size()) {
      throw new PSQLException(
          GT.tr("The array index is out of range: {0}, number of elements: {1}.",
              index + count, (long) arrayList.size()),
          PSQLState.DATA_ERROR);
    }

    return buildArray(arrayList, (int) index, count);
  }

  private Object readBinaryArray(byte[] fieldBytes, int index, int count) throws SQLException {
    return ArrayDecoding.readBinaryArray(index, count, fieldBytes, getConnection());
  }

  private ResultSet readBinaryResultSet(byte[] fieldBytes, int index, int count)
      throws SQLException {
    int dimensions = ByteConverter.int4(fieldBytes, 0);
    // int flags = ByteConverter.int4(fieldBytes, 4); // bit 0: 0=no-nulls, 1=has-nulls
    int elementOid = ByteConverter.int4(fieldBytes, 8);
    int pos = 12;
    int[] dims = new int[dimensions];
    for (int d = 0; d < dimensions; ++d) {
      dims[d] = ByteConverter.int4(fieldBytes, pos);
      pos += 4;
      /* int lbound = ByteConverter.int4(fieldBytes, pos); */
      pos += 4;
    }
    if (count > 0 && dimensions > 0) {
      dims[0] = Math.min(count, dims[0]);
    }
    List<Tuple> rows = new ArrayList<Tuple>();
    Field[] fields = new Field[2];

    storeValues(fieldBytes, rows, fields, elementOid, dims, pos, 0, index);

    BaseStatement stat = (BaseStatement) getConnection()
        .createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    return stat.createDriverResultSet(fields, rows);
  }

  private int storeValues(byte[] fieldBytes, List<Tuple> rows, Field[] fields, int elementOid,
      final int[] dims,
      int pos, final int thisDimension, int index) throws SQLException {
    // handle an empty array
    if (dims.length == 0) {
      fields[0] = new Field("INDEX", Oid.INT4);
      fields[0].setFormat(Field.BINARY_FORMAT);
      fields[1] = new Field("VALUE", elementOid);
      fields[1].setFormat(Field.BINARY_FORMAT);
      for (int i = 1; i < index; ++i) {
        int len = ByteConverter.int4(fieldBytes, pos);
        pos += 4;
        if (len != -1) {
          pos += len;
        }
      }
    } else if (thisDimension == dims.length - 1) {
      fields[0] = new Field("INDEX", Oid.INT4);
      fields[0].setFormat(Field.BINARY_FORMAT);
      fields[1] = new Field("VALUE", elementOid);
      fields[1].setFormat(Field.BINARY_FORMAT);
      for (int i = 1; i < index; ++i) {
        int len = ByteConverter.int4(fieldBytes, pos);
        pos += 4;
        if (len != -1) {
          pos += len;
        }
      }
      for (int i = 0; i < dims[thisDimension]; ++i) {
        byte[][] rowData = new byte[2][];
        rowData[0] = new byte[4];
        ByteConverter.int4(rowData[0], 0, i + index);
        rows.add(new Tuple(rowData));
        int len = ByteConverter.int4(fieldBytes, pos);
        pos += 4;
        if (len == -1) {
          continue;
        }
        rowData[1] = new byte[len];
        System.arraycopy(fieldBytes, pos, rowData[1], 0, rowData[1].length);
        pos += len;
      }
    } else {
      fields[0] = new Field("INDEX", Oid.INT4);
      fields[0].setFormat(Field.BINARY_FORMAT);
      fields[1] = new Field("VALUE", oid);
      fields[1].setFormat(Field.BINARY_FORMAT);
      int nextDimension = thisDimension + 1;
      int dimensionsLeft = dims.length - nextDimension;
      for (int i = 1; i < index; ++i) {
        pos = calcRemainingDataLength(fieldBytes, dims, pos, elementOid, nextDimension);
      }
      for (int i = 0; i < dims[thisDimension]; ++i) {
        byte[][] rowData = new byte[2][];
        rowData[0] = new byte[4];
        ByteConverter.int4(rowData[0], 0, i + index);
        rows.add(new Tuple(rowData));
        int dataEndPos = calcRemainingDataLength(fieldBytes, dims, pos, elementOid, nextDimension);
        int dataLength = dataEndPos - pos;
        rowData[1] = new byte[12 + 8 * dimensionsLeft + dataLength];
        ByteConverter.int4(rowData[1], 0, dimensionsLeft);
        System.arraycopy(fieldBytes, 4, rowData[1], 4, 8);
        System.arraycopy(fieldBytes, 12 + nextDimension * 8, rowData[1], 12, dimensionsLeft * 8);
        System.arraycopy(fieldBytes, pos, rowData[1], 12 + dimensionsLeft * 8, dataLength);
        pos = dataEndPos;
      }
    }
    return pos;
  }

  private int calcRemainingDataLength(byte[] fieldBytes,
      int[] dims, int pos, int elementOid, int thisDimension) {
    if (thisDimension == dims.length - 1) {
      for (int i = 0; i < dims[thisDimension]; ++i) {
        int len = ByteConverter.int4(fieldBytes, pos);
        pos += 4;
        if (len == -1) {
          continue;
        }
        pos += len;
      }
    } else {
      pos = calcRemainingDataLength(fieldBytes, dims, elementOid, pos, thisDimension + 1);
    }
    return pos;
  }

  /**
   * Build {@link ArrayList} from field's string input. As a result of this method
   * {@link #arrayList} is build. Method can be called many times in order to make sure that array
   * list is ready to use, however {@link #arrayList} will be set only once during first call.
   */
  private PgArrayList buildArrayList(String fieldString) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (arrayList == null) {
        arrayList = ArrayDecoding.buildArrayList(fieldString, getConnection().getTypeInfo().getArrayDelimiter(oid));
      }
      return arrayList;
    }
  }

  /**
   * Convert {@link ArrayList} to array.
   *
   * @param input list to be converted into array
   */
  private Object buildArray(ArrayDecoding.PgArrayList input, int index, int count) throws SQLException {
    final BaseConnection connection = getConnection();
    return ArrayDecoding.readStringArray(index, count, connection.getTypeInfo().getPGArrayElement(oid), input, connection);
  }

  public int getBaseType() throws SQLException {
    return getConnection().getTypeInfo().getSQLType(getBaseTypeName());
  }

  public String getBaseTypeName() throws SQLException {
    int elementOID = getConnection().getTypeInfo().getPGArrayElement(oid);
    return castNonNull(getConnection().getTypeInfo().getPGType(elementOID));
  }

  public java.sql.ResultSet getResultSet() throws SQLException {
    return getResultSetImpl(1, 0, null);
  }

  public java.sql.ResultSet getResultSet(long index, int count) throws SQLException {
    return getResultSetImpl(index, count, null);
  }

  public ResultSet getResultSet(@Nullable Map<String, Class<?>> map) throws SQLException {
    return getResultSetImpl(map);
  }

  public ResultSet getResultSet(long index, int count, @Nullable Map<String, Class<?>> map)
      throws SQLException {
    return getResultSetImpl(index, count, map);
  }

  public ResultSet getResultSetImpl(@Nullable Map<String, Class<?>> map) throws SQLException {
    return getResultSetImpl(1, 0, map);
  }

  public ResultSet getResultSetImpl(long index, int count, @Nullable Map<String, Class<?>> map)
      throws SQLException {

    // for now maps aren't supported.
    if (map != null && !map.isEmpty()) {
      throw com.aliyun.polardb2.Driver.notImplemented(this.getClass(), "getResultSetImpl(long,int,Map)");
    }

    // array index is out of range
    if (index < 1) {
      throw new PSQLException(GT.tr("The array index is out of range: {0}", index),
          PSQLState.DATA_ERROR);
    }

    if (fieldBytes != null) {
      return readBinaryResultSet(fieldBytes, (int) index, count);
    }

    final PgArrayList arrayList = buildArrayList(castNonNull(fieldString));

    if (count == 0) {
      count = arrayList.size();
    }

    // array index out of range
    if ((--index) + count > arrayList.size()) {
      throw new PSQLException(
          GT.tr("The array index is out of range: {0}, number of elements: {1}.",
                  index + count, (long) arrayList.size()),
          PSQLState.DATA_ERROR);
    }

    List<Tuple> rows = new ArrayList<Tuple>();

    Field[] fields = new Field[2];

    // one dimensional array
    if (arrayList.dimensionsCount <= 1) {
      // array element type
      final int baseOid = getConnection().getTypeInfo().getPGArrayElement(oid);
      fields[0] = new Field("INDEX", Oid.INT4);
      fields[1] = new Field("VALUE", baseOid);

      for (int i = 0; i < count; i++) {
        int offset = (int) index + i;
        byte[] @Nullable [] t = new byte[2][0];
        String v = (String) arrayList.get(offset);
        t[0] = getConnection().encodeString(Integer.toString(offset + 1));
        t[1] = v == null ? null : getConnection().encodeString(v);
        rows.add(new Tuple(t));
      }
    } else {
      // when multi-dimensional
      fields[0] = new Field("INDEX", Oid.INT4);
      fields[1] = new Field("VALUE", oid);
      for (int i = 0; i < count; i++) {
        int offset = (int) index + i;
        byte[] @Nullable [] t = new byte[2][0];
        Object v = arrayList.get(offset);

        t[0] = getConnection().encodeString(Integer.toString(offset + 1));
        t[1] = v == null ? null : getConnection().encodeString(toString((ArrayDecoding.PgArrayList) v));
        rows.add(new Tuple(t));
      }
    }

    BaseStatement stat = (BaseStatement) getConnection()
        .createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    return stat.createDriverResultSet(fields, rows);
  }

  @SuppressWarnings("nullness")
  public @Nullable String toString() {
    if (fieldString == null && fieldBytes != null) {
      try {
        Object array = readBinaryArray(fieldBytes, 1, 0);

        final ArrayEncoding.ArrayEncoder arraySupport = ArrayEncoding.getArrayEncoder(array);
        assert arraySupport != null;
        fieldString = arraySupport.toArrayString(connection.getTypeInfo().getArrayDelimiter(oid), array);
      } catch (SQLException e) {
        fieldString = "NULL"; // punt
      }
    }
    return fieldString;
  }

  /**
   * Convert array list to PG String representation (e.g. {0,1,2}).
   */
  private String toString(ArrayDecoding.PgArrayList list) throws SQLException {
    if (list == null) {
      return "NULL";
    }

    StringBuilder b = new StringBuilder().append('{');

    char delim = getConnection().getTypeInfo().getArrayDelimiter(oid);

    for (int i = 0; i < list.size(); i++) {
      Object v = list.get(i);

      if (i > 0) {
        b.append(delim);
      }

      if (v == null) {
        b.append("NULL");
      } else if (v instanceof ArrayDecoding.PgArrayList) {
        b.append(toString((ArrayDecoding.PgArrayList) v));
      } else {
        escapeArrayElement(b, (String) v);
      }
    }

    b.append('}');

    return b.toString();
  }

  public static void escapeArrayElement(StringBuilder b, String s) {
    b.append('"');
    for (int j = 0; j < s.length(); j++) {
      char c = s.charAt(j);
      if (c == '"' || c == '\\') {
        b.append('\\');
      }

      b.append(c);
    }
    b.append('"');
  }

  /**
   * POLAR: Check if a value needs double-quoting inside a composite (record)
   * literal. Per PostgreSQL Composite Types spec, a field value MUST be
   * double-quoted if it contains comma, parenthesis, double quote, backslash,
   * or has leading/trailing whitespace.
   */
  public static boolean needsQuotingInRecord(String s) {
    if (s.isEmpty()) {
      return false;
    }
    if (Character.isWhitespace(s.charAt(0))
        || Character.isWhitespace(s.charAt(s.length() - 1))) {
      return true;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == ',' || c == '(' || c == ')' || c == '"' || c == '\\') {
        return true;
      }
    }
    return false;
  }

  /**
   * POLAR: Append a single record field value to the record literal builder,
   * adding double quotes and escaping {@code "} / {@code \} when required.
   *
   * <p>Empty fields are emitted as nothing (representing SQL NULL between
   * commas, e.g. {@code (a,,c)}).
   */
  public static void appendRecordField(StringBuilder sb, String field) {
    if (field.isEmpty()) {
      return;
    }
    if (!needsQuotingInRecord(field)) {
      sb.append(field);
      return;
    }
    sb.append('"');
    for (int k = 0; k < field.length(); k++) {
      char c = field.charAt(k);
      if (c == '"' || c == '\\') {
        sb.append('\\');
      }
      sb.append(c);
    }
    sb.append('"');
  }

  /**
   * POLAR: Build a composite record literal {@code (f1,"f2",...)} from a raw
   * comma-separated field string produced by Oracle-compatibility frameworks
   * (e.g. Manulife's OracleArrayParameter / PGobject value).
   *
   * <p>Each field is split on {@code ,} and individually inspected; fields
   * that contain commas, parentheses, quotes, backslashes, or surrounding
   * whitespace are double-quoted and inner {@code "} / {@code \} escaped.
   *
   * <p>This fixes the {@code malformed record literal: "..." Too few columns}
   * server error reported when a varchar field value contains parentheses
   * (e.g. {@code 會診摘要副本 ( 詳情請參閱以上英文版 )。}). Without field-
   * level quoting, the server's record_in parser misinterprets the inner
   * parentheses as record boundaries.
   *
   * <p>NOTE: This assumes the raw input uses {@code ,} as the field
   * delimiter and that fields themselves do not contain literal commas.
   * Frameworks that need embedded commas in field values must produce
   * properly quoted record literals upstream.
   */
  public static String buildRecordLiteralFromCsv(String rawCsv) {
    StringBuilder sb = new StringBuilder();
    sb.append('(');
    int n = rawCsv.length();
    int start = 0;
    boolean first = true;
    for (int i = 0; i <= n; i++) {
      if (i == n || rawCsv.charAt(i) == ',') {
        if (!first) {
          sb.append(',');
        }
        first = false;
        appendRecordField(sb, rawCsv.substring(start, i));
        start = i + 1;
      }
    }
    sb.append(')');
    return sb.toString();
  }

  /**
   * POLAR: Fix array elements that should be composite record literals but
   * are missing the enclosing parentheses.
   *
   * <p>Parses the array string into individual elements, checks each one,
   * and wraps elements that don't start with {@code (} in record literal
   * parentheses. Elements that are already record literals (starting with
   * {@code (}) are left unchanged.
   *
   * <p>Example: {@code {"HHF01790,HH,RB"}} becomes
   * {@code {"(HHF01790,HH,RB)"}}.
   *
   * @param arrayString the array literal string
   * @param delim the array delimiter character (usually ',')
   * @return the fixed array string with record parentheses added where needed
   */
  public static String fixCompositeArrayElements(String arrayString, char delim) {
    ArrayDecoding.PgArrayList list = ArrayDecoding.buildArrayList(arrayString, delim);
    boolean needsFix = false;
    for (int i = 0; i < list.size(); i++) {
      Object elem = list.get(i);
      if (elem instanceof String) {
        String s = (String) elem;
        if (!s.isEmpty() && s.charAt(0) != '(') {
          needsFix = true;
          break;
        }
      }
    }
    if (!needsFix) {
      return arrayString;
    }
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append(delim);
      }
      Object elem = list.get(i);
      if (elem == null) {
        sb.append("NULL");
      } else {
        String s = (String) elem;
        if (!s.isEmpty() && s.charAt(0) != '(') {
          // POLAR: Build the record literal with per-field quoting so that
          // values containing parentheses / commas don't break record_in.
          escapeArrayElement(sb, buildRecordLiteralFromCsv(s));
        } else {
          escapeArrayElement(sb, s);
        }
      }
    }
    sb.append('}');
    return sb.toString();
  }

  public boolean isBinary() {
    return fieldBytes != null;
  }

  public byte @Nullable [] toBytes() {
    return fieldBytes;
  }

  public void free() throws SQLException {
    connection = null;
    fieldString = null;
    fieldBytes = null;
    arrayList = null;
  }
}
