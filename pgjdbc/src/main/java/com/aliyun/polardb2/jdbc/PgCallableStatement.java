/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.jdbc;

import static com.aliyun.polardb2.util.internal.Nullness.castNonNull;

import com.aliyun.polardb2.Driver;
import com.aliyun.polardb2.core.Field;
import com.aliyun.polardb2.core.Oid;
import com.aliyun.polardb2.core.ParameterList;
import com.aliyun.polardb2.core.Query;
import com.aliyun.polardb2.core.Tuple;
import com.aliyun.polardb2.util.GT;
import com.aliyun.polardb2.util.PGobject;
import com.aliyun.polardb2.util.PSQLException;
import com.aliyun.polardb2.util.PSQLState;

import org.checkerframework.checker.index.qual.Positive;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

class PgCallableStatement extends PgPreparedStatement implements CallableStatement {
  // Used by the callablestatement style methods
  private final boolean isFunction;
  private final boolean outParamBeforeFunc;
  /* POLAR: DO anonymous block with $N INOUT parameters */
  private final boolean isDoBlock;
  /* POLAR: Oracle sequence pseudocolumn (e.g. seq.nextval / seq.currval) */
  private final boolean isSequencePseudocol;
  // functionReturnType contains the user supplied value to check
  // testReturn contains a modified version to make it easier to
  // check the getXXX methods..
  private int @Nullable [] functionReturnType;
  private int @Nullable [] testReturn;
  /* POLAR DIFF: track the original SQL type before DATE->TIMESTAMP mapping
   * so that getObject() can return java.sql.Date when user explicitly registered Types.DATE */
  private int @Nullable [] userRegisteredType;
  // POLAR: stores the type name provided to registerOutParameter(index, ARRAY, typeName)
  // used when converting Types.OTHER String values back to the correct Array type
  private @Nullable String @Nullable [] functionReturnTypeName;
  // returnTypeSet is true when a proper call to registerOutParameter has been made
  private boolean returnTypeSet;
  protected @Nullable Object @Nullable [] callResult;
  private int lastIndex = 0;

  PgCallableStatement(PgConnection connection, String sql, int rsType, int rsConcurrency,
      int rsHoldability) throws SQLException {
    super(connection, connection.borrowCallableQuery(sql), rsType, rsConcurrency, rsHoldability);
    this.isFunction = preparedQuery.isFunction;
    this.outParamBeforeFunc = preparedQuery.outParamBeforeFunc;
    this.isDoBlock = preparedQuery.isDoBlock;
    this.isSequencePseudocol = preparedQuery.isSequencePseudocol;

    /* POLAR: get the unamed SQL */
    if (this.preparedQuery.unProc != null && this.preparedQuery.unProc.isUnamedProc()) {
      sql = this.preparedQuery.unProc.getUnamedProcSql();
    }

    if (this.isFunction) {
      // POLAR: Use parameter count + 1 to ensure array is large enough for all parameter indices.
      // This is needed for cases where user registers a parameter with high index as OUT
      // even if it's actually an IN parameter in the function definition.
      //
      // For DO blocks:
      //   - $N-style: doBlockParamCount holds the max $N index (may exceed preparedParameters count)
      //   - ?-style: doBlockParamCount is 0; use preparedParameters.getParameterCount() instead
      int baseCount;
      if (this.isDoBlock && preparedQuery.doBlockParamCount > 0) {
        // $N-style DO block: explicit param count from SQL scanning
        baseCount = preparedQuery.doBlockParamCount;
      } else if (this.isSequencePseudocol) {
        // POLAR: sequence pseudocolumn: exactly 1 OUT parameter (the sequence value), no ? params
        baseCount = 1;
      } else {
        // Normal function/procedure call, or ?-style DO block (? converted to $N by parseJdbcSql)
        baseCount = this.preparedParameters.getParameterCount();
      }
      int arraySize = baseCount + 1;
      this.testReturn = new int[arraySize];
      this.functionReturnType = new int[arraySize];
      this.functionReturnTypeName = new String[arraySize];
      this.userRegisteredType = new int[arraySize];

      // POLAR: main entry for call function
      // if server enable, pass function call as Oracle format
      if (connection.callFunctionMode() && this.outParamBeforeFunc) {
        this.preparedParameters.setCallFunctionMode(true);
      }
    }
  }

  public int executeUpdate() throws SQLException {
    if (isFunction) {
      executeWithFlags(0);
      return 0;
    }
    return super.executeUpdate();
  }

  @Override
  public ResultSet executeQuery() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      /* POLAR: For stored procedure/function calls (isFunction=true), allow execution
       * even if no result set is returned (e.g., procedures with only out parameters
       * that don't assign values).
       *
       * Previously this returned null, but that violates the JDBC spec (executeQuery
       * must never return null) and causes NullPointerException in connection pools
       * like HikariCP that wrap the result in a proxy:
       *   "Cannot invoke java.sql.ResultSet.close() because this.delegate is null"
       *
       * Fix: if the procedure produces a result set, return it; otherwise return an
       * empty ResultSet that can be safely iterated and closed. */
      if (isFunction) {
        boolean hasResultSet = executeWithFlags(0);
        if (hasResultSet && result != null && result.getResultSet() != null) {
          return getSingleResultSet();
        }
        return createDriverResultSet(new Field[0], new java.util.ArrayList<Tuple>());
      }
      return super.executeQuery();
    }
  }

  public @Nullable Object getObject(@Positive int i, @Nullable Map<String, Class<?>> map)
      throws SQLException {
    return getObjectImpl(i, map);
  }

  public @Nullable Object getObject(String s, @Nullable Map<String, Class<?>> map) throws SQLException {
    return getObjectImpl(s, map);
  }

  @Override
  public boolean executeWithFlags(int flags) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      /* POLAR: For DO anonymous blocks, all ? parameters are INOUT.
       * Users may only call registerOutParameter (not setXxx) for some/all of them.
       * Without an explicit setXxx call, paramValues[i] == null and the driver would
       * throw "No value specified for parameter N" from checkAllParametersSet().
       * Auto-fill unset parameters with SQL NULL so the block executes successfully. */
      if (isDoBlock) {
        int paramCount = preparedParameters.getParameterCount();
        for (int i = 1; i <= paramCount; i++) {
          if (!preparedParameters.isParameterSet(i)) {
            preparedParameters.setNull(i, 0);
          }
        }
      }

      boolean hasResultSet = super.executeWithFlags(flags);
      int[] functionReturnType = this.functionReturnType;
      if (!isFunction || !returnTypeSet || functionReturnType == null) {
        return hasResultSet;
      }

      // If we are executing and there are out parameters
      // callable statement function set the return data
      if (!hasResultSet) {
        /* POLAR: Allow stored procedure with out parameters to return nothing.
         * Some stored procedures may have out parameters but not assign values to them,
         * in which case no result set is returned. We initialize callResult with nulls. */
        lastIndex = 0;
        int resultSize = doBlockResultSize();
        this.callResult = new Object[resultSize];
        return false;
      }

      ResultSet rs = castNonNull(getResultSet());
      if (!rs.next()) {
        /* POLAR: Allow stored procedure with out parameters to return empty result set.
         * Initialize callResult with nulls so getXXX() calls return null/default values. */
        rs.close();
        result = null;
        lastIndex = 0;
        int resultSize = doBlockResultSize();
        this.callResult = new Object[resultSize];
        return false;
      }

      // figure out how many columns
      int cols = rs.getMetaData().getColumnCount();

      /* POLAR: For DO anonymous blocks, the result set columns map directly to $1, $2, ...
       * in order. Skip the outParameterCount check since DO blocks do not use
       * preparedParameters.registerOutParameter.
       * Similarly, for sequence pseudocolumns, no ? placeholder was registered; skip the check. */
      int extraOutColumns = 0;
      if (!isDoBlock && !isSequencePseudocol) {
        int outParameterCount = preparedParameters.getOutParameterCount();

        // POLAR: Allow execution when cols <= outParameterCount.
        // This handles cases where an IN parameter is incorrectly registered as OUT parameter.
        // The database only returns actual OUT parameters in the result set,
        // but user may register more parameters as OUT than actual OUT parameters.
        //
        // POLAR: Also allow execution when cols > outParameterCount.
        // This handles cases where an IN OUT parameter was only setXxx() (as IN) but the
        // caller forgot to registerOutParameter() for it. The server still returns the
        // INOUT parameter as an OUT column, so cols exceeds outParameterCount. We absorb
        // the surplus columns into the unregistered slots in parameter order; callers that
        // never registered them simply won't fetch the value via getXxx().
        // The total ? placeholder count guards against an unbounded surplus:
        // (parameterCount - outParameterCount) is the number of unregistered slots, so
        // surplus columns beyond that are still rejected as truly invalid.
        if (cols > outParameterCount) {
          int unregisteredSlots = preparedParameters.getParameterCount() - outParameterCount;
          if (cols - outParameterCount > unregisteredSlots) {
            throw new PSQLException(
                GT.tr("A CallableStatement was executed with an invalid number of parameters"),
                PSQLState.SYNTAX_ERROR);
          }
          extraOutColumns = cols - outParameterCount;
        }
      }

      // reset last result fetched (for wasNull)
      lastIndex = 0;

      // allocate enough space for all possible parameters without regard to in/out
      int resultSize = doBlockResultSize();
      @Nullable Object[] callResult = new Object[resultSize];
      this.callResult = callResult;

      if (isDoBlock) {
        /* POLAR: For DO anonymous blocks, result set columns correspond to $1, $2, ...
         * in sequential order. Map each column directly to its parameter index. */
        String @Nullable [] functionReturnTypeName = this.functionReturnTypeName;
        for (int i = 0; i < cols; i++) {
          int paramIdx = i; // 0-based index into callResult
          callResult[paramIdx] = rs.getObject(i + 1);
          int columnType = rs.getMetaData().getColumnType(i + 1);
          int registeredType = functionReturnType[paramIdx];
          String typeName = functionReturnTypeName != null ? functionReturnTypeName[paramIdx] : null;
          if (registeredType != 0 && columnType != registeredType) {
            try {
              callResult[paramIdx] = convertOutParamValue(callResult[paramIdx], columnType,
                  registeredType, typeName);
            } catch (PSQLException e) {
              throw new PSQLException(GT.tr(
                  "A CallableStatement function was executed and the out parameter {0} was of type {1} however type {2} was registered.",
                  i + 1, "java.sql.Types=" + columnType, "java.sql.Types=" + registeredType),
                  PSQLState.DATA_TYPE_MISMATCH);
            }
          }
        }
      } else {
        // move them into the result set
        String @Nullable [] functionReturnTypeName = this.functionReturnTypeName;
        // POLAR: Track unregistered-INOUT surplus locally; decremented as we consume slots.
        int remainingExtras = extraOutColumns;
        for (int i = 0, j = 0; i < cols; i++, j++) {
          // find the next out parameter, the assumption is that the functionReturnType
          // array will be initialized with 0 and only out parameters will have values
          // other than 0. 0 is the value for java.sql.Types.NULL, which should not
          // conflict.
          // POLAR: When extra OUT columns exist (caller forgot registerOutParameter
          // for an IN OUT param), do not skip slot j with functionReturnType[j]==0;
          // instead occupy it with the next surplus column so callResult is aligned
          // with parameter ordinal positions (1-based). This keeps any user that does
          // call getXxx(idx) on a registered slot correct, and lets the unregistered
          // INOUT value silently land in its proper slot (typically unused).
          while (j < functionReturnType.length && functionReturnType[j] == 0) {
            if (remainingExtras > 0) {
              remainingExtras--;
              break;
            }
            j++;
          }
          if (j >= functionReturnType.length) {
            // Defensive: extraOutColumns guard above should prevent this; if reached,
            // silently drop trailing surplus columns rather than ArrayIndexOutOfBounds.
            break;
          }

          callResult[j] = rs.getObject(i + 1);
          int columnType = rs.getMetaData().getColumnType(i + 1);
          String typeName = functionReturnTypeName != null ? functionReturnTypeName[j] : null;

          // POLAR: Skip type conversion for unregistered slots (functionReturnType[j]==0)
          // since the user never declared a registered type to convert to.
          if (functionReturnType[j] != 0 && columnType != functionReturnType[j]) {
            // POLAR: convert out parameter value between compatible types.
            try {
              callResult[j] = convertOutParamValue(callResult[j], columnType,
                  functionReturnType[j], typeName);
            } catch (PSQLException e) {
              // re-throw with column index info
              throw new PSQLException(GT.tr(
                  "A CallableStatement function was executed and the out parameter {0} was of type {1} however type {2} was registered.",
                  i + 1, "java.sql.Types=" + columnType, "java.sql.Types=" + functionReturnType[j]),
                  PSQLState.DATA_TYPE_MISMATCH);
            }
          }
        }
      }
      rs.close();
      result = null;
    }
    return false;
  }

  /**
   * POLAR: Returns the size to allocate for callResult array.
   * For $N-style DO blocks, uses the explicit doBlockParamCount.
   * For ?-style DO blocks and regular calls, uses preparedParameters.getParameterCount().
   */
  private int doBlockResultSize() {
    if (isDoBlock && preparedQuery.doBlockParamCount > 0) {
      return preparedQuery.doBlockParamCount + 1;
    }
    return preparedParameters.getParameterCount() + 1;
  }

  /**
   * POLAR: Converts an out parameter value from the database column type to the registered type.
   *
   * <p>Supports conversions between compatible type families:
   * <ul>
   *   <li>Integer family: SMALLINT, INTEGER, BIGINT</li>
   *   <li>Floating-point family: REAL, FLOAT, DOUBLE</li>
   *   <li>Numeric family: NUMERIC, DECIMAL</li>
   *   <li>String family: CHAR, VARCHAR, LONGVARCHAR, NCHAR, NVARCHAR, LONGNVARCHAR</li>
   *   <li>Date/time family: DATE, TIME, TIMESTAMP, TIMESTAMP_WITH_TIMEZONE</li>
   *   <li>Any type to/from string family (toString / parse)</li>
   * </ul>
   *
   * @param value        the raw value from the result set (may be null)
   * @param columnType   the actual SQL type returned by the database
   * @param registeredType the SQL type registered via registerOutParameter
   * @param registeredTypeName the type name registered via registerOutParameter(index, ARRAY, name)
   * @return the converted value, or null if value is null
   * @throws PSQLException if no compatible conversion exists
   */
  private @Nullable Object convertOutParamValue(
      @Nullable Object value, int columnType, int registeredType,
      @Nullable String registeredTypeName) throws PSQLException {
    if (value == null) {
      return null;
    }

    // REF_CURSOR / OTHER: backwards-compatible alias, keep value as-is
    if (columnType == Types.REF_CURSOR && registeredType == Types.OTHER) {
      return value;
    }

    // POLAR: When the DB returns Types.OTHER (e.g. DO block result, cross-package TABLE OF RECORD,
    // composite types, or other unrecognized types), we should still try to convert the value
    // to the registered type. The value is typically a String representation.
    if (columnType == Types.OTHER) {
      // POLAR: If user registered as ARRAY and value is a String, wrap it as PgArray so that
      // getArray() can cast it without ClassCastException.
      if (registeredType == Types.ARRAY && value instanceof String) {
        return buildPgArrayFromString(value.toString(), registeredTypeName);
      }
      /* POLAR: After typtype='a' was reverted to Types.OTHER in TypeInfoCache,
       * PolarDB TABLE OF / VARRAY / INDEX BY columns surface here as PGobject
       * instead of String. Honour ARRAY registration in that case too, by
       * unwrapping the PGobject's raw text and wrapping it as PgArray. */
      if (registeredType == Types.ARRAY && value instanceof PGobject) {
        PGobject pgo = (PGobject) value;
        String raw = pgo.getValue();
        String name = registeredTypeName != null && !registeredTypeName.isEmpty()
            ? registeredTypeName : pgo.getType();
        return buildPgArrayFromString(raw == null ? "" : raw, name);
      }
      // POLAR: If user registered as STRUCT and value is a String (DO block composite OUT param),
      // wrap it as PGobject so that getObject() returns a usable composite value instead of
      // a raw String.
      if (registeredType == Types.STRUCT && value instanceof String) {
        return buildPgObjectFromString(value.toString(), registeredTypeName);
      }
      /* POLAR: STRUCT registration with a PGobject value is already a usable
       * composite handle; pass it through (optionally re-tagging the type name
       * so getSQLTypeName() returns what the caller registered). */
      if (registeredType == Types.STRUCT && value instanceof PGobject) {
        return value;
      }
      // If value is a String, try to parse it to the registered type
      if (value instanceof String && registeredType != Types.OTHER) {
        return parseStringToType(value.toString(), registeredType);
      }
      // Otherwise pass through as-is (e.g., PGobject for composite types)
      return value;
    }

    // ---- string family on either side ----
    if (isStringType(registeredType)) {
      // Any DB type → registered as string: convert to string representation
      return value.toString();
    }
    if (isStringType(columnType)) {
      // DB returned string → parse to the registered type
      return parseStringToType(value.toString(), registeredType);
    }

    // ---- numeric family conversions ----
    // Normalize the value to BigDecimal first when the source is any numeric type,
    // then project to the target type.
    BigDecimal numericValue = toNumeric(value, columnType);
    if (numericValue != null) {
      Object result = fromNumeric(numericValue, registeredType);
      if (result != null) {
        return result;
      }
    }

    // ---- date/time family: keep value as-is, getXXX methods handle the conversion ----
    if (isDateTimeType(columnType) && isDateTimeType(registeredType)) {
      return value;
    }

    // No compatible conversion found
    throw new PSQLException("incompatible type", PSQLState.DATA_TYPE_MISMATCH);
  }

  /** Returns true if the SQL type belongs to the string/character family. */
  private static boolean isStringType(int sqlType) {
    return sqlType == Types.CHAR || sqlType == Types.VARCHAR || sqlType == Types.LONGVARCHAR
        || sqlType == Types.NCHAR || sqlType == Types.NVARCHAR || sqlType == Types.LONGNVARCHAR;
  }

  /** Returns true if the SQL type belongs to the date/time family. */
  private static boolean isDateTimeType(int sqlType) {
    return sqlType == Types.DATE || sqlType == Types.TIME || sqlType == Types.TIMESTAMP
        || sqlType == Types.TIMESTAMP_WITH_TIMEZONE || sqlType == Types.TIME_WITH_TIMEZONE;
  }

  /**
   * Tries to represent a numeric DB value as BigDecimal.
   * Returns null if the column type is not a numeric family type.
   */
  private static @Nullable BigDecimal toNumeric(@Nullable Object value, int columnType) {
    if (value == null) {
      return null;
    }
    switch (columnType) {
      case Types.SMALLINT:
      case Types.INTEGER:
        return BigDecimal.valueOf(((Number) value).longValue());
      case Types.BIGINT:
        return (value instanceof BigDecimal)
            ? (BigDecimal) value
            : BigDecimal.valueOf((Long) value);
      case Types.NUMERIC:
      case Types.DECIMAL:
        return (BigDecimal) value;
      case Types.REAL:
      case Types.FLOAT:
        return BigDecimal.valueOf(((Float) value).doubleValue());
      case Types.DOUBLE:
        return BigDecimal.valueOf((Double) value);
      default:
        return null;
    }
  }

  /**
   * Projects a BigDecimal to the target registered SQL type.
   * Returns null if the target type is not a numeric family type.
   */
  private static @Nullable Object fromNumeric(BigDecimal bd, int registeredType) {
    switch (registeredType) {
      case Types.SMALLINT:
      case Types.INTEGER:
        return bd.intValue();
      case Types.BIGINT:
        return bd.longValue();
      case Types.NUMERIC:
      case Types.DECIMAL:
        return bd;
      case Types.REAL:
      case Types.FLOAT:
        return bd.floatValue();
      case Types.DOUBLE:
        return bd.doubleValue();
      case Types.BIT:
      case Types.BOOLEAN:
        return bd.intValue() != 0;
      default:
        return null;
    }
  }

  /**
   * Parses a string value into the target registered SQL type.
   * Falls back to the original string if parsing is not applicable or fails.
   */
  private static @Nullable Object parseStringToType(String strVal, int registeredType) {
    try {
      switch (registeredType) {
        case Types.SMALLINT:
        case Types.INTEGER:
          return Integer.parseInt(strVal);
        case Types.BIGINT:
          return Long.parseLong(strVal);
        case Types.NUMERIC:
        case Types.DECIMAL:
          return new BigDecimal(strVal);
        case Types.REAL:
        case Types.FLOAT:
          return Float.parseFloat(strVal);
        case Types.DOUBLE:
          return Double.parseDouble(strVal);
        case Types.BIT:
        case Types.BOOLEAN:
          return Boolean.parseBoolean(strVal);
        /* POLAR DIFF: Parse date/time strings instead of returning raw String.
         * When the DB column type is VARCHAR but the user registered DATE/TIME/TIMESTAMP,
         * returning the raw String causes ClassCastException in getDate()/getTimestamp().
         * Parse to the proper java.sql type here so the accessor can safely cast it.
         */
        case Types.DATE:
          return java.sql.Date.valueOf(strVal.trim());
        case Types.TIME:
          return java.sql.Time.valueOf(strVal.trim());
        case Types.TIMESTAMP:
          // Timestamp.valueOf() requires "yyyy-MM-dd HH:mm:ss[.nnnnnnnnn]".
          // A date-only string ("yyyy-MM-dd") is valid: interpret as midnight.
          String ts = strVal.trim();
          if (ts.length() == 10 && ts.charAt(4) == '-') {
            ts = ts + " 00:00:00";
          }
          return java.sql.Timestamp.valueOf(ts);
        /* POLAR DIFF end */
        default:
          return strVal;
      }
    } catch (NumberFormatException e) {
      // If parsing fails, return the original string value
      return strVal;
    } catch (IllegalArgumentException e) {
      // If date/time parsing fails, return the original string value
      return strVal;
    }
  }

  /**
   * POLAR: Wraps a String value returned by the DB (for a DO block ARRAY OUT parameter) into
   * a PgArray so that getArray() can safely cast it.
   *
   * <p>When typeName is provided (e.g. "tab_fat_client_form"), the OID is resolved from the type
   * registry. If the OID cannot be resolved, Oid.UNSPECIFIED is used so the caller can still
   * parse the string representation.
   */
  private java.sql.Array buildPgArrayFromString(String fieldString,
      @Nullable String typeName) throws PSQLException {
    int oid = Oid.UNSPECIFIED;
    if (typeName != null && !typeName.isEmpty()) {
      try {
        oid = connection.getTypeInfo().getPGType(typeName.toLowerCase(Locale.ROOT));
        if (oid == Oid.UNSPECIFIED) {
          oid = connection.getTypeInfo().getPGType(typeName);
        }
      } catch (SQLException e) {
        // ignore: use UNSPECIFIED OID, PgArray will do its best to parse the string
      }
    }
    try {
      return new PgArray(connection, oid, fieldString);
    } catch (SQLException e) {
      throw new PSQLException(
          GT.tr("Could not build Array from String value for type {0}", typeName),
          PSQLState.DATA_TYPE_MISMATCH, e);
    }
  }

  /**
   * POLAR: Wraps a String value returned by the DB (for a DO block composite/STRUCT OUT parameter)
   * into a PGobject so that getObject() returns a usable value instead of a raw String.
   *
   * <p>When typeName is provided (e.g. "cis.rec_fat_client_form"), it is set as the PGobject type.
   * The string value (PostgreSQL composite literal, e.g. "(3001,FORM-X,99,AnswerX,2025-07-01)")
   * is stored as the PGobject value.
   */
  private PGobject buildPgObjectFromString(String fieldString,
      @Nullable String typeName) throws PSQLException {
    try {
      PGobject obj = new PGobject();
      obj.setType(typeName != null && !typeName.isEmpty() ? typeName : "record");
      obj.setValue(fieldString);
      return obj;
    } catch (SQLException e) {
      throw new PSQLException(
          GT.tr("Could not build composite type from String value for type {0}", typeName),
          PSQLState.DATA_TYPE_MISMATCH, e);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Before executing a stored procedure call you must explicitly call registerOutParameter to
   * register the java.sql.Type of each out parameter.</p>
   *
   * <p>Note: When reading the value of an out parameter, you must use the getXXX method whose Java
   * type XXX corresponds to the parameter's registered SQL type.</p>
   *
   * <p>ONLY 1 RETURN PARAMETER if {?= call ..} syntax is used</p>
   *
   * @param parameterIndex the first parameter is 1, the second is 2,...
   * @param sqlType SQL type code defined by java.sql.Types; for parameters of type Numeric or
   *        Decimal use the version of registerOutParameter that accepts a scale value
   * @throws SQLException if a database-access error occurs.
   */
  @Override
  public void registerOutParameter(@Positive int parameterIndex, int sqlType)
      throws SQLException {
    doRegisterOutParameter(parameterIndex, sqlType, null);
  }

  /**
   * Core implementation shared by all registerOutParameter overloads.
   *
   * <p>Normalizes the SQL type, resolves the database OID (using {@code typeName} for custom types
   * such as VARRAY, or falling back to the SQL type mapping when {@code typeName} is null/empty),
   * and records the parameter registration state.
   *
   * @param parameterIndex 1-based parameter index
   * @param sqlType        SQL type code from {@link java.sql.Types}
   * @param typeName       optional custom type name (e.g. "ACTOR_NAME_ARRAY"); may be null
   * @throws SQLException if the statement is not a function call or the connection is closed
   */
  private void doRegisterOutParameter(@Positive int parameterIndex, int sqlType,
      @Nullable String typeName) throws SQLException {
    checkClosed();
    switch (sqlType) {
      case Types.TINYINT:
        // we don't have a TINYINT type use SMALLINT
        sqlType = Types.SMALLINT;
        break;
      case Types.LONGVARCHAR:
        sqlType = Types.VARCHAR;
        break;
      case Types.DECIMAL:
        sqlType = Types.NUMERIC;
        break;
      case Types.FLOAT:
        // float is the same as double
        sqlType = Types.DOUBLE;
        break;
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
        sqlType = Types.BINARY;
        break;
      case Types.BOOLEAN:
        sqlType = Types.BIT;
        break;
      /* POLAR DIFF: map date to timestamp, but remember the original type */
      case Types.DATE:
        if (connection.isMapDateToTimestamp()) {
          int[] userReg = this.userRegisteredType;
          if (userReg != null) {
            userReg[parameterIndex - 1] = Types.DATE;
          }
          sqlType = Types.TIMESTAMP;
        }
        break;
      case -10: // OracleTypes.cursor
        sqlType = Types.REF_CURSOR;
        break;
      /* POLAR DIFF end */
      default:
        break;
    }
    int[] functionReturnType = this.functionReturnType;
    int[] testReturn = this.testReturn;
    if (!isFunction || functionReturnType == null || testReturn == null) {
      throw new PSQLException(
          GT.tr(
              "This statement does not declare an OUT parameter.  Use '{' ?= call ... '}' to declare one."),
          PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);
    }

    // POLAR: When typeName is provided (e.g. VARRAY or custom array type), look up its OID by
    // name so that the wire protocol uses the correct type OID for the OUT parameter slot.
    int oid;
    if (typeName != null && !typeName.isEmpty()) {
      oid = connection.getTypeInfo().getPGType(typeName.toLowerCase(Locale.ROOT));
      if (oid == Oid.UNSPECIFIED) {
        oid = connection.getTypeInfo().getPGType(typeName.toUpperCase(Locale.ROOT));
      }
      if (oid == Oid.UNSPECIFIED) {
        oid = connection.getTypeInfo().getPGType(typeName);
      }
      if (oid == Oid.UNSPECIFIED) {
        // last resort: derive oid from sqlType
        Integer derived = connection.getTypeInfo().getOidFromSqlType(sqlType);
        oid = derived != null ? derived : Oid.UNSPECIFIED;
      }
    } else {
      /* POLAR: get oid from sqlType */
      Integer derived = connection.getTypeInfo().getOidFromSqlType(sqlType);
      oid = derived != null ? derived : Oid.UNSPECIFIED;
    }

    /* POLAR: For DO anonymous blocks, $N parameters are bound directly in the SQL;
     * we do not call preparedParameters.registerOutParameter since there is no
     * corresponding positional ? placeholder. We only record the expected return type.
     * Similarly, for sequence pseudocolumns (seq.nextval/seq.currval), the SELECT has
     * no ? placeholder—skip parameter registration and just record the return type. */
    if (!isDoBlock && !isSequencePseudocol) {
      preparedParameters.registerOutParameter(parameterIndex, oid);
    }
    // functionReturnType contains the user supplied value to check
    // testReturn contains a modified version to make it easier to
    // check the getXXX methods..
    functionReturnType[parameterIndex - 1] = sqlType;
    testReturn[parameterIndex - 1] = sqlType;

    // POLAR: store type name for ARRAY OUT parameters (needed to reconstruct PgArray from String)
    String @Nullable [] functionReturnTypeName = this.functionReturnTypeName;
    if (functionReturnTypeName != null) {
      functionReturnTypeName[parameterIndex - 1] = typeName;
    }

    if (functionReturnType[parameterIndex - 1] == Types.CHAR
        || functionReturnType[parameterIndex - 1] == Types.LONGVARCHAR) {
      testReturn[parameterIndex - 1] = Types.VARCHAR;
    } else if (functionReturnType[parameterIndex - 1] == Types.FLOAT) {
      testReturn[parameterIndex - 1] = Types.REAL; // changes to streamline later error checking
    }
    returnTypeSet = true;
  }

  public boolean wasNull() throws SQLException {
    if (lastIndex == 0 || callResult == null) {
      throw new PSQLException(GT.tr("wasNull cannot be call before fetching a result."),
          PSQLState.OBJECT_NOT_IN_STATE);
    }

    // check to see if the last access threw an exception
    return callResult[lastIndex - 1] == null;
  }

  public @Nullable String getString(@Positive int parameterIndex) throws SQLException {
    Object result = getCallResult(parameterIndex);
    if (result == null) {
      return null;
    }

    // getString() should be able to convert from most types to String
    // This is more flexible than strict type checking
    int testReturn = this.testReturn != null ? this.testReturn[parameterIndex - 1] : -1;

    // For numeric types, convert to string
    if (testReturn == Types.INTEGER || testReturn == Types.SMALLINT
        || testReturn == Types.BIGINT || testReturn == Types.NUMERIC
        || testReturn == Types.DECIMAL || testReturn == Types.DOUBLE
        || testReturn == Types.REAL || testReturn == Types.FLOAT) {
      return result.toString();
    }

    // For VARCHAR, CHAR and other string types, direct cast
    if (testReturn == Types.VARCHAR || testReturn == Types.CHAR
        || testReturn == Types.LONGVARCHAR) {
      return (String) result;
    }

    // For other types, try to convert to string
    return result.toString();
  }

  public boolean getBoolean(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.BIT, "Boolean");
    if (result == null) {
      return false;
    }
    return BooleanTypeUtil.castToBoolean(result);
  }

  public byte getByte(@Positive int parameterIndex) throws SQLException {
    /* POLAR: allow getByte from number */
    int testReturn = this.testReturn != null ? this.testReturn[parameterIndex - 1] : -1;

    if (testReturn == Types.NUMERIC) {
      Object result = callResult != null ? callResult[parameterIndex - 1] : null;
      if (result == null) {
        return 0;
      }
      return ((BigDecimal) result).byteValue();
    }

    // fake tiny int with smallint
    Object result = checkIndex(parameterIndex, Types.SMALLINT, "Byte");

    if (result == null) {
      return 0;
    }

    return ((Integer) result).byteValue();

  }

  public short getShort(@Positive int parameterIndex) throws SQLException {
    /* POLAR: allow getShort from number */
    int testReturn = this.testReturn != null ? this.testReturn[parameterIndex - 1] : -1;

    if (testReturn == Types.NUMERIC) {
      Object result = callResult != null ? callResult[parameterIndex - 1] : null;
      if (result == null) {
        return 0;
      }
      return ((BigDecimal) result).shortValue();
    }

    Object result = checkIndex(parameterIndex, Types.SMALLINT, "Short");
    if (result == null) {
      return 0;
    }
    return ((Integer) result).shortValue();
  }

  public int getInt(@Positive int parameterIndex) throws SQLException {

    /* POLAR: allow getInt from number */
    int testReturn = this.testReturn != null ? this.testReturn[parameterIndex - 1] : -1;

    if (testReturn == Types.NUMERIC) {
      Object result = callResult != null ? callResult[parameterIndex - 1] : null;
      lastIndex = parameterIndex;
      if (result == null) {
        return 0;
      }
      return ((BigDecimal) result).intValue();
    }

    Object result = checkIndex(parameterIndex, Types.INTEGER, "Int");
    if (result == null) {
      return 0;
    }

    return (Integer) result;
  }

  public long getLong(@Positive int parameterIndex) throws SQLException {
    /* POLAR: allow getLong from number */
    int testReturn = this.testReturn != null ? this.testReturn[parameterIndex - 1] : -1;

    if (testReturn == Types.NUMERIC) {
      Object result = callResult != null ? callResult[parameterIndex - 1] : null;
      if (result == null) {
        return 0;
      }
      return ((BigDecimal) result).longValue();
    }

    Object result = checkIndex(parameterIndex, Types.BIGINT, "Long");
    if (result == null) {
      return 0;
    }

    /* POLAR: handle BigDecimal returned for BIGINT */
    if (result instanceof BigDecimal) {
      return ((BigDecimal) result).longValue();
    }

    return (Long) result;
  }

  public float getFloat(@Positive int parameterIndex) throws SQLException {
    /* POLAR: allow getFloat from number */
    int testReturn = this.testReturn != null ? this.testReturn[parameterIndex - 1] : -1;

    if (testReturn == Types.NUMERIC) {
      Object result = callResult != null ? callResult[parameterIndex - 1] : null;
      if (result == null) {
        return 0;
      }
      return ((BigDecimal) result).floatValue();
    }

    Object result = checkIndex(parameterIndex, Types.REAL, "Float");
    if (result == null) {
      return 0;
    }

    return (Float) result;
  }

  public double getDouble(@Positive int parameterIndex) throws SQLException {
    /* POLAR: allow getDouble from number */
    int testReturn = this.testReturn != null ? this.testReturn[parameterIndex - 1] : -1;

    if (testReturn == Types.NUMERIC) {
      Object result = callResult != null ? callResult[parameterIndex - 1] : null;
      if (result == null) {
        return 0;
      }
      return ((BigDecimal) result).doubleValue();
    }

    Object result = checkIndex(parameterIndex, Types.DOUBLE, "Double");
    if (result == null) {
      return 0;
    }

    return (Double) result;
  }

  public @Nullable BigDecimal getBigDecimal(@Positive int parameterIndex, int scale) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.NUMERIC, "BigDecimal");
    return (@Nullable BigDecimal) result;
  }

  public byte @Nullable [] getBytes(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.VARBINARY, Types.BINARY, "Bytes");
    return ((byte @Nullable []) result);
  }

  public java.sql.@Nullable Date getDate(@Positive int parameterIndex) throws SQLException {
    /* POLAR DIFF: map date to timestamp */
    if (connection.isMapDateToTimestamp()) {
      Timestamp result = getTimestamp(parameterIndex);

      if (result == null) {
        return null;
      }

      return new java.sql.Date(result.getTime());
    }
    /* POLAR DIFF end */
    Object result = checkIndex(parameterIndex, Types.DATE, "Date");
    if (result == null) {
      return null;
    }
    /* POLAR DIFF: guard against String stored when DB column type was VARCHAR */
    if (result instanceof String) {
      return getTimestampUtils().toDate(null, (String) result);
    }
    /* POLAR DIFF end */
    return (java.sql.Date) result;
  }

  public java.sql.@Nullable Time getTime(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.TIME, "Time");
    return (java.sql.@Nullable Time) result;
  }

  public java.sql.@Nullable Timestamp getTimestamp(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.TIMESTAMP, "Timestamp");
    if (result == null) {
      return null;
    }
    /* POLAR DIFF: When callResult stores a String (e.g. DB column type was VARCHAR but user
     * registered TIMESTAMP), a direct cast throws ClassCastException. Parse via TimestampUtils.
     */
    if (result instanceof String) {
      return getTimestampUtils().toTimestamp(null, (String) result);
    }
    /* POLAR DIFF end */
    return (java.sql.Timestamp) result;
  }

  public @Nullable Object getObject(@Positive int parameterIndex) throws SQLException {
    Object result = getCallResult(parameterIndex);
    /* POLAR DIFF: When user explicitly registered Types.DATE, convert Timestamp to java.sql.Date.
     * For table SELECT queries, ORADATE getObject() still returns Timestamp (Oracle behavior). */
    int[] userReg = this.userRegisteredType;
    if (result instanceof Timestamp && userReg != null
        && parameterIndex > 0 && parameterIndex <= userReg.length
        && userReg[parameterIndex - 1] == Types.DATE) {
      return new java.sql.Date(((Timestamp) result).getTime());
    }
    /* POLAR DIFF end */
    return result;
  }

  /**
   * helperfunction for the getXXX calls to check isFunction and index == 1 Compare BOTH type fields
   * against the return type.
   *
   * @param parameterIndex parameter index (1-based)
   * @param type1 type 1
   * @param type2 type 2
   * @param getName getter name
   * @throws SQLException if something goes wrong
   */
  protected @Nullable Object checkIndex(@Positive int parameterIndex, int type1, int type2, String getName)
      throws SQLException {
    Object result = getCallResult(parameterIndex);
    int testReturn = this.testReturn != null ? this.testReturn[parameterIndex - 1] : -1;
    if (type1 != testReturn && type2 != testReturn) {
      throw new PSQLException(
          GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2}) was made.",
                  "java.sql.Types=" + testReturn, getName,
                  "java.sql.Types=" + type1),
          PSQLState.MOST_SPECIFIC_TYPE_DOES_NOT_MATCH);
    }
    return result;
  }

  /**
   * Helper function for the getXXX calls to check isFunction and index == 1.
   *
   * @param parameterIndex parameter index (1-based)
   * @param type type
   * @param getName getter name
   * @throws SQLException if given index is not valid
   */
  protected @Nullable Object checkIndex(@Positive int parameterIndex,
      int type, String getName) throws SQLException {
    Object result = getCallResult(parameterIndex);
    int testReturn = this.testReturn != null ? this.testReturn[parameterIndex - 1] : -1;
    if (type != testReturn) {
      throw new PSQLException(
          GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2}) was made.",
              "java.sql.Types=" + testReturn, getName,
                  "java.sql.Types=" + type),
          PSQLState.MOST_SPECIFIC_TYPE_DOES_NOT_MATCH);
    }
    return result;
  }

  private @Nullable Object getCallResult(@Positive int parameterIndex) throws SQLException {
    checkClosed();

    if (!isFunction) {
      throw new PSQLException(
          GT.tr(
              "A CallableStatement was declared, but no call to registerOutParameter(1, <some type>) was made."),
          PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);
    }

    if (!returnTypeSet) {
      throw new PSQLException(GT.tr("No function outputs were registered."),
          PSQLState.OBJECT_NOT_IN_STATE);
    }

    @Nullable Object @Nullable [] callResult = this.callResult;
    if (callResult == null) {
      throw new PSQLException(
          GT.tr("Results cannot be retrieved from a CallableStatement before it is executed."),
          PSQLState.NO_DATA);
    }

    lastIndex = parameterIndex;
    return callResult[parameterIndex - 1];
  }

  @Override
  protected BatchResultHandler createBatchHandler(Query[] queries,
      @Nullable ParameterList[] parameterLists) {
    return new CallableBatchResultHandler(this, queries, parameterLists);
  }

  public java.sql.@Nullable Array getArray(int i) throws SQLException {
    Object result = checkIndex(i, Types.ARRAY, "Array");
    return (Array) result;
  }

  public java.math.@Nullable BigDecimal getBigDecimal(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.NUMERIC, "BigDecimal");
    return ((BigDecimal) result);
  }

  public @Nullable Blob getBlob(int i) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBlob(int)");
  }

  public @Nullable Clob getClob(int i) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getClob(int)");
  }

  public @Nullable Object getObjectImpl(int i, @Nullable Map<String, Class<?>> map) throws SQLException {
    if (map == null || map.isEmpty()) {
      return getObject(i);
    }
    throw Driver.notImplemented(this.getClass(), "getObjectImpl(int,Map)");
  }

  public @Nullable Ref getRef(int i) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRef(int)");
  }

  public java.sql.@Nullable Date getDate(int i, java.util.@Nullable Calendar cal) throws SQLException {
    Object result = checkIndex(i, Types.DATE, "Date");

    if (result == null) {
      return null;
    }

    String value = result.toString();
    return getTimestampUtils().toDate(cal, value);
  }

  public @Nullable Time getTime(int i, java.util.@Nullable Calendar cal) throws SQLException {
    Object result = checkIndex(i, Types.TIME, "Time");

    if (result == null) {
      return null;
    }

    String value = result.toString();
    return getTimestampUtils().toTime(cal, value);
  }

  public @Nullable Timestamp getTimestamp(int i, java.util.@Nullable Calendar cal) throws SQLException {
    Object result = checkIndex(i, Types.TIMESTAMP, "Timestamp");

    if (result == null) {
      return null;
    }

    String value = result.toString();
    return getTimestampUtils().toTimestamp(cal, value);
  }

  public void registerOutParameter(@Positive int parameterIndex, int sqlType, String typeName)
      throws SQLException {
    doRegisterOutParameter(parameterIndex, sqlType, typeName);
  }

  public void setObject(String parameterName, @Nullable Object x, java.sql.SQLType targetSqlType,
      int scaleOrLength) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject");
  }

  public void setObject(String parameterName, @Nullable Object x, java.sql.SQLType targetSqlType)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject");
  }

  public void registerOutParameter(@Positive int parameterIndex, java.sql.SQLType sqlType)
      throws SQLException {
    registerOutParameter(parameterIndex, sqlType.getVendorTypeNumber());
  }

  public void registerOutParameter(@Positive int parameterIndex, java.sql.SQLType sqlType, int scale)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  public void registerOutParameter(@Positive int parameterIndex, java.sql.SQLType sqlType, String typeName)
      throws SQLException {
    registerOutParameter(parameterIndex, sqlType.getVendorTypeNumber(), typeName);
  }

  public void registerOutParameter(String parameterName, java.sql.SQLType sqlType)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  public void registerOutParameter(String parameterName, java.sql.SQLType sqlType, int scale)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  public void registerOutParameter(String parameterName, java.sql.SQLType sqlType, String typeName)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  public @Nullable RowId getRowId(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRowId(int)");
  }

  public @Nullable RowId getRowId(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRowId(String)");
  }

  public void setRowId(String parameterName, @Nullable RowId x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setRowId(String, RowId)");
  }

  public void setNString(String parameterName, @Nullable String value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNString(String, String)");
  }

  public void setNCharacterStream(String parameterName, @Nullable Reader value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNCharacterStream(String, Reader, long)");
  }

  public void setNCharacterStream(String parameterName, @Nullable Reader value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNCharacterStream(String, Reader)");
  }

  public void setCharacterStream(String parameterName, @Nullable Reader value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setCharacterStream(String, Reader, long)");
  }

  public void setCharacterStream(String parameterName, @Nullable Reader value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setCharacterStream(String, Reader)");
  }

  public void setBinaryStream(String parameterName, @Nullable InputStream value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBinaryStream(String, InputStream, long)");
  }

  public void setBinaryStream(String parameterName, @Nullable InputStream value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBinaryStream(String, InputStream)");
  }

  public void setAsciiStream(String parameterName, @Nullable InputStream value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStream(String, InputStream, long)");
  }

  public void setAsciiStream(String parameterName, @Nullable InputStream value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStream(String, InputStream)");
  }

  public void setNClob(String parameterName, @Nullable NClob value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(String, NClob)");
  }

  public void setClob(String parameterName, @Nullable Reader reader, long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClob(String, Reader, long)");
  }

  public void setClob(String parameterName, @Nullable Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClob(String, Reader)");
  }

  public void setBlob(String parameterName, @Nullable InputStream inputStream, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBlob(String, InputStream, long)");
  }

  public void setBlob(String parameterName, @Nullable InputStream inputStream) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBlob(String, InputStream)");
  }

  public void setBlob(String parameterName, @Nullable Blob x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBlob(String, Blob)");
  }

  public void setClob(String parameterName, @Nullable Clob x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClob(String, Clob)");
  }

  public void setNClob(String parameterName, @Nullable Reader reader, long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(String, Reader, long)");
  }

  public void setNClob(String parameterName, @Nullable Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(String, Reader)");
  }

  public @Nullable NClob getNClob(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNClob(int)");
  }

  public @Nullable NClob getNClob(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNClob(String)");
  }

  public void setSQLXML(String parameterName, @Nullable SQLXML xmlObject) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setSQLXML(String, SQLXML)");
  }

  public @Nullable SQLXML getSQLXML(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.SQLXML, "SQLXML");
    return (SQLXML) result;
  }

  public @Nullable SQLXML getSQLXML(String parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getSQLXML(String)");
  }

  public String getNString(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.VARCHAR, "String");
    return (String) result;
  }

  public @Nullable String getNString(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNString(String)");
  }

  public @Nullable Reader getNCharacterStream(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNCharacterStream(int)");
  }

  public @Nullable Reader getNCharacterStream(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNCharacterStream(String)");
  }

  public @Nullable Reader getCharacterStream(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getCharacterStream(int)");
  }

  public @Nullable Reader getCharacterStream(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getCharacterStream(String)");
  }

  public <T> @Nullable T getObject(@Positive int parameterIndex, Class<T> type)
      throws SQLException {
    if (type == ResultSet.class) {
      return type.cast(getObject(parameterIndex));
    }
    throw new PSQLException(GT.tr("Unsupported type conversion to {1}.", type),
            PSQLState.INVALID_PARAMETER_VALUE);
  }

  public <T> @Nullable T getObject(String parameterName, Class<T> type) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getObject(String, Class<T>)");
  }

  public void registerOutParameter(String parameterName, int sqlType) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter(String,int)");
  }

  public void registerOutParameter(String parameterName, int sqlType, int scale)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter(String,int,int)");
  }

  public void registerOutParameter(String parameterName, int sqlType, String typeName)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter(String,int,String)");
  }

  public java.net.@Nullable URL getURL(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getURL(String)");
  }

  public void setURL(String parameterName, java.net.@Nullable URL val) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setURL(String,URL)");
  }

  public void setNull(String parameterName, int sqlType) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNull(String,int)");
  }

  public void setBoolean(String parameterName, boolean x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBoolean(String,boolean)");
  }

  public void setByte(String parameterName, byte x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setByte(String,byte)");
  }

  public void setShort(String parameterName, short x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setShort(String,short)");
  }

  public void setInt(String parameterName, int x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setInt(String,int)");
  }

  public void setLong(String parameterName, long x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setLong(String,long)");
  }

  public void setFloat(String parameterName, float x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setFloat(String,float)");
  }

  public void setDouble(String parameterName, double x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setDouble(String,double)");
  }

  public void setBigDecimal(String parameterName, @Nullable BigDecimal x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBigDecimal(String,BigDecimal)");
  }

  public void setString(String parameterName, @Nullable String x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setString(String,String)");
  }

  public void setBytes(String parameterName, byte @Nullable [] x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBytes(String,byte)");
  }

  public void setDate(String parameterName, java.sql.@Nullable Date x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setDate(String,Date)");
  }

  public void setTime(String parameterName, @Nullable Time x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setTime(String,Time)");
  }

  public void setTimestamp(String parameterName, @Nullable Timestamp x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setTimestamp(String,Timestamp)");
  }

  public void setAsciiStream(String parameterName, @Nullable InputStream x, int length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStream(String,InputStream,int)");
  }

  public void setBinaryStream(String parameterName, @Nullable InputStream x, int length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBinaryStream(String,InputStream,int)");
  }

  public void setObject(String parameterName, @Nullable Object x, int targetSqlType, int scale)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject(String,Object,int,int)");
  }

  public void setObject(String parameterName, @Nullable Object x, int targetSqlType) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject(String,Object,int)");
  }

  public void setObject(String parameterName, @Nullable Object x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject(String,Object)");
  }

  public void setCharacterStream(String parameterName, @Nullable Reader reader, int length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setCharacterStream(String,Reader,int)");
  }

  public void setDate(String parameterName, java.sql.@Nullable Date x, @Nullable Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setDate(String,Date,Calendar)");
  }

  public void setTime(String parameterName, @Nullable Time x, @Nullable Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setTime(String,Time,Calendar)");
  }

  public void setTimestamp(String parameterName, @Nullable Timestamp x, @Nullable Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setTimestamp(String,Timestamp,Calendar)");
  }

  public void setNull(String parameterName, int sqlType, String typeName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNull(String,int,String)");
  }

  public @Nullable String getString(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getString(String)");
  }

  public boolean getBoolean(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBoolean(String)");
  }

  public byte getByte(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getByte(String)");
  }

  public short getShort(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getShort(String)");
  }

  public int getInt(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getInt(String)");
  }

  public long getLong(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getLong(String)");
  }

  public float getFloat(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getFloat(String)");
  }

  public double getDouble(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getDouble(String)");
  }

  public byte @Nullable [] getBytes(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBytes(String)");
  }

  public java.sql.@Nullable Date getDate(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getDate(String)");
  }

  public Time getTime(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getTime(String)");
  }

  public @Nullable Timestamp getTimestamp(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getTimestamp(String)");
  }

  public @Nullable Object getObject(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getObject(String)");
  }

  public @Nullable BigDecimal getBigDecimal(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBigDecimal(String)");
  }

  public @Nullable Object getObjectImpl(String parameterName, @Nullable Map<String, Class<?>> map) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getObject(String,Map)");
  }

  public @Nullable Ref getRef(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRef(String)");
  }

  public @Nullable Blob getBlob(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBlob(String)");
  }

  public @Nullable Clob getClob(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getClob(String)");
  }

  public @Nullable Array getArray(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getArray(String)");
  }

  public java.sql.@Nullable Date getDate(String parameterName, @Nullable Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getDate(String,Calendar)");
  }

  public @Nullable Time getTime(String parameterName, @Nullable Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getTime(String,Calendar)");
  }

  public @Nullable Timestamp getTimestamp(String parameterName, @Nullable Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getTimestamp(String,Calendar)");
  }

  public java.net.@Nullable URL getURL(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getURL(String)");
  }

  public void registerOutParameter(@Positive int parameterIndex, int sqlType, int scale) throws SQLException {
    // ignore scale for now
    registerOutParameter(parameterIndex, sqlType);
  }
}
