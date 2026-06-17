/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.core;

import com.aliyun.polardb2.PGConnection;
import com.aliyun.polardb2.PGProperty;
import com.aliyun.polardb2.jdbc.FieldMetadata;
import com.aliyun.polardb2.jdbc.TimestampUtils;
import com.aliyun.polardb2.polarora.PolarDriverPrefix;
import com.aliyun.polardb2.util.LruCache;
import com.aliyun.polardb2.xml.PGXmlFactoryFactory;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.checkerframework.dataflow.qual.Pure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.TimerTask;

/**
 * Driver-internal connection interface. Application code should not use this interface.
 */
public interface BaseConnection extends PGConnection, Connection {
  /**
   * Cancel the current query executing on this connection.
   *
   * @throws SQLException if something goes wrong.
   */
  void cancelQuery() throws SQLException;

  /**
   * Execute a SQL query that returns a single resultset. Never causes a new transaction to be
   * started regardless of the autocommit setting.
   *
   * @param s the query to execute
   * @return the (non-null) returned resultset
   * @throws SQLException if something goes wrong.
   */
  ResultSet execSQLQuery(String s) throws SQLException;

  ResultSet execSQLQuery(String s, int resultSetType, int resultSetConcurrency)
      throws SQLException;

  /**
   * Execute a SQL query that does not return results. Never causes a new transaction to be started
   * regardless of the autocommit setting.
   *
   * @param s the query to execute
   * @throws SQLException if something goes wrong.
   */
  void execSQLUpdate(String s) throws SQLException;

  /**
   * Get the QueryExecutor implementation for this connection.
   *
   * @return the (non-null) executor
   */
  QueryExecutor getQueryExecutor();

  /**
   * Internal protocol for work with physical and logical replication. Physical replication available
   * only since PostgreSQL version 9.1. Logical replication available only since PostgreSQL version 9.4.
   *
   * @return not null replication protocol
   */
  ReplicationProtocol getReplicationProtocol();

  /**
   * <p>Construct and return an appropriate object for the given type and value. This only considers
   * the types registered via {@link com.aliyun.polardb2.PGConnection#addDataType(String, Class)} and
   * {@link com.aliyun.polardb2.PGConnection#addDataType(String, String)}.</p>
   *
   * <p>If no class is registered as handling the given type, then a generic
   * {@link com.aliyun.polardb2.util.PGobject} instance is returned.</p>
   *
   * <p>value or byteValue must be non-null</p>
   * @param type the backend typename
   * @param value the type-specific string representation of the value
   * @param byteValue the type-specific binary representation of the value
   * @return an appropriate object; never null.
   * @throws SQLException if something goes wrong
   */
  Object getObject(String type, @Nullable String value, byte @Nullable [] byteValue)
      throws SQLException;

  @Pure
  Encoding getEncoding() throws SQLException;

  TypeInfo getTypeInfo();

  /**
   * <p>Check if we have at least a particular server version.</p>
   *
   * <p>The input version is of the form xxyyzz, matching a PostgreSQL version like xx.yy.zz. So 9.0.12
   * is 90012.</p>
   *
   * @param ver the server version to check, of the form xxyyzz eg 90401
   * @return true if the server version is at least "ver".
   */
  boolean haveMinimumServerVersion(int ver);

  /**
   * <p>Check if we have at least a particular server version.</p>
   *
   * <p>The input version is of the form xxyyzz, matching a PostgreSQL version like xx.yy.zz. So 9.0.12
   * is 90012.</p>
   *
   * @param ver the server version to check
   * @return true if the server version is at least "ver".
   */
  boolean haveMinimumServerVersion(Version ver);

  /**
   * Encode a string using the database's client_encoding (usually UTF8, but can vary on older
   * server versions). This is used when constructing synthetic resultsets (for example, in metadata
   * methods).
   *
   * @param str the string to encode
   * @return an encoded representation of the string
   * @throws SQLException if something goes wrong.
   */
  byte @PolyNull [] encodeString(@PolyNull String str) throws SQLException;

  /**
   * Escapes a string for use as string-literal within an SQL command. The method chooses the
   * applicable escaping rules based on the value of {@link #getStandardConformingStrings()}.
   *
   * @param str a string value
   * @return the escaped representation of the string
   * @throws SQLException if the string contains a {@code \0} character
   */
  String escapeString(String str) throws SQLException;

  /**
   * Returns whether the server treats string-literals according to the SQL standard or if it uses
   * traditional PostgreSQL escaping rules. Versions up to 8.1 always treated backslashes as escape
   * characters in string-literals. Since 8.2, this depends on the value of the
   * {@code standard_conforming_strings} server variable.
   *
   * @return true if the server treats string literals according to the SQL standard
   * @see QueryExecutor#getStandardConformingStrings()
   */
  boolean getStandardConformingStrings();

  // Ew. Quick hack to give access to the connection-specific utils implementation.
  @Deprecated
  TimestampUtils getTimestampUtils();

  // Get the per-connection logger.
  java.util.logging.Logger getLogger();

  // Get the bind-string-as-varchar config flag
  boolean getStringVarcharFlag();

  /**
   * Get the current transaction state of this connection.
   *
   * @return current transaction state of this connection
   */
  TransactionState getTransactionState();

  /**
   * Returns true if value for the given oid should be sent using binary transfer. False if value
   * should be sent using text transfer.
   *
   * @param oid The oid to check.
   * @return True for binary transfer, false for text transfer.
   */
  boolean binaryTransferSend(int oid);

  /**
   * Return whether to disable column name sanitation.
   *
   * @return true column sanitizer is disabled
   */
  boolean isColumnSanitiserDisabled();

  /**
   * Schedule a TimerTask for later execution. The task will be scheduled with the shared Timer for
   * this connection.
   *
   * @param timerTask timer task to schedule
   * @param milliSeconds delay in milliseconds
   */
  void addTimerTask(TimerTask timerTask, long milliSeconds);

  /**
   * Invoke purge() on the underlying shared Timer so that internal resources will be released.
   */
  void purgeTimerTasks();

  /**
   * Return metadata cache for given connection.
   *
   * @return metadata cache
   */
  LruCache<FieldMetadata.Key, FieldMetadata> getFieldMetadataCache();

  CachedQuery createQuery(String sql, boolean escapeProcessing, boolean isParameterized,
      String... columnNames)
      throws SQLException;

  /**
   * By default, the connection resets statement cache in case deallocate all/discard all
   * message is observed.
   * This API allows to disable that feature for testing purposes.
   *
   * @param flushCacheOnDeallocate true if statement cache should be reset when "deallocate/discard" message observed
   */
  void setFlushCacheOnDeallocate(boolean flushCacheOnDeallocate);

  /**
   * Indicates if statements to backend should be hinted as read only.
   *
   * @return Indication if hints to backend (such as when transaction begins)
   *         should be read only.
   * @see PGProperty#READ_ONLY_MODE
   */
  boolean hintReadOnly();

  /**
   * Retrieve the factory to instantiate XML processing factories.
   *
   * @return The factory to use to instantiate XML processing factories
   * @throws SQLException if the class cannot be found or instantiated.
   */
  PGXmlFactoryFactory getXmlFactoryFactory() throws SQLException;

  /**
   * Indicates if error details from server used in included in logging and exceptions.
   *
   * @return true if should be included and passed on to other exceptions
   */
  boolean getLogServerErrorDetail();

  /**
   * POLARDB DIFF
   */

  /**
   * Returns the mapDateToTimestamp connection setting.
   *
   * @return mapDateToTimestamp setting
   */
  boolean isMapDateToTimestamp();

  /**
   * Returns the oracleCase connection setting.
   *
   * @return oracleCase setting
   */
  boolean isOracleCase();

  /**
   * Returns the oracleCase connection setting.
   *
   * @return oracleCase setting
   */
  boolean isOracleCaseStrict();

  /**
   * Returns the autoCommitSpecCompliant connection setting.
   *
   * @return autoCommitSpecCompliant setting
   */
  boolean isAutoCommitSpecCompliant();

  /**
   * Returns the namedParam connection setting.
   *
   * @return namedParam setting
   */
  boolean isNamedParam();

  /**
   * Returns the isOraCommentStyle connection setting.
   *
   * @return isOraCommentStyle setting
   */
  boolean isOraCommentStyle();

  /**
   * Returns the isCollectWarning connection setting.
   *
   * @return isCollectWarning setting
   */
  boolean isCollectWarning();

  /**
   * Returns the clobAsText connection setting.
   *
   * @return clobAsText setting
   */
  boolean getClobAsText();

  /**
   * Returns the blobAsBytea connection setting.
   *
   * @return blobAsBytea setting
   */
  boolean getBlobAsBytea();

  /**
   * Returns the blobUpperHex connection setting.
   * When true, getString() on bytea columns returns uppercase hex without \x prefix (Oracle style).
   *
   * @return blobUpperHex setting
   */
  boolean isBlobUpperHex();

  /**
   * Returns the defaultPolarMaxFetchSize connection setting.
   *
   * @return defaultPolarMaxFetchSize setting
   */
  int defaultPolarMaxFetchSize();

  /**
   * Returns the PolarDriverPrefix connection setting.
   *
   * @return PolarDriverPrefix setting
   */
  PolarDriverPrefix getDriverPrefix();

  /**
   * Set the PolarDriverPrefix connection setting.
   *
   */
  void setDriverPrefix(PolarDriverPrefix polarDriverPrefix);

  /**
   * Returns isBoolAsInt connection setting.
   *
   * @return isBoolAsInt setting
   */
  boolean isBoolAsInt();

  /**
   * Returns isBigintAsNumeric connection setting.
   *
   * @return isBigintAsNumeric setting
   */
  boolean isBigintAsNumeric();

  /**
   * Returns isNumberStripTrailingZeros connection setting.
   *
   * @return isNumberStripTrailingZeros setting
   */
  boolean isNumberStripTrailingZeros();

  /**
   * Returns isBoolAsInt connection setting.
   *
   * @return isBoolAsInt setting
   */
  boolean callFunctionMode();

  /**
   * Returns true when autocommitFetch is enabled AND the server supports
   * polar_enable_autocommit_cursor (holdable portal on suspend).
   *
   * @return whether autocommit cursor fetch is active for this connection
   */
  boolean isAutocommitFetchEnabled();

  /**
   * Returns isForceDriverType connection setting.
   *
   * @return isForceDriverType setting
   */
  boolean isForceDriverType();

}
