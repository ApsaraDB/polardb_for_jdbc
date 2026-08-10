/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2;

import com.aliyun.polardb2.copy.CopyManager;
import com.aliyun.polardb2.fastpath.Fastpath;
import com.aliyun.polardb2.jdbc.AutoSave;
import com.aliyun.polardb2.jdbc.PreferQueryMode;
import com.aliyun.polardb2.largeobject.LargeObjectManager;
import com.aliyun.polardb2.replication.PGReplicationConnection;
import com.aliyun.polardb2.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * This interface defines the public PostgreSQL extensions to java.sql.Connection. All Connections
 * returned by the PostgreSQL driver implement PGConnection.
 */
public interface PGConnection {

  /**
   * Creates an {@link Array} wrapping <i>elements</i>. This is similar to
   * {@link java.sql.Connection#createArrayOf(String, Object[])}, but also
   * provides support for primitive arrays.
   *
   * @param typeName
   *          The SQL name of the type to map the <i>elements</i> to.
   *          Must not be {@code null}.
   * @param elements
   *          The array of objects to map. A {@code null} value will result in
   *          an {@link Array} representing {@code null}.
   * @return An {@link Array} wrapping <i>elements</i>.
   * @throws SQLException
   *           If for some reason the array cannot be created.
   * @see java.sql.Connection#createArrayOf(String, Object[])
   */
  Array createArrayOf(String typeName, @Nullable Object elements) throws SQLException;

  /**
   * POLAR: Return the ordered field descriptions of a composite (record) type.
   *
   * <p>The standard JDBC API carries only positionally ordered values across
   * {@code createStruct}/{@code getAttributes}, so neither the driver nor the
   * application can reorder fields by name without knowing both sides' field
   * definitions. This method exposes the database side of that information: the
   * fields of {@code typeName} in their server-defined order
   * ({@code pg_attribute.attnum}), so application frameworks can combine it with
   * Java reflection on their bean classes, match fields by name, and produce a
   * value array ordered according to the database schema - for both writes
   * ({@code createStruct}) and reads ({@code Struct.getAttributes}).</p>
   *
   * <p>If {@code typeName} is an Oracle synonym, it is resolved to the actual
   * type via {@code all_synonyms} before the metadata is queried. Results are
   * cached per connection.</p>
   *
   * @param typeName
   *          The SQL name of the composite type (optionally schema-qualified),
   *          or a synonym referring to one. Must not be {@code null}.
   * @return the fields of the composite type in server-defined order, or
   *         {@code null} if the type does not exist or is not a composite type.
   * @throws SQLException
   *           If for some reason the metadata cannot be retrieved.
   * @see PGCompositeField
   * @since 42.5.7.0.15
   */
  @Nullable List<PGCompositeField> getCompositeTypeFields(String typeName) throws SQLException;

  /**
   * This method returns any notifications that have been received since the last call to this
   * method. Returns null if there have been no notifications.
   *
   * @return notifications that have been received
   * @throws SQLException if something wrong happens
   * @since 7.3
   */
  PGNotification[] getNotifications() throws SQLException;

  /**
   * This method returns any notifications that have been received since the last call to this
   * method. Returns null if there have been no notifications. A timeout can be specified so the
   * driver waits for notifications.
   *
   * @param timeoutMillis when 0, blocks forever. when &gt; 0, blocks up to the specified number of millis
   *        or until at least one notification has been received. If more than one notification is
   *        about to be received, these will be returned in one batch.
   * @return notifications that have been received
   * @throws SQLException if something wrong happens
   * @since 43
   */
  PGNotification[] getNotifications(int timeoutMillis) throws SQLException;

  /**
   * This returns the COPY API for the current connection.
   *
   * @return COPY API for the current connection
   * @throws SQLException if something wrong happens
   * @since 8.4
   */
  CopyManager getCopyAPI() throws SQLException;

  /**
   * This returns the LargeObject API for the current connection.
   *
   * @return LargeObject API for the current connection
   * @throws SQLException if something wrong happens
   * @since 7.3
   */
  LargeObjectManager getLargeObjectAPI() throws SQLException;

  /**
   * This returns the Fastpath API for the current connection.
   *
   * @return Fastpath API for the current connection
   * @throws SQLException if something wrong happens
   * @since 7.3
   * @deprecated This API is somewhat obsolete, as one may achieve similar performance
   *         and greater functionality by setting up a prepared statement to define
   *         the function call. Then, executing the statement with binary transmission of parameters
   *         and results substitutes for a fast-path function call.
   */
  @Deprecated
  Fastpath getFastpathAPI() throws SQLException;

  /**
   * This allows client code to add a handler for one of com.aliyun.polardb2's more unique data types. It
   * is approximately equivalent to <code>addDataType(type, Class.forName(name))</code>.
   *
   * @param type JDBC type name
   * @param className class name
   * @throws RuntimeException if the type cannot be registered (class not found, etc).
   * @deprecated As of 8.0, replaced by {@link #addDataType(String, Class)}. This deprecated method
   *             does not work correctly for registering classes that cannot be directly loaded by
   *             the JDBC driver's classloader.
   */
  @Deprecated
  void addDataType(String type, String className);

  /**
   * <p>This allows client code to add a handler for one of com.aliyun.polardb2's more unique data types.</p>
   *
   * <p><b>NOTE:</b> This is not part of JDBC, but an extension.</p>
   *
   * <p>The best way to use this is as follows:</p>
   *
   * <pre>
   * ...
   * ((com.aliyun.polardb2.PGConnection)myconn).addDataType("mytype", my.class.name.class);
   * ...
   * </pre>
   *
   * <p>where myconn is an open Connection to com.aliyun.polardb2.</p>
   *
   * <p>The handling class must extend com.aliyun.polardb2.util.PGobject</p>
   *
   * @param type the PostgreSQL type to register
   * @param klass the class implementing the Java representation of the type; this class must
   *        implement {@link com.aliyun.polardb2.util.PGobject}).
   * @throws SQLException if <code>klass</code> does not implement
   *         {@link com.aliyun.polardb2.util.PGobject}).
   * @see com.aliyun.polardb2.util.PGobject
   * @since 8.0
   */
  void addDataType(String type, Class<? extends PGobject> klass) throws SQLException;

  /**
   * Set the default statement reuse threshold before enabling server-side prepare. See
   * {@link com.aliyun.polardb2.PGStatement#setPrepareThreshold(int)} for details.
   *
   * @param threshold the new threshold
   * @since build 302
   */
  void setPrepareThreshold(int threshold);

  /**
   * Get the default server-side prepare reuse threshold for statements created from this
   * connection.
   *
   * @return the current threshold
   * @since build 302
   */
  int getPrepareThreshold();

  /**
   * Set the default fetch size for statements created from this connection.
   *
   * @param fetchSize new default fetch size
   * @throws SQLException if specified negative <code>fetchSize</code> parameter
   * @see Statement#setFetchSize(int)
   */
  void setDefaultFetchSize(int fetchSize) throws SQLException;

  /**
   * Get the default fetch size for statements created from this connection.
   *
   * @return current state for default fetch size
   * @see PGProperty#DEFAULT_ROW_FETCH_SIZE
   * @see Statement#getFetchSize()
   */
  int getDefaultFetchSize();

  /**
   * Return the process ID (PID) of the backend server process handling this connection.
   *
   * @return PID of backend server process.
   */
  int getBackendPID();

  /**
   * Sends a query cancellation for this connection.
   * @throws SQLException if there are problems cancelling the query
   */
  void cancelQuery() throws SQLException;

  /**
   * Return the given string suitably quoted to be used as an identifier in an SQL statement string.
   * Quotes are added only if necessary (i.e., if the string contains non-identifier characters or
   * would be case-folded). Embedded quotes are properly doubled.
   *
   * @param identifier input identifier
   * @return the escaped identifier
   * @throws SQLException if something goes wrong
   */
  String escapeIdentifier(String identifier) throws SQLException;

  /**
   * Return the given string suitably quoted to be used as a string literal in an SQL statement
   * string. Embedded single-quotes and backslashes are properly doubled. Note that quote_literal
   * returns null on null input.
   *
   * @param literal input literal
   * @return the quoted literal
   * @throws SQLException if something goes wrong
   */
  String escapeLiteral(String literal) throws SQLException;

  /**
   * <p>Returns the query mode for this connection.</p>
   *
   * <p>When running in simple query mode, certain features are not available: callable statements,
   * partial result set fetch, bytea type, etc.</p>
   * <p>The list of supported features is subject to change.</p>
   *
   * @return the preferred query mode
   * @see PreferQueryMode
   */
  PreferQueryMode getPreferQueryMode();

  /**
   * Connection configuration regarding automatic per-query savepoints.
   *
   * @return connection configuration regarding automatic per-query savepoints
   * @see PGProperty#AUTOSAVE
   */
  AutoSave getAutosave();

  /**
   * Configures if connection should use automatic savepoints.
   * @param autoSave connection configuration regarding automatic per-query savepoints
   * @see PGProperty#AUTOSAVE
   */
  void setAutosave(AutoSave autoSave);

  /**
   * @return replication API for the current connection
   */
  PGReplicationConnection getReplicationAPI();

  /**
   * <p>Returns the current values of all parameters reported by the server.</p>
   *
   * <p>PostgreSQL reports values for a subset of parameters (GUCs) to the client
   * at connect-time, then sends update messages whenever the values change
   * during a session. PgJDBC records the latest values and exposes it to client
   * applications via <code>getParameterStatuses()</code>.</p>
   *
   * <p>PgJDBC exposes individual accessors for some of these parameters as
   * listed below. They are more backwards-compatible and should be preferred
   * where possible.</p>
   *
   * <p>Not all parameters are reported, only those marked
   * <code>GUC_REPORT</code> in the source code. The <code>pg_settings</code>
   * view does not expose information about which parameters are reportable.
   * PgJDBC's map will only contain the parameters the server reports values
   * for, so you cannot use this method as a substitute for running a
   * <code>SHOW paramname;</code> or <code>SELECT
   * current_setting('paramname');</code> query for arbitrary parameters.</p>
   *
   * <p>Parameter names are <i>case-insensitive</i> and <i>case-preserving</i>
   * in this map, like in PostgreSQL itself. So <code>DateStyle</code> and
   * <code>datestyle</code> are the same key.</p>
   *
   * <p>
   *  As of PostgreSQL 11 the reportable parameter list, and related PgJDBC
   *  interfaces or assessors, are:
   * </p>
   *
   * <ul>
   *  <li>
   *    <code>application_name</code> -
   *    {@link java.sql.Connection#getClientInfo()},
   *    {@link java.sql.Connection#setClientInfo(java.util.Properties)}
   *    and <code>ApplicationName</code> connection property.
   *  </li>
   *  <li>
   *    <code>client_encoding</code> - PgJDBC always sets this to <code>UTF8</code>.
   *    See <code>allowEncodingChanges</code> connection property.
   *  </li>
   *  <li><code>DateStyle</code> - PgJDBC requires this to always be set to <code>ISO</code></li>
   *  <li><code>standard_conforming_strings</code> - indirectly via {@link #escapeLiteral(String)}</li>
   *  <li>
   *    <code>TimeZone</code> - set from JDK timezone see {@link java.util.TimeZone#getDefault()}
   *    and {@link java.util.TimeZone#setDefault(TimeZone)}
   *  </li>
   *  <li><code>integer_datetimes</code></li>
   *  <li><code>IntervalStyle</code></li>
   *  <li><code>server_encoding</code></li>
   *  <li><code>server_version</code></li>
   *  <li><code>is_superuser</code> </li>
   *  <li><code>session_authorization</code></li>
   * </ul>
   *
   * <p>Note that some PgJDBC operations will change server parameters
   * automatically.</p>
   *
   * @return unmodifiable map of case-insensitive parameter names to parameter values
   * @since 42.2.6
   */
  Map<String,String> getParameterStatuses();

  /**
   * Shorthand for getParameterStatuses().get(...) .
   *
   * @param parameterName case-insensitive parameter name
   * @return parameter value if defined, or null if no parameter known
   * @see #getParameterStatuses
   * @since 42.2.6
   */
  @Nullable String getParameterStatus(String parameterName);

  /**
   * Turn on/off adaptive fetch for connection. Existing statements and resultSets won't be affected
   * by change here.
   *
   * @param adaptiveFetch desired state of adaptive fetch.
   */
  void setAdaptiveFetch(boolean adaptiveFetch);

  /**
   * Get state of adaptive fetch for connection.
   *
   * @return state of adaptive fetch (turned on or off)
   */
  boolean getAdaptiveFetch();
}
