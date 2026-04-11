/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.jdbc;

import static com.aliyun.polardb2.util.internal.Nullness.castNonNull;

import com.aliyun.polardb2.PGNotification;
import com.aliyun.polardb2.PGProperty;
import com.aliyun.polardb2.copy.CopyManager;
import com.aliyun.polardb2.core.BaseConnection;
import com.aliyun.polardb2.core.BaseStatement;
import com.aliyun.polardb2.core.CachedQuery;
import com.aliyun.polardb2.core.ConnectionFactory;
import com.aliyun.polardb2.core.Encoding;
import com.aliyun.polardb2.core.Oid;
import com.aliyun.polardb2.core.Query;
import com.aliyun.polardb2.core.QueryExecutor;
import com.aliyun.polardb2.core.ReplicationProtocol;
import com.aliyun.polardb2.core.ResultHandlerBase;
import com.aliyun.polardb2.core.ServerVersion;
import com.aliyun.polardb2.core.SqlCommand;
import com.aliyun.polardb2.core.TransactionState;
import com.aliyun.polardb2.core.TypeInfo;
import com.aliyun.polardb2.core.Utils;
import com.aliyun.polardb2.core.Version;
import com.aliyun.polardb2.fastpath.Fastpath;
import com.aliyun.polardb2.largeobject.LargeObjectManager;
import com.aliyun.polardb2.polarora.PolarDriverPrefix;
import com.aliyun.polardb2.replication.PGReplicationConnection;
import com.aliyun.polardb2.replication.PGReplicationConnectionImpl;
import com.aliyun.polardb2.util.GT;
import com.aliyun.polardb2.util.HostSpec;
import com.aliyun.polardb2.util.LazyCleaner;
import com.aliyun.polardb2.util.LruCache;
import com.aliyun.polardb2.util.PGBinaryObject;
import com.aliyun.polardb2.util.PGobject;
import com.aliyun.polardb2.util.PSQLException;
import com.aliyun.polardb2.util.PSQLState;
import com.aliyun.polardb2.xml.DefaultPGXmlFactoryFactory;
import com.aliyun.polardb2.xml.LegacyInsecurePGXmlFactoryFactory;
import com.aliyun.polardb2.xml.PGXmlFactoryFactory;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.checkerframework.dataflow.qual.Pure;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.security.Permission;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.ClientInfoStatus;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLPermission;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Condition;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PgConnection implements BaseConnection {

  private static final Logger LOGGER = Logger.getLogger(PgConnection.class.getName());
  private static final Set<Integer> SUPPORTED_BINARY_OIDS = getSupportedBinaryOids();
  private static final SQLPermission SQL_PERMISSION_ABORT = new SQLPermission("callAbort");
  private static final SQLPermission SQL_PERMISSION_NETWORK_TIMEOUT = new SQLPermission("setNetworkTimeout");

  private static final @Nullable MethodHandle SYSTEM_GET_SECURITY_MANAGER;
  private static final @Nullable MethodHandle SECURITY_MANAGER_CHECK_PERMISSION;

  static {
    MethodHandle systemGetSecurityManagerHandle = null;
    MethodHandle securityManagerCheckPermission = null;
    try {
      Class<?> securityManagerClass = Class.forName("java.lang.SecurityManager");
      systemGetSecurityManagerHandle =
          MethodHandles.lookup().findStatic(System.class, "getSecurityManager",
              MethodType.methodType(securityManagerClass));
      securityManagerCheckPermission =
          MethodHandles.lookup().findVirtual(securityManagerClass, "checkPermission",
              MethodType.methodType(void.class, Permission.class));
    } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException ignore) {
    }
    SYSTEM_GET_SECURITY_MANAGER = systemGetSecurityManagerHandle;
    SECURITY_MANAGER_CHECK_PERMISSION = securityManagerCheckPermission;
  }

  private enum ReadOnlyBehavior {
    ignore,
    transaction,
    always;
  }

  private final ResourceLock lock = new ResourceLock();
  private final Condition lockCondition = lock.newCondition();

  //
  // Data initialized on construction:
  //
  private final Properties clientInfo;

  /* URL we were created via */
  private final String creatingURL;

  private final ReadOnlyBehavior readOnlyBehavior;

  private @Nullable Throwable openStackTrace;

  /**
   * This field keeps finalize action alive, so its .finalize() method is called only
   * when the connection itself becomes unreachable.
   * Moving .finalize() to a different object allows JVM to release all the other objects
   * referenced in PgConnection early.
   */
  private final PgConnectionCleaningAction finalizeAction;
  private final Object leakHandle = new Object();

  /* Actual network handler */
  private final QueryExecutor queryExecutor;

  /* Query that runs COMMIT */
  private final Query commitQuery;
  /* Query that runs ROLLBACK */
  private final Query rollbackQuery;

  private final CachedQuery setSessionReadOnly;

  private final CachedQuery setSessionNotReadOnly;

  private final TypeInfo typeCache;

  private boolean disableColumnSanitiser = false;

  // Default statement prepare threshold.
  protected int prepareThreshold;

  /**
   * Default fetch size for statement.
   *
   * @see PGProperty#DEFAULT_ROW_FETCH_SIZE
   */
  protected int defaultFetchSize;

  // Default forcebinary option.
  protected boolean forcebinary = false;

  /**
   * Oids for which binary transfer should be disabled.
   */
  private final Set<? extends Integer> binaryDisabledOids;

  private int rsHoldability = ResultSet.CLOSE_CURSORS_AT_COMMIT;
  private int savepointId = 0;
  // Connection's autocommit state.
  private boolean autoCommit = true;
  // Connection's readonly state.
  private boolean readOnly = false;
  // Filter out database objects for which the current user has no privileges granted from the DatabaseMetaData
  private final boolean  hideUnprivilegedObjects ;
  // Whether to include error details in logging and exceptions
  private final boolean logServerErrorDetail;
  // Bind String to UNSPECIFIED or VARCHAR?
  private final boolean bindStringAsVarchar;

  /* POLAR DIFF */
  private boolean mapDateToTimestamp = false;
  private int oracleCase = 0;
  private boolean autoCommitSpecCompliant = true;
  private boolean namedParam = false;
  private boolean collectWarning = true;
  // use bytea for BLOBs instead of Postgres LOs?
  private boolean blobAsBytea = false;
  // output bytea as Oracle-style uppercase hex (AABBCC) instead of PG \xaabbcc?
  private boolean blobUpperHex = true;
  // use text for CLOBs instead of Postgres LOs?
  private boolean clobAsText = false;
  private int defaultPolarMaxFetchSize;
  private PolarDriverPrefix driverPrefix = PolarDriverPrefix.POLARDB;
  private boolean boolAsInt = false;
  private boolean bigintAsNumeric = false;
  private boolean numberStripTrailingZeros = true;
  private boolean commentStyle = false;
  private boolean callFunctionMode = false;
  private boolean forceDriverType = false;
  private boolean allowSelectInExecuteUpdate = false;
  /* POLAR DIFF END */

  // Current warnings; there might be more on queryExecutor too.
  private @Nullable SQLWarning firstWarning;

  private @Nullable PreparedStatement checkConnectionQuery;
  /**
   * Replication protocol in current version postgresql(10devel) supports a limited number of
   * commands.
   */
  private final boolean replicationConnection;

  private final LruCache<FieldMetadata.Key, FieldMetadata> fieldMetadataCache;

  private final @Nullable String xmlFactoryFactoryClass;
  private @Nullable PGXmlFactoryFactory xmlFactoryFactory;
  private final LazyCleaner.Cleanable<IOException> cleanable;

  final CachedQuery borrowQuery(String sql) throws SQLException {
    return queryExecutor.borrowQuery(sql);
  }

  final CachedQuery borrowCallableQuery(String sql) throws SQLException {
    return queryExecutor.borrowCallableQuery(sql);
  }

  private CachedQuery borrowReturningQuery(String sql, String @Nullable [] columnNames)
      throws SQLException {
    return queryExecutor.borrowReturningQuery(sql, columnNames);
  }

  @Override
  public CachedQuery createQuery(String sql, boolean escapeProcessing, boolean isParameterized,
      String... columnNames)
      throws SQLException {
    return queryExecutor.createQuery(sql, escapeProcessing, isParameterized, columnNames);
  }

  void releaseQuery(CachedQuery cachedQuery) {
    queryExecutor.releaseQuery(cachedQuery);
  }

  @Override
  public void setFlushCacheOnDeallocate(boolean flushCacheOnDeallocate) {
    queryExecutor.setFlushCacheOnDeallocate(flushCacheOnDeallocate);
    LOGGER.log(Level.FINE, "  setFlushCacheOnDeallocate = {0}", flushCacheOnDeallocate);
  }

  //
  // Ctor.
  //
  @SuppressWarnings({"method.invocation.invalid"})
  public PgConnection(HostSpec[] hostSpecs,
                      Properties info,
                      String url) throws SQLException {
    // Print out the driver version number
    LOGGER.log(Level.FINE, com.aliyun.polardb2.util.DriverInfo.DRIVER_FULL_NAME);

    this.creatingURL = url;

    this.readOnlyBehavior = getReadOnlyBehavior(PGProperty.READ_ONLY_MODE.getOrDefault(info));

    setDefaultFetchSize(PGProperty.DEFAULT_ROW_FETCH_SIZE.getInt(info));

    setPrepareThreshold(PGProperty.PREPARE_THRESHOLD.getInt(info));
    if (prepareThreshold == -1) {
      setForceBinary(true);
    }

    /* POLAR: set some parameters. */
    polarSetParameters(info);

    // Now make the initial connection and set up local state
    this.queryExecutor = ConnectionFactory.openConnection(hostSpecs, info);

    // WARNING for unsupported servers (8.1 and lower are not supported)
    if (LOGGER.isLoggable(Level.WARNING) && !haveMinimumServerVersion(ServerVersion.v8_2)) {
      LOGGER.log(Level.WARNING, "Unsupported Server Version: {0}", queryExecutor.getServerVersion());
    }

    setSessionReadOnly = createQuery("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY", false, true);
    setSessionNotReadOnly = createQuery("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE", false, true);

    // Set read-only early if requested
    if (PGProperty.READ_ONLY.getBoolean(info)) {
      setReadOnly(true);
    }

    this.hideUnprivilegedObjects = PGProperty.HIDE_UNPRIVILEGED_OBJECTS.getBoolean(info);

    // get oids that support binary transfer
    Set<Integer> binaryOids = getBinaryEnabledOids(info);
    // get oids that should be disabled from transfer
    binaryDisabledOids = getBinaryDisabledOids(info);
    // if there are any, remove them from the enabled ones
    if (!binaryDisabledOids.isEmpty()) {
      binaryOids.removeAll(binaryDisabledOids);
    }

    // split for receive and send for better control
    Set<Integer> useBinarySendForOids = new HashSet<Integer>(binaryOids);

    Set<Integer> useBinaryReceiveForOids = new HashSet<Integer>(binaryOids);

    /*
     * Does not pass unit tests because unit tests expect setDate to have millisecond accuracy
     * whereas the binary transfer only supports date accuracy.
     */
    useBinarySendForOids.remove(Oid.DATE);

    queryExecutor.setBinaryReceiveOids(useBinaryReceiveForOids);
    queryExecutor.setBinarySendOids(useBinarySendForOids);

    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, "    types using binary send = {0}", oidsToString(useBinarySendForOids));
      LOGGER.log(Level.FINEST, "    types using binary receive = {0}", oidsToString(useBinaryReceiveForOids));
      LOGGER.log(Level.FINEST, "    integer date/time = {0}", queryExecutor.getIntegerDateTimes());
    }

    //
    // String -> text or unknown?
    //

    String stringType = PGProperty.STRING_TYPE.getOrDefault(info);
    if (stringType != null) {
      if (stringType.equalsIgnoreCase("unspecified")) {
        bindStringAsVarchar = false;
      } else if (stringType.equalsIgnoreCase("varchar")) {
        bindStringAsVarchar = true;
      } else {
        throw new PSQLException(
            GT.tr("Unsupported value for stringtype parameter: {0}", stringType),
            PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else {
      bindStringAsVarchar = true;
    }

    // Initialize timestamp stuff
    timestampUtils = new TimestampUtils(!queryExecutor.getIntegerDateTimes(),
        new QueryExecutorTimeZoneProvider(queryExecutor));

    // Initialize common queries.
    // isParameterized==true so full parse is performed and the engine knows the query
    // is not a compound query with ; inside, so it could use parse/bind/exec messages
    commitQuery = createQuery("COMMIT", false, true).query;
    rollbackQuery = createQuery("ROLLBACK", false, true).query;

    int unknownLength = PGProperty.UNKNOWN_LENGTH.getInt(info);

    // Initialize object handling
    @SuppressWarnings("argument")
    TypeInfo typeCache = createTypeInfo(this, unknownLength);
    this.typeCache = typeCache;
    initObjectTypes(info);

    if (PGProperty.LOG_UNCLOSED_CONNECTIONS.getBoolean(info)) {
      openStackTrace = new Throwable("Connection was created at this point:");
    }
    finalizeAction = new PgConnectionCleaningAction(lock, openStackTrace, queryExecutor.getCloseAction());
    this.logServerErrorDetail = PGProperty.LOG_SERVER_ERROR_DETAIL.getBoolean(info);
    this.disableColumnSanitiser = PGProperty.DISABLE_COLUMN_SANITISER.getBoolean(info);

    if (haveMinimumServerVersion(ServerVersion.v8_3)) {
      typeCache.addCoreType("uuid", Oid.UUID, Types.OTHER, "java.util.UUID", Oid.UUID_ARRAY);
      typeCache.addCoreType("xml", Oid.XML, Types.SQLXML, "java.sql.SQLXML", Oid.XML_ARRAY);
    }

    this.clientInfo = new Properties();
    if (haveMinimumServerVersion(ServerVersion.v9_0)) {
      String appName = PGProperty.APPLICATION_NAME.getOrDefault(info);
      if (appName == null) {
        appName = "";
      }
      this.clientInfo.put("ApplicationName", appName);
    }

    fieldMetadataCache = new LruCache<FieldMetadata.Key, FieldMetadata>(
            Math.max(0, PGProperty.DATABASE_METADATA_CACHE_FIELDS.getInt(info)),
            Math.max(0, PGProperty.DATABASE_METADATA_CACHE_FIELDS_MIB.getInt(info) * 1024L * 1024L),
        false);

    replicationConnection = PGProperty.REPLICATION.getOrDefault(info) != null;

    xmlFactoryFactoryClass = PGProperty.XML_FACTORY_FACTORY.getOrDefault(info);
    cleanable = LazyCleaner.getInstance().register(leakHandle, finalizeAction);
  }

  private static ReadOnlyBehavior getReadOnlyBehavior(@Nullable String property) {
    if (property == null) {
      return ReadOnlyBehavior.transaction;
    }
    try {
      return ReadOnlyBehavior.valueOf(property);
    } catch (IllegalArgumentException e) {
      try {
        return ReadOnlyBehavior.valueOf(property.toLowerCase(Locale.US));
      } catch (IllegalArgumentException e2) {
        return ReadOnlyBehavior.transaction;
      }
    }
  }

  private static Set<Integer> getSupportedBinaryOids() {
    return new HashSet<Integer>(Arrays.asList(
        Oid.BYTEA,
        Oid.INT2,
        Oid.INT4,
        Oid.INT8,
        Oid.FLOAT4,
        Oid.FLOAT8,
        Oid.NUMERIC,
        Oid.TIME,
        Oid.DATE,
        Oid.TIMETZ,
        Oid.TIMESTAMP,
        Oid.TIMESTAMPTZ,
        Oid.BYTEA_ARRAY,
        Oid.INT2_ARRAY,
        Oid.INT4_ARRAY,
        Oid.INT8_ARRAY,
        Oid.OID_ARRAY,
        Oid.FLOAT4_ARRAY,
        Oid.FLOAT8_ARRAY,
        Oid.VARCHAR_ARRAY,
        Oid.TEXT_ARRAY,
        Oid.POINT,
        Oid.BOX,
        Oid.UUID));
  }

  /**
   * Gets all oids for which binary transfer can be enabled.
   *
   * @param info properties
   * @return oids for which binary transfer can be enabled
   * @throws PSQLException if any oid is not valid
   */
  private static Set<Integer> getBinaryEnabledOids(Properties info) throws PSQLException {
    // check if binary transfer should be enabled for built-in types
    boolean binaryTransfer = PGProperty.BINARY_TRANSFER.getBoolean(info);
    // get formats that currently have binary protocol support
    Set<Integer> binaryOids = new HashSet<Integer>(32);
    if (binaryTransfer) {
      binaryOids.addAll(SUPPORTED_BINARY_OIDS);
    }
    // add all oids which are enabled for binary transfer by the creator of the connection
    String oids = PGProperty.BINARY_TRANSFER_ENABLE.getOrDefault(info);
    if (oids != null) {
      binaryOids.addAll(getOidSet(oids));
    }
    return binaryOids;
  }

  /**
   * Gets all oids for which binary transfer should be disabled.
   *
   * @param info properties
   * @return oids for which binary transfer should be disabled
   * @throws PSQLException if any oid is not valid
   */
  private static Set<? extends Integer> getBinaryDisabledOids(Properties info)
      throws PSQLException {
    // check for oids that should explicitly be disabled
    String oids = PGProperty.BINARY_TRANSFER_DISABLE.getOrDefault(info);
    if (oids == null) {
      return Collections.emptySet();
    }
    return getOidSet(oids);
  }

  private static Set<? extends Integer> getOidSet(String oidList) throws PSQLException {
    if (oidList.isEmpty()) {
      return Collections.emptySet();
    }
    Set<Integer> oids = new HashSet<>();
    StringTokenizer tokenizer = new StringTokenizer(oidList, ",");
    while (tokenizer.hasMoreTokens()) {
      String oid = tokenizer.nextToken();
      oids.add(Oid.valueOf(oid));
    }
    return oids;
  }

  private String oidsToString(Set<Integer> oids) {
    StringBuilder sb = new StringBuilder();
    for (Integer oid : oids) {
      sb.append(Oid.toString(oid));
      sb.append(',');
    }
    if (sb.length() > 0) {
      sb.setLength(sb.length() - 1);
    } else {
      sb.append(" <none>");
    }
    return sb.toString();
  }

  private final TimestampUtils timestampUtils;

  /* POLAR: set some parameters */
  private void polarSetParameters(Properties info) {
    // new add parameters
    this.driverPrefix = PolarDriverPrefix.forName(PGProperty.DRIVER_PREFIX.getOrDefault(info));
    this.mapDateToTimestamp = PGProperty.MAP_DATE_TO_TIMESTAMP.getBoolean(info);
    String oracleCaseLabel = PGProperty.ORACLE_CASE.getOrDefault(info);
    if (oracleCaseLabel.equalsIgnoreCase("true")) {
      this.oracleCase = 1;
    } else if (oracleCaseLabel.equalsIgnoreCase("strict")) {
      this.oracleCase = 2;
    }
    this.autoCommit = PGProperty.AUTO_COMMIT.getBoolean(info);
    this.autoCommitSpecCompliant = PGProperty.AUTO_COMMIT_SPEC_COMPLIANT.getBoolean(info);
    this.collectWarning = PGProperty.COLLECT_WARNING.getBoolean(info);
    this.blobAsBytea = PGProperty.BLOB_AS_BYTEA.getBoolean(info);
    this.blobUpperHex = PGProperty.BLOB_UPPER_HEX.getBoolean(info);
    this.clobAsText = PGProperty.CLOB_AS_TEXT.getBoolean(info);
    this.defaultPolarMaxFetchSize = PGProperty.DEFAULT_POLAR_MAX_FETCH_SIZE.getIntNoCheck(info);
    this.boolAsInt = PGProperty.BOOL_AS_INT.getBoolean(info);
    this.bigintAsNumeric = PGProperty.BIGINT_AS_NUMERIC.getBoolean(info);
    this.numberStripTrailingZeros = PGProperty.NUMBER_STRIP_TRAILING_ZEROS.getBoolean(info);
    this.callFunctionMode = PGProperty.CALL_FUNCTION_MODE.getBoolean(info);
    this.forceDriverType = PGProperty.FORCE_DRIVER_TYPE.getBoolean(info);
    this.allowSelectInExecuteUpdate = PGProperty.ALLOW_SELECT_IN_EXECUTE_UPDATE.getBoolean(info);
  }

  @Deprecated
  public TimestampUtils getTimestampUtils() {
    return timestampUtils;
  }

  /**
   * The current type mappings.
   */
  protected Map<String, Class<?>> typemap = new HashMap<String, Class<?>>();

  /**
   * Obtain the connection lock and return it. Callers must use try-with-resources to ensure that
   * unlock() is performed on the lock.
   */
  final ResourceLock obtainLock() {
    return lock.obtain();
  }

  /**
   * Return the lock condition for this connection.
   */
  final Condition lockCondition() {
    return lockCondition;
  }

  @Override
  public Statement createStatement() throws SQLException {
    // We now follow the spec and default to TYPE_FORWARD_ONLY.
    return createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
  }

  @Override
  public PreparedStatement prepareStatement(String sql) throws SQLException {
    return prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
  }

  @Override
  public CallableStatement prepareCall(String sql) throws SQLException {
    return prepareCall(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
  }

  @Override
  public Map<String, Class<?>> getTypeMap() throws SQLException {
    checkClosed();
    return typemap;
  }

  public QueryExecutor getQueryExecutor() {
    return queryExecutor;
  }

  public ReplicationProtocol getReplicationProtocol() {
    return queryExecutor.getReplicationProtocol();
  }

  /**
   * This adds a warning to the warning chain.
   *
   * @param warn warning to add
   */
  public void addWarning(SQLWarning warn) {
    // Add the warning to the chain
    if (firstWarning != null) {
      firstWarning.setNextWarning(warn);
    } else {
      firstWarning = warn;
    }

  }

  @Override
  public ResultSet execSQLQuery(String s) throws SQLException {
    return execSQLQuery(s, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
  }

  @Override
  public ResultSet execSQLQuery(String s, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    BaseStatement stat = (BaseStatement) createStatement(resultSetType, resultSetConcurrency);
    boolean hasResultSet = stat.executeWithFlags(s, QueryExecutor.QUERY_SUPPRESS_BEGIN);

    while (!hasResultSet && stat.getUpdateCount() != -1) {
      hasResultSet = stat.getMoreResults();
    }

    if (!hasResultSet) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    // Transfer warnings to the connection, since the user never
    // has a chance to see the statement itself.
    SQLWarning warnings = stat.getWarnings();
    if (warnings != null) {
      addWarning(warnings);
    }

    return castNonNull(stat.getResultSet(), "hasResultSet==true, yet getResultSet()==null");
  }

  @Override
  public void execSQLUpdate(String s) throws SQLException {
    BaseStatement stmt = (BaseStatement) createStatement();
    if (stmt.executeWithFlags(s, QueryExecutor.QUERY_NO_METADATA | QueryExecutor.QUERY_NO_RESULTS
        | QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new PSQLException(GT.tr("A result was returned when none was expected."),
          PSQLState.TOO_MANY_RESULTS);
    }

    // Transfer warnings to the connection, since the user never
    // has a chance to see the statement itself.
    SQLWarning warnings = stmt.getWarnings();
    if (warnings != null) {
      addWarning(warnings);
    }

    stmt.close();
  }

  void execSQLUpdate(CachedQuery query) throws SQLException {
    BaseStatement stmt = (BaseStatement) createStatement();
    if (stmt.executeWithFlags(query, QueryExecutor.QUERY_NO_METADATA | QueryExecutor.QUERY_NO_RESULTS
        | QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new PSQLException(GT.tr("A result was returned when none was expected."),
          PSQLState.TOO_MANY_RESULTS);
    }

    // Transfer warnings to the connection, since the user never
    // has a chance to see the statement itself.
    SQLWarning warnings = stmt.getWarnings();
    if (warnings != null) {
      addWarning(warnings);
    }

    stmt.close();
  }

  /**
   * <p>In SQL, a result table can be retrieved through a cursor that is named. The current row of a
   * result can be updated or deleted using a positioned update/delete statement that references the
   * cursor name.</p>
   *
   * <p>We do not support positioned update/delete, so this is a no-op.</p>
   *
   * @param cursor the cursor name
   * @throws SQLException if a database access error occurs
   */
  public void setCursorName(String cursor) throws SQLException {
    checkClosed();
    // No-op.
  }

  /**
   * getCursorName gets the cursor name.
   *
   * @return the current cursor name
   * @throws SQLException if a database access error occurs
   */
  public @Nullable String getCursorName() throws SQLException {
    checkClosed();
    return null;
  }

  /**
   * <p>We are required to bring back certain information by the DatabaseMetaData class. These
   * functions do that.</p>
   *
   * <p>Method getURL() brings back the URL (good job we saved it)</p>
   *
   * @return the url
   * @throws SQLException just in case...
   */
  public String getURL() throws SQLException {
    return creatingURL;
  }

  /**
   * Method getUserName() brings back the User Name (again, we saved it).
   *
   * @return the user name
   * @throws SQLException just in case...
   */
  public String getUserName() throws SQLException {
    return queryExecutor.getUser();
  }

  public Fastpath getFastpathAPI() throws SQLException {
    checkClosed();
    if (fastpath == null) {
      fastpath = new Fastpath(this);
    }
    return fastpath;
  }

  // This holds a reference to the Fastpath API if already open
  private @Nullable Fastpath fastpath;

  public LargeObjectManager getLargeObjectAPI() throws SQLException {
    checkClosed();
    if (largeobject == null) {
      largeobject = new LargeObjectManager(this);
    }
    return largeobject;
  }

  // This holds a reference to the LargeObject API if already open
  private @Nullable LargeObjectManager largeobject;

  /*
   * This method is used internally to return an object based around com.aliyun.polardb2's more unique
   * data types.
   *
   * <p>It uses an internal HashMap to get the handling class. If the type is not supported, then an
   * instance of com.aliyun.polardb2.util.PGobject is returned.
   *
   * You can use the getValue() or setValue() methods to handle the returned object. Custom objects
   * can have their own methods.
   *
   * @return PGobject for this type, and set to value
   *
   * @exception SQLException if value is not correct for this type
   */
  @Override
  public Object getObject(String type, @Nullable String value, byte @Nullable [] byteValue)
      throws SQLException {
    if (typemap != null) {
      Class<?> c = typemap.get(type);
      if (c != null) {
        // Handle the type (requires SQLInput & SQLOutput classes to be implemented)
        throw new PSQLException(GT.tr("Custom type maps are not supported."),
            PSQLState.NOT_IMPLEMENTED);
      }
    }

    PGobject obj = null;

    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, "Constructing object from type={0} value=<{1}>", new Object[]{type, value});
    }

    try {
      Class<? extends PGobject> klass = typeCache.getPGobject(type);

      // If className is not null, then try to instantiate it,
      // It must be basetype PGobject

      // This is used to implement the com.aliyun.polardb2 unique types (like lseg,
      // point, etc).

      if (klass != null) {
        obj = klass.newInstance();
        obj.setType(type);
        if (byteValue != null && obj instanceof PGBinaryObject) {
          PGBinaryObject binObj = (PGBinaryObject) obj;
          binObj.setByteValue(byteValue, 0);
        } else {
          obj.setValue(value);
        }
      } else {
        // If className is null, then the type is unknown.
        // so return a PGobject with the type set, and the value set
        /* POLAR DIFF: For composite types (typtype='c', sqlType=STRUCT), create a
         * PgCompositeObject which extends PGobject AND implements java.sql.Struct.
         * This ensures that composite type values from any code path (getArray(),
         * getResultSet(), getObject()) can be cast to both PGobject and Struct. */
        int sqlType = typeCache.getSQLType(type);
        if (sqlType == java.sql.Types.STRUCT) {
          obj = new PgCompositeObject();
        } else {
          obj = new PGobject();
        }
        // POLAR DIFF end
        obj.setType(type);
        obj.setValue(value);
      }

      return obj;
    } catch (SQLException sx) {
      // rethrow the exception. Done because we capture any others next
      throw sx;
    } catch (Exception ex) {
      throw new PSQLException(GT.tr("Failed to create object for: {0}.", type),
          PSQLState.CONNECTION_FAILURE, ex);
    }
  }

  protected TypeInfo createTypeInfo(BaseConnection conn, int unknownLength) {
    return new TypeInfoCache(conn, unknownLength);
  }

  public TypeInfo getTypeInfo() {
    return typeCache;
  }

  @Override
  public void addDataType(String type, String name) {
    try {
      addDataType(type, Class.forName(name).asSubclass(PGobject.class));
    } catch (Exception e) {
      throw new RuntimeException("Cannot register new type " + type, e);
    }
  }

  @Override
  public void addDataType(String type, Class<? extends PGobject> klass) throws SQLException {
    checkClosed();
    // first add the data type to the type cache
    typeCache.addDataType(type, klass);
    // then check if this type supports binary transfer
    if (PGBinaryObject.class.isAssignableFrom(klass) && getPreferQueryMode() != PreferQueryMode.SIMPLE) {
      // try to get an oid for this type (will return 0 if the type does not exist in the database)
      int oid = typeCache.getPGType(type);
      // check if oid is there and if it is not disabled for binary transfer
      if (oid > 0 && !binaryDisabledOids.contains(oid)) {
        // allow using binary transfer for receiving and sending of this type
        queryExecutor.addBinaryReceiveOid(oid);
        queryExecutor.addBinarySendOid(oid);
      }
    }
  }

  // This initialises the objectTypes hash map
  private void initObjectTypes(Properties info) throws SQLException {
    // Add in the types that come packaged with the driver.
    // These can be overridden later if desired.
    addDataType("box", com.aliyun.polardb2.geometric.PGbox.class);
    addDataType("circle", com.aliyun.polardb2.geometric.PGcircle.class);
    addDataType("line", com.aliyun.polardb2.geometric.PGline.class);
    addDataType("lseg", com.aliyun.polardb2.geometric.PGlseg.class);
    addDataType("path", com.aliyun.polardb2.geometric.PGpath.class);
    addDataType("point", com.aliyun.polardb2.geometric.PGpoint.class);
    addDataType("polygon", com.aliyun.polardb2.geometric.PGpolygon.class);
    addDataType("money", com.aliyun.polardb2.util.PGmoney.class);
    addDataType("interval", com.aliyun.polardb2.util.PGInterval.class);

    Enumeration<?> e = info.propertyNames();
    while (e.hasMoreElements()) {
      String propertyName = (String) e.nextElement();
      if (propertyName != null && propertyName.startsWith("datatype.")) {
        String typeName = propertyName.substring(9);
        String className = castNonNull(info.getProperty(propertyName));
        Class<?> klass;

        try {
          klass = Class.forName(className);
        } catch (ClassNotFoundException cnfe) {
          throw new PSQLException(
              GT.tr("Unable to load the class {0} responsible for the datatype {1}",
                  className, typeName),
              PSQLState.SYSTEM_ERROR, cnfe);
        }

        addDataType(typeName, klass.asSubclass(PGobject.class));
      }
    }
  }

  /**
   * <B>Note:</B> even though {@code Statement} is automatically closed when it is garbage
   * collected, it is better to close it explicitly to lower resource consumption.
   * The spec says that calling close on a closed connection is a no-op.
   *
   * {@inheritDoc}
   */
  @Override
  public void close() throws SQLException {
    if (queryExecutor == null) {
      // This might happen in case constructor throws an exception (e.g. host being not available).
      // When that happens the connection is still registered in the finalizer queue, so it gets finalized
      return;
    }
    openStackTrace = null;
    try {
      cleanable.clean();
    } catch (IOException e) {
      throw new PSQLException(
          GT.tr("Unable to close connection properly"),
          PSQLState.UNKNOWN_STATE, e);
    }
  }

  @Override
  public String nativeSQL(String sql) throws SQLException {
    checkClosed();
    CachedQuery cachedQuery = queryExecutor.createQuery(sql, false, true);

    return cachedQuery.query.getNativeSql();
  }

  @Override
  public @Nullable SQLWarning getWarnings() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkClosed();
      SQLWarning newWarnings = queryExecutor.getWarnings(); // NB: also clears them.
      if (firstWarning == null) {
        firstWarning = newWarnings;
      } else if (newWarnings != null) {
        firstWarning.setNextWarning(newWarnings); // Chain them on.
      }

      return firstWarning;
    }
  }

  @Override
  public void clearWarnings() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkClosed();
      //noinspection ThrowableNotThrown
      queryExecutor.getWarnings(); // Clear and discard.
      firstWarning = null;
    }
  }

  @Override
  public void setReadOnly(boolean readOnly) throws SQLException {
    checkClosed();
    if (queryExecutor.getTransactionState() != TransactionState.IDLE) {
      throw new PSQLException(
          GT.tr("Cannot change transaction read-only property in the middle of a transaction."),
          PSQLState.ACTIVE_SQL_TRANSACTION);
    }

    if (readOnly != this.readOnly && autoCommit && this.readOnlyBehavior == ReadOnlyBehavior.always) {
      execSQLUpdate(readOnly ? setSessionReadOnly : setSessionNotReadOnly);
    }

    this.readOnly = readOnly;
    LOGGER.log(Level.FINE, "  setReadOnly = {0}", readOnly);
  }

  @Override
  public boolean isReadOnly() throws SQLException {
    checkClosed();
    return readOnly;
  }

  @Override
  public boolean hintReadOnly() {
    return readOnly && readOnlyBehavior != ReadOnlyBehavior.ignore;
  }

  @Override
  public void setAutoCommit(boolean autoCommit) throws SQLException {
    checkClosed();

    if (this.autoCommit == autoCommit) {
      return;
    }

    if (!this.autoCommit) {
      commit();
    }

    // if the connection is read only, we need to make sure session settings are
    // correct when autocommit status changed
    if (this.readOnly && readOnlyBehavior == ReadOnlyBehavior.always) {
      // if we are turning on autocommit, we need to set session
      // to read only
      if (autoCommit) {
        this.autoCommit = true;
        execSQLUpdate(setSessionReadOnly);
      } else {
        // if we are turning auto commit off, we need to
        // disable session
        execSQLUpdate(setSessionNotReadOnly);
      }
    }

    this.autoCommit = autoCommit;
    LOGGER.log(Level.FINE, "  setAutoCommit = {0}", autoCommit);
  }

  @Override
  public boolean getAutoCommit() throws SQLException {
    checkClosed();
    return this.autoCommit;
  }

  private void executeTransactionCommand(Query query) throws SQLException {
    int flags = QueryExecutor.QUERY_NO_METADATA | QueryExecutor.QUERY_NO_RESULTS
        | QueryExecutor.QUERY_SUPPRESS_BEGIN;
    if (prepareThreshold == 0) {
      flags |= QueryExecutor.QUERY_ONESHOT;
    }

    try {
      getQueryExecutor().execute(query, null, new TransactionCommandHandler(), 0, 0, flags);
    } catch (SQLException e) {
      // Don't retry composite queries as it might get partially executed
      if (query.getSubqueries() != null || !queryExecutor.willHealOnRetry(e)) {
        throw e;
      }
      query.close();
      // retry
      getQueryExecutor().execute(query, null, new TransactionCommandHandler(), 0, 0, flags);
    }
  }

  @Override
  public void commit() throws SQLException {
    checkClosed();

    if (autoCommit && isAutoCommitSpecCompliant()) {
      throw new PSQLException(GT.tr("Cannot commit when autoCommit is enabled."),
          PSQLState.NO_ACTIVE_SQL_TRANSACTION);
    }

    if (queryExecutor.getTransactionState() != TransactionState.IDLE) {
      executeTransactionCommand(commitQuery);
    }
  }

  protected void checkClosed() throws SQLException {
    if (isClosed()) {
      throw new PSQLException(GT.tr("This connection has been closed."),
          PSQLState.CONNECTION_DOES_NOT_EXIST);
    }
  }

  @Override
  public void rollback() throws SQLException {
    checkClosed();

    if (autoCommit && isAutoCommitSpecCompliant()) {
      throw new PSQLException(GT.tr("Cannot rollback when autoCommit is enabled."),
          PSQLState.NO_ACTIVE_SQL_TRANSACTION);
    }

    if (queryExecutor.getTransactionState() != TransactionState.IDLE) {
      executeTransactionCommand(rollbackQuery);
    } else {
      // just log for debugging
      LOGGER.log(Level.FINE, "Rollback requested but no transaction in progress");
    }
  }

  public TransactionState getTransactionState() {
    return queryExecutor.getTransactionState();
  }

  public int getTransactionIsolation() throws SQLException {
    checkClosed();

    String level = null;
    final ResultSet rs = execSQLQuery("SHOW TRANSACTION ISOLATION LEVEL"); // nb: no BEGIN triggered
    if (rs.next()) {
      level = rs.getString(1);
    }
    rs.close();

    // TODO revisit: throw exception instead of silently eating the error in unknown cases?
    if (level == null) {
      return Connection.TRANSACTION_READ_COMMITTED; // Best guess.
    }

    level = level.toUpperCase(Locale.US);
    if (level.equals("READ COMMITTED")) {
      return Connection.TRANSACTION_READ_COMMITTED;
    }
    if (level.equals("READ UNCOMMITTED")) {
      return Connection.TRANSACTION_READ_UNCOMMITTED;
    }
    if (level.equals("REPEATABLE READ")) {
      return Connection.TRANSACTION_REPEATABLE_READ;
    }
    if (level.equals("SERIALIZABLE")) {
      return Connection.TRANSACTION_SERIALIZABLE;
    }

    return Connection.TRANSACTION_READ_COMMITTED; // Best guess.
  }

  public void setTransactionIsolation(int level) throws SQLException {
    checkClosed();

    if (queryExecutor.getTransactionState() != TransactionState.IDLE) {
      throw new PSQLException(
          GT.tr("Cannot change transaction isolation level in the middle of a transaction."),
          PSQLState.ACTIVE_SQL_TRANSACTION);
    }

    String isolationLevelName = getIsolationLevelName(level);
    if (isolationLevelName == null) {
      throw new PSQLException(GT.tr("Transaction isolation level {0} not supported.", level),
          PSQLState.NOT_IMPLEMENTED);
    }

    String isolationLevelSQL =
        "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL " + isolationLevelName;
    execSQLUpdate(isolationLevelSQL); // nb: no BEGIN triggered
    LOGGER.log(Level.FINE, "  setTransactionIsolation = {0}", isolationLevelName);
  }

  protected @Nullable String getIsolationLevelName(int level) {
    switch (level) {
      case Connection.TRANSACTION_READ_COMMITTED:
        return "READ COMMITTED";
      case Connection.TRANSACTION_SERIALIZABLE:
        return "SERIALIZABLE";
      case Connection.TRANSACTION_READ_UNCOMMITTED:
        return "READ UNCOMMITTED";
      case Connection.TRANSACTION_REPEATABLE_READ:
        return "REPEATABLE READ";
      default:
        return null;
    }
  }

  public void setCatalog(String catalog) throws SQLException {
    checkClosed();
    // no-op
  }

  public String getCatalog() throws SQLException {
    checkClosed();
    return queryExecutor.getDatabase();
  }

  public boolean getHideUnprivilegedObjects() {
    return hideUnprivilegedObjects;
  }

  /**
   * Get server version number.
   *
   * @return server version number
   */
  public String getDBVersionNumber() {
    return queryExecutor.getServerVersion();
  }

  /**
   * Get server major version.
   *
   * @return server major version
   */
  public int getServerMajorVersion() {
    try {
      StringTokenizer versionTokens = new StringTokenizer(queryExecutor.getServerVersion(), "."); // aaXbb.ccYdd
      return integerPart(versionTokens.nextToken()); // return X
    } catch (NoSuchElementException e) {
      return 0;
    }
  }

  /**
   * Get server minor version.
   *
   * @return server minor version
   */
  public int getServerMinorVersion() {
    try {
      StringTokenizer versionTokens = new StringTokenizer(queryExecutor.getServerVersion(), "."); // aaXbb.ccYdd
      versionTokens.nextToken(); // Skip aaXbb
      return integerPart(versionTokens.nextToken()); // return Y
    } catch (NoSuchElementException e) {
      return 0;
    }
  }

  @Override
  public boolean haveMinimumServerVersion(int ver) {
    return queryExecutor.getServerVersionNum() >= ver;
  }

  @Override
  public boolean haveMinimumServerVersion(Version ver) {
    return haveMinimumServerVersion(ver.getVersionNum());
  }

  @Pure
  @Override
  public Encoding getEncoding() {
    return queryExecutor.getEncoding();
  }

  @Override
  public byte @PolyNull [] encodeString(@PolyNull String str) throws SQLException {
    try {
      return getEncoding().encode(str);
    } catch (IOException ioe) {
      throw new PSQLException(GT.tr("Unable to translate data into the desired encoding."),
          PSQLState.DATA_ERROR, ioe);
    }
  }

  @Override
  public String escapeString(String str) throws SQLException {
    return Utils.escapeLiteral(null, str, queryExecutor.getStandardConformingStrings())
        .toString();
  }

  @Override
  public boolean getStandardConformingStrings() {
    return queryExecutor.getStandardConformingStrings();
  }

  // This is a cache of the DatabaseMetaData instance for this connection
  protected java.sql.@Nullable DatabaseMetaData metadata;

  @Override
  public boolean isClosed() throws SQLException {
    return queryExecutor.isClosed();
  }

  @Override
  public void cancelQuery() throws SQLException {
    checkClosed();
    queryExecutor.sendQueryCancel();
  }

  @Override
  public PGNotification[] getNotifications() throws SQLException {
    return getNotifications(-1);
  }

  @Override
  public PGNotification[] getNotifications(int timeoutMillis) throws SQLException {
    checkClosed();
    getQueryExecutor().processNotifies(timeoutMillis);
    // Backwards-compatibility hand-holding.
    PGNotification[] notifications = queryExecutor.getNotifications();
    return notifications;
  }

  /**
   * Handler for transaction queries.
   */
  private class TransactionCommandHandler extends ResultHandlerBase {
    public void handleCompletion() throws SQLException {
      SQLWarning warning = getWarning();
      if (warning != null) {
        PgConnection.this.addWarning(warning);
      }
      super.handleCompletion();
    }
  }

  public int getPrepareThreshold() {
    return prepareThreshold;
  }

  public void setDefaultFetchSize(int fetchSize) throws SQLException {
    if (fetchSize < 0) {
      throw new PSQLException(GT.tr("Fetch size must be a value greater to or equal to 0."),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    this.defaultFetchSize = fetchSize;
    LOGGER.log(Level.FINE, "  setDefaultFetchSize = {0}", fetchSize);
  }

  public int getDefaultFetchSize() {
    return defaultFetchSize;
  }

  public void setPrepareThreshold(int newThreshold) {
    this.prepareThreshold = newThreshold;
    LOGGER.log(Level.FINE, "  setPrepareThreshold = {0}", newThreshold);
  }

  public boolean getForceBinary() {
    return forcebinary;
  }

  public void setForceBinary(boolean newValue) {
    this.forcebinary = newValue;
    LOGGER.log(Level.FINE, "  setForceBinary = {0}", newValue);
  }

  public void setTypeMapImpl(Map<String, Class<?>> map) throws SQLException {
    typemap = map;
  }

  public Logger getLogger() {
    return LOGGER;
  }

  public int getProtocolVersion() {
    return queryExecutor.getProtocolVersion();
  }

  public boolean getStringVarcharFlag() {
    return bindStringAsVarchar;
  }

  private @Nullable CopyManager copyManager;

  public CopyManager getCopyAPI() throws SQLException {
    checkClosed();
    if (copyManager == null) {
      copyManager = new CopyManager(this);
    }
    return copyManager;
  }

  public boolean binaryTransferSend(int oid) {
    return queryExecutor.useBinaryForSend(oid);
  }

  public int getBackendPID() {
    return queryExecutor.getBackendPID();
  }

  public boolean isColumnSanitiserDisabled() {
    return this.disableColumnSanitiser;
  }

  public void setDisableColumnSanitiser(boolean disableColumnSanitiser) {
    this.disableColumnSanitiser = disableColumnSanitiser;
    LOGGER.log(Level.FINE, "  setDisableColumnSanitiser = {0}", disableColumnSanitiser);
  }

  @Override
  public PreferQueryMode getPreferQueryMode() {
    return queryExecutor.getPreferQueryMode();
  }

  @Override
  public AutoSave getAutosave() {
    return queryExecutor.getAutoSave();
  }

  @Override
  public void setAutosave(AutoSave autoSave) {
    queryExecutor.setAutoSave(autoSave);
    LOGGER.log(Level.FINE, "  setAutosave = {0}", autoSave.value());
  }

  protected void abort() {
    queryExecutor.abort();
  }

  private Timer getTimer() {
    return finalizeAction.getTimer();
  }

  @Override
  public void addTimerTask(TimerTask timerTask, long milliSeconds) {
    Timer timer = getTimer();
    timer.schedule(timerTask, milliSeconds);
  }

  @Override
  public void purgeTimerTasks() {
    finalizeAction.purgeTimerTasks();
  }

  @Override
  public String escapeIdentifier(String identifier) throws SQLException {
    return Utils.escapeIdentifier(null, identifier).toString();
  }

  @Override
  public String escapeLiteral(String literal) throws SQLException {
    return Utils.escapeLiteral(null, literal, queryExecutor.getStandardConformingStrings())
        .toString();
  }

  @Override
  public LruCache<FieldMetadata.Key, FieldMetadata> getFieldMetadataCache() {
    return fieldMetadataCache;
  }

  @Override
  public PGReplicationConnection getReplicationAPI() {
    return new PGReplicationConnectionImpl(this);
  }

  // Parse a "dirty" integer surrounded by non-numeric characters
  private static int integerPart(String dirtyString) {
    int start = 0;

    while (start < dirtyString.length() && !Character.isDigit(dirtyString.charAt(start))) {
      ++start;
    }

    int end = start;
    while (end < dirtyString.length() && Character.isDigit(dirtyString.charAt(end))) {
      ++end;
    }

    if (start == end) {
      return 0;
    }

    return Integer.parseInt(dirtyString.substring(start, end));
  }

  @Override
  public Statement createStatement(int resultSetType, int resultSetConcurrency,
      int resultSetHoldability) throws SQLException {
    checkClosed();
    return new PgStatement(this, resultSetType, resultSetConcurrency, resultSetHoldability);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
      int resultSetHoldability) throws SQLException {
    checkClosed();
    return new PgPreparedStatement(this, sql, resultSetType, resultSetConcurrency,
        resultSetHoldability);
  }

  @Override
  public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
      int resultSetHoldability) throws SQLException {
    checkClosed();
    return new PgCallableStatement(this, sql, resultSetType, resultSetConcurrency,
        resultSetHoldability);
  }

  @Override
  public DatabaseMetaData getMetaData() throws SQLException {
    checkClosed();
    if (metadata == null) {
      metadata = new PgDatabaseMetaData(this);
    }
    return metadata;
  }

  @Override
  public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
    setTypeMapImpl(map);
    LOGGER.log(Level.FINE, "  setTypeMap = {0}", map);
  }

  protected Array makeArray(int oid, @Nullable String fieldString) throws SQLException {
    return new PgArray(this, oid, fieldString);
  }

  protected Blob makeBlob(long oid) throws SQLException {
    return new PgBlob(this, oid);
  }

  protected Clob makeClob(long oid) throws SQLException {
    return new PgClob(this, oid);
  }

  protected SQLXML makeSQLXML() throws SQLException {
    return new PgSQLXML(this);
  }

  @Override
  public Clob createClob() throws SQLException {
    if (clobAsText) {
      return new PgClobText();
    } else {
      throw com.aliyun.polardb2.Driver.notImplemented(this.getClass(), "createClob()");
    }
  }

  @Override
  public Blob createBlob() throws SQLException {
    if (blobAsBytea) {
      return new PgBlobBytea();
    } else {
      throw com.aliyun.polardb2.Driver.notImplemented(this.getClass(), "createBlob()");
    }
  }

  @Override
  public NClob createNClob() throws SQLException {
    checkClosed();
    throw com.aliyun.polardb2.Driver.notImplemented(this.getClass(), "createNClob()");
  }

  @Override
  public SQLXML createSQLXML() throws SQLException {
    checkClosed();
    return makeSQLXML();
  }

  @Override
  public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
    checkClosed();
    return new PgStruct(typeName, attributes);
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  @Override
  public Array createArrayOf(String typeName, @Nullable Object elements) throws SQLException {
    checkClosed();

    final TypeInfo typeInfo = getTypeInfo();

    final int oid = typeInfo.getPGArrayType(typeName);

    if (oid == Oid.UNSPECIFIED) {
      throw new PSQLException(GT.tr("Unable to find server array type for provided name {0}.", typeName),
          PSQLState.INVALID_NAME);
    }

    final char delim = typeInfo.getArrayDelimiter(oid);

    if (elements == null) {
      return makeArray(oid, null);
    }

    /* POLAR DIFF: Handle Object[][] for composite array types (TABLE OF).
     *
     * OracleArrayParameter.getOracleArray(conn) builds Object[][] where each
     * inner Object[] represents the fields of a composite record, then calls
     * conn.createArrayOf(tableName, data). Without this fix, ArrayEncoding
     * treats Object[][] as a 2-D array and produces {{"HHF01790","HH",...}}
     * instead of {"(HHF01790,HH,...)"}.
     *
     * PostgreSQL then parses the inner '{' as part of the first field value,
     * causing "value too long for type character varying(8 byte)" errors
     * when the first field is exactly at its length limit.
     *
     * Fix: Detect Object[][] with STRUCT element type and convert each inner
     * Object[] to a properly formatted record literal (val1,val2,...). */
    int elemOid = typeInfo.getPGArrayElement(oid);
    if (elemOid != Oid.UNSPECIFIED) {
      int elemSqlType = typeInfo.getSQLType(elemOid);
      if (elemSqlType == Types.STRUCT && isObjectArray2D(elements)) {
        String arrayString = buildCompositeArrayFromObject2D((Object[][]) elements, delim);
        return makeArray(oid, arrayString);
      }
    }
    /* POLAR DIFF end */

    final ArrayEncoding.ArrayEncoder arraySupport = ArrayEncoding.getArrayEncoder(elements);
    if (arraySupport.supportBinaryRepresentation(oid) && getPreferQueryMode() != PreferQueryMode.SIMPLE) {
      return new PgArray(this, oid, arraySupport.toBinaryRepresentation(this, elements, oid));
    }

    final String arrayString = arraySupport.toArrayString(delim, elements);

    /* POLAR DIFF: Fix String/Object[] elements for composite array types.
     * When createArrayOf is called with String[] or Object[] containing String
     * elements for a composite (record) element type, the encoder just quotes
     * the strings but does not add record literal parentheses (val1,val2,...).
     * Use PgArray.fixCompositeArrayElements to add missing (...)  wrappers. */
    if (elemOid != Oid.UNSPECIFIED) {
      int elemSqlType = typeInfo.getSQLType(elemOid);
      if (elemSqlType == Types.STRUCT) {
        return makeArray(oid, PgArray.fixCompositeArrayElements(arrayString, delim));
      }
    }
    /* POLAR DIFF end */

    return makeArray(oid, arrayString);
  }

  /**
   * POLAR: Check if the object is a 2-D Object array (Object[][]) where each
   * inner element is Object[] representing composite record fields.
   */
  private boolean isObjectArray2D(@Nullable Object obj) {
    if (obj == null) {
      return false;
    }
    Class<?> clazz = obj.getClass();
    if (!clazz.isArray()) {
      return false;
    }
    Class<?> componentType = clazz.getComponentType();
    if (componentType == null || !componentType.isArray()) {
      return false;
    }
    // Check if it's Object[][] (inner arrays are Object[])
    Class<?> innerComponentType = componentType.getComponentType();
    return innerComponentType != null && Object.class.equals(innerComponentType);
  }

  /**
   * POLAR: Convert Object[][] to a PostgreSQL array string where each inner
   * Object[] is formatted as a composite record literal (val1,val2,...).
   *
   * @param data the 2-D array where each inner array represents record fields
   * @param delim the array delimiter (usually ',')
   * @return a PostgreSQL array string like {"(val1,val2,...)","(val3,val4,...)"}
   */
  private String buildCompositeArrayFromObject2D(Object[][] data, char delim) {
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    for (int i = 0; i < data.length; i++) {
      if (i > 0) {
        sb.append(delim);
      }
      Object[] row = data[i];
      if (row == null) {
        sb.append("NULL");
      } else {
        // Build the record literal (val1,val2,...)
        StringBuilder recordSb = new StringBuilder();
        recordSb.append('(');
        for (int j = 0; j < row.length; j++) {
          if (j > 0) {
            recordSb.append(',');
          }
          Object val = row[j];
          if (val != null) {
            String strVal = val.toString();
            // Escape special characters in record field values
            // In composite literals, we need to escape quotes and backslashes
            // and quote values containing special chars (comma, paren, quote, backslash, whitespace)
            if (needsQuotingInRecord(strVal)) {
              recordSb.append('"');
              for (int k = 0; k < strVal.length(); k++) {
                char c = strVal.charAt(k);
                if (c == '"' || c == '\\') {
                  recordSb.append('\\');
                }
                recordSb.append(c);
              }
              recordSb.append('"');
            } else {
              recordSb.append(strVal);
            }
          }
          // null values are represented by empty string between commas in record literals
        }
        recordSb.append(')');
        // Escape the entire record as an array element
        PgArray.escapeArrayElement(sb, recordSb.toString());
      }
    }
    sb.append('}');
    return sb.toString();
  }

  /**
   * POLAR: Check if a string value needs quoting inside a composite record literal.
   * Values need quoting if they contain: comma, parenthesis, quote, backslash,
   * or leading/trailing whitespace.
   */
  private boolean needsQuotingInRecord(String s) {
    if (s.isEmpty()) {
      return false;
    }
    // Check for leading/trailing whitespace
    if (Character.isWhitespace(s.charAt(0)) || Character.isWhitespace(s.charAt(s.length() - 1))) {
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

  @Override
  public Array createArrayOf(String typeName, @Nullable Object @Nullable [] elements)
      throws SQLException {
    return createArrayOf(typeName, (Object) elements);
  }

  @Override
  public boolean isValid(int timeout) throws SQLException {
    if (timeout < 0) {
      throw new PSQLException(GT.tr("Invalid timeout ({0}<0).", timeout),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (isClosed()) {
      return false;
    }
    boolean changedNetworkTimeout = false;
    try {
      int oldNetworkTimeout = getNetworkTimeout();
      int newNetworkTimeout = (int) Math.min(timeout * 1000L, Integer.MAX_VALUE);
      try {
        // change network timeout only if the new value is less than the current
        // (zero means infinite timeout)
        if (newNetworkTimeout != 0 && (oldNetworkTimeout == 0 || newNetworkTimeout < oldNetworkTimeout)) {
          changedNetworkTimeout = true;
          setNetworkTimeout(null, newNetworkTimeout);
        }
        if (replicationConnection) {
          Statement statement = createStatement();
          statement.execute("IDENTIFY_SYSTEM");
          statement.close();
        } else {
          PreparedStatement checkConnectionQuery;
          try (ResourceLock ignore = lock.obtain()) {
            checkConnectionQuery = this.checkConnectionQuery;
            if (checkConnectionQuery == null) {
              checkConnectionQuery = prepareStatement("");
              this.checkConnectionQuery = checkConnectionQuery;
            }
          }
          checkConnectionQuery.executeUpdate();
        }
        return true;
      } finally {
        if (changedNetworkTimeout) {
          setNetworkTimeout(null, oldNetworkTimeout);
        }
      }
    } catch (SQLException e) {
      if (PSQLState.IN_FAILED_SQL_TRANSACTION.getState().equals(e.getSQLState())) {
        // "current transaction aborted", assume the connection is up and running
        return true;
      }
      LOGGER.log(Level.FINE, GT.tr("Validating connection."), e);
    }
    return false;
  }

  @Override
  public void setClientInfo(String name, @Nullable String value) throws SQLClientInfoException {
    try {
      checkClosed();
    } catch (final SQLException cause) {
      Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
      failures.put(name, ClientInfoStatus.REASON_UNKNOWN);
      throw new SQLClientInfoException(GT.tr("This connection has been closed."), failures, cause);
    }

    if (haveMinimumServerVersion(ServerVersion.v9_0) && "ApplicationName".equals(name)) {
      if (value == null) {
        value = "";
      }
      final String oldValue = queryExecutor.getApplicationName();
      if (value.equals(oldValue)) {
        return;
      }

      try {
        StringBuilder sql = new StringBuilder("SET application_name = '");
        Utils.escapeLiteral(sql, value, getStandardConformingStrings());
        sql.append("'");
        execSQLUpdate(sql.toString());
      } catch (SQLException sqle) {
        Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
        failures.put(name, ClientInfoStatus.REASON_UNKNOWN);
        throw new SQLClientInfoException(
            GT.tr("Failed to set ClientInfo property: {0}", "ApplicationName"), sqle.getSQLState(),
            failures, sqle);
      }
      if (LOGGER.isLoggable(Level.FINE)) {
        LOGGER.log(Level.FINE, "  setClientInfo = {0} {1}", new Object[]{name, value});
      }
      clientInfo.put(name, value);
      return;
    }

    addWarning(new SQLWarning(GT.tr("ClientInfo property not supported."),
        PSQLState.NOT_IMPLEMENTED.getState()));
  }

  @Override
  public void setClientInfo(Properties properties) throws SQLClientInfoException {
    try {
      checkClosed();
    } catch (final SQLException cause) {
      Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
      for (Map.Entry<Object, Object> e : properties.entrySet()) {
        failures.put((String) e.getKey(), ClientInfoStatus.REASON_UNKNOWN);
      }
      throw new SQLClientInfoException(GT.tr("This connection has been closed."), failures, cause);
    }

    Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
    for (String name : new String[]{"ApplicationName"}) {
      try {
        setClientInfo(name, properties.getProperty(name, null));
      } catch (SQLClientInfoException e) {
        failures.putAll(e.getFailedProperties());
      }
    }

    if (!failures.isEmpty()) {
      throw new SQLClientInfoException(GT.tr("One or more ClientInfo failed."),
          PSQLState.NOT_IMPLEMENTED.getState(), failures);
    }
  }

  @Override
  public @Nullable String getClientInfo(String name) throws SQLException {
    checkClosed();
    clientInfo.put("ApplicationName", queryExecutor.getApplicationName());
    return clientInfo.getProperty(name);
  }

  @Override
  public Properties getClientInfo() throws SQLException {
    checkClosed();
    clientInfo.put("ApplicationName", queryExecutor.getApplicationName());
    return clientInfo;
  }

  public <T> T createQueryObject(Class<T> ifc) throws SQLException {
    checkClosed();
    throw com.aliyun.polardb2.Driver.notImplemented(this.getClass(), "createQueryObject(Class<T>)");
  }

  public boolean getLogServerErrorDetail() {
    return logServerErrorDetail;
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    checkClosed();
    return iface.isAssignableFrom(getClass());
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    checkClosed();
    if (iface.isAssignableFrom(getClass())) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }

  public @Nullable String getSchema() throws SQLException {
    checkClosed();
    Statement stmt = createStatement();
    try {
      ResultSet rs = stmt.executeQuery("select current_schema()");
      try {
        if (!rs.next()) {
          return null; // Is it ever possible?
        }
        return rs.getString(1);
      } finally {
        rs.close();
      }
    } finally {
      stmt.close();
    }
  }

  public void setSchema(@Nullable String schema) throws SQLException {
    checkClosed();
    Statement stmt = createStatement();
    try {
      if (schema == null) {
        stmt.executeUpdate("SET SESSION search_path TO DEFAULT");
      } else {
        StringBuilder sb = new StringBuilder();
        sb.append("SET SESSION search_path TO '");
        Utils.escapeLiteral(sb, schema, getStandardConformingStrings());
        sb.append("'");
        stmt.executeUpdate(sb.toString());
        LOGGER.log(Level.FINE, "  setSchema = {0}", schema);
      }
    } finally {
      stmt.close();
    }
  }

  public class AbortCommand implements Runnable {
    public void run() {
      abort();
    }
  }

  public void abort(Executor executor) throws SQLException {
    if (executor == null) {
      throw new SQLException("executor is null");
    }
    if (isClosed()) {
      return;
    }

    SQL_PERMISSION_ABORT.checkGuard(this);

    AbortCommand command = new AbortCommand();
    executor.execute(command);
  }

  public void setNetworkTimeout(@Nullable Executor executor /*not used*/, int milliseconds)
      throws SQLException {
    checkClosed();

    if (milliseconds < 0) {
      throw new PSQLException(GT.tr("Network timeout must be a value greater than or equal to 0."),
              PSQLState.INVALID_PARAMETER_VALUE);
    }

    checkPermission(SQL_PERMISSION_NETWORK_TIMEOUT);

    try {
      queryExecutor.setNetworkTimeout(milliseconds);
    } catch (IOException ioe) {
      throw new PSQLException(GT.tr("Unable to set network timeout."),
              PSQLState.COMMUNICATION_ERROR, ioe);
    }
  }

  private void checkPermission(SQLPermission sqlPermissionNetworkTimeout) {
    if (SYSTEM_GET_SECURITY_MANAGER != null && SECURITY_MANAGER_CHECK_PERMISSION != null) {
      try {
        Object securityManager = SYSTEM_GET_SECURITY_MANAGER.invoke();
        if (securityManager != null) {
          SECURITY_MANAGER_CHECK_PERMISSION.invoke(securityManager, sqlPermissionNetworkTimeout);
        }
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }

  public int getNetworkTimeout() throws SQLException {
    checkClosed();

    try {
      return queryExecutor.getNetworkTimeout();
    } catch (IOException ioe) {
      throw new PSQLException(GT.tr("Unable to get network timeout."),
              PSQLState.COMMUNICATION_ERROR, ioe);
    }
  }

  @Override
  public void setHoldability(int holdability) throws SQLException {
    checkClosed();

    switch (holdability) {
      case ResultSet.CLOSE_CURSORS_AT_COMMIT:
        rsHoldability = holdability;
        break;
      case ResultSet.HOLD_CURSORS_OVER_COMMIT:
        rsHoldability = holdability;
        break;
      default:
        throw new PSQLException(GT.tr("Unknown ResultSet holdability setting: {0}.", holdability),
            PSQLState.INVALID_PARAMETER_VALUE);
    }
    LOGGER.log(Level.FINE, "  setHoldability = {0}", holdability);
  }

  @Override
  public int getHoldability() throws SQLException {
    checkClosed();
    return rsHoldability;
  }

  @Override
  public Savepoint setSavepoint() throws SQLException {
    checkClosed();

    String pgName;
    if (getAutoCommit()) {
      throw new PSQLException(GT.tr("Cannot establish a savepoint in auto-commit mode."),
          PSQLState.NO_ACTIVE_SQL_TRANSACTION);
    }

    PSQLSavepoint savepoint = new PSQLSavepoint(savepointId++);
    pgName = savepoint.getPGName();

    // Note we can't use execSQLUpdate because we don't want
    // to suppress BEGIN.
    Statement stmt = createStatement();
    stmt.executeUpdate("SAVEPOINT " + pgName);
    stmt.close();

    return savepoint;
  }

  @Override
  public Savepoint setSavepoint(String name) throws SQLException {
    checkClosed();

    if (getAutoCommit()) {
      throw new PSQLException(GT.tr("Cannot establish a savepoint in auto-commit mode."),
          PSQLState.NO_ACTIVE_SQL_TRANSACTION);
    }

    PSQLSavepoint savepoint = new PSQLSavepoint(name);

    // Note we can't use execSQLUpdate because we don't want
    // to suppress BEGIN.
    Statement stmt = createStatement();
    stmt.executeUpdate("SAVEPOINT " + savepoint.getPGName());
    stmt.close();

    return savepoint;
  }

  @Override
  public void rollback(Savepoint savepoint) throws SQLException {
    checkClosed();

    PSQLSavepoint pgSavepoint = (PSQLSavepoint) savepoint;
    execSQLUpdate("ROLLBACK TO SAVEPOINT " + pgSavepoint.getPGName());
  }

  @Override
  public void releaseSavepoint(Savepoint savepoint) throws SQLException {
    checkClosed();

    PSQLSavepoint pgSavepoint = (PSQLSavepoint) savepoint;
    execSQLUpdate("RELEASE SAVEPOINT " + pgSavepoint.getPGName());
    pgSavepoint.invalidate();
  }

  @Override
  public Statement createStatement(int resultSetType, int resultSetConcurrency)
      throws SQLException {
    checkClosed();
    return createStatement(resultSetType, resultSetConcurrency, getHoldability());
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    checkClosed();
    return prepareStatement(sql, resultSetType, resultSetConcurrency, getHoldability());
  }

  @Override
  public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    checkClosed();
    return prepareCall(sql, resultSetType, resultSetConcurrency, getHoldability());
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
    if (autoGeneratedKeys != Statement.RETURN_GENERATED_KEYS) {
      return prepareStatement(sql);
    }

    return prepareStatement(sql, (String[]) null);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int @Nullable [] columnIndexes) throws SQLException {
    if (columnIndexes != null && columnIndexes.length == 0) {
      return prepareStatement(sql);
    }

    checkClosed();
    throw new PSQLException(GT.tr("Returning autogenerated keys is not supported."),
        PSQLState.NOT_IMPLEMENTED);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, String @Nullable[] columnNames) throws SQLException {
    if (columnNames != null && columnNames.length == 0) {
      return prepareStatement(sql);
    }

    CachedQuery cachedQuery = borrowReturningQuery(sql, columnNames);
    PgPreparedStatement ps =
        new PgPreparedStatement(this, cachedQuery,
            ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_READ_ONLY,
            getHoldability());
    Query query = cachedQuery.query;
    SqlCommand sqlCommand = query.getSqlCommand();
    if (sqlCommand != null) {
      ps.wantsGeneratedKeysAlways = sqlCommand.isReturningKeywordPresent();
    } else {
      // If composite query is given, just ignore "generated keys" arguments
    }
    return ps;
  }

  @Override
  public final Map<String,String> getParameterStatuses() {
    return queryExecutor.getParameterStatuses();
  }

  @Override
  public final @Nullable String getParameterStatus(String parameterName) {
    return queryExecutor.getParameterStatus(parameterName);
  }

  @Override
  public boolean getAdaptiveFetch() {
    return queryExecutor.getAdaptiveFetch();
  }

  @Override
  public void setAdaptiveFetch(boolean adaptiveFetch) {
    queryExecutor.setAdaptiveFetch(adaptiveFetch);
  }

  @Override
  public PGXmlFactoryFactory getXmlFactoryFactory() throws SQLException {
    PGXmlFactoryFactory xmlFactoryFactory = this.xmlFactoryFactory;
    if (xmlFactoryFactory != null) {
      return xmlFactoryFactory;
    }
    if (xmlFactoryFactoryClass == null || xmlFactoryFactoryClass.equals("")) {
      xmlFactoryFactory = DefaultPGXmlFactoryFactory.INSTANCE;
    } else if (xmlFactoryFactoryClass.equals("LEGACY_INSECURE")) {
      xmlFactoryFactory = LegacyInsecurePGXmlFactoryFactory.INSTANCE;
    } else {
      Class<?> clazz;
      try {
        clazz = Class.forName(xmlFactoryFactoryClass);
      } catch (ClassNotFoundException ex) {
        throw new PSQLException(
            GT.tr("Could not instantiate xmlFactoryFactory: {0}", xmlFactoryFactoryClass),
            PSQLState.INVALID_PARAMETER_VALUE, ex);
      }
      if (!clazz.isAssignableFrom(PGXmlFactoryFactory.class)) {
        throw new PSQLException(
            GT.tr("Connection property xmlFactoryFactory must implement PGXmlFactoryFactory: {0}", xmlFactoryFactoryClass),
            PSQLState.INVALID_PARAMETER_VALUE);
      }
      try {
        xmlFactoryFactory = (PGXmlFactoryFactory) clazz.newInstance();
      } catch (Exception ex) {
        throw new PSQLException(
            GT.tr("Could not instantiate xmlFactoryFactory: {0}", xmlFactoryFactoryClass),
            PSQLState.INVALID_PARAMETER_VALUE, ex);
      }
    }
    this.xmlFactoryFactory = xmlFactoryFactory;
    return xmlFactoryFactory;
  }

  @Override
  public boolean isMapDateToTimestamp() {
    return mapDateToTimestamp;
  }

  @Override
  public PolarDriverPrefix getDriverPrefix() {
    return driverPrefix;
  }

  /* POLAR */
  @Override
  public boolean isBoolAsInt() {
    return boolAsInt;
  }

  @Override
  public boolean isBigintAsNumeric() {
    return bigintAsNumeric;
  }

  @Override
  public boolean isNumberStripTrailingZeros() {
    return numberStripTrailingZeros;
  }

  @Override
  public void setDriverPrefix(PolarDriverPrefix polarDriverPrefix) {
    driverPrefix = polarDriverPrefix;
  }

  @Override
  public boolean isOracleCase() {
    return oracleCase == 1;
  }

  @Override
  public boolean isOracleCaseStrict() {
    return oracleCase == 2;
  }

  @Override
  public boolean isAutoCommitSpecCompliant() {
    return autoCommitSpecCompliant;
  }

  @Override
  public boolean isNamedParam() {
    return namedParam;
  }

  @Override
  public boolean isOraCommentStyle() {
    return commentStyle;
  }

  @Override
  public boolean isCollectWarning() {
    return collectWarning;
  }

  // clob_as_text getter
  @Override
  public boolean getClobAsText() {
    return clobAsText;
  }

  // blob_as_bytea getter
  @Override
  public boolean getBlobAsBytea() {
    return blobAsBytea;
  }

  @Override
  public boolean isBlobUpperHex() {
    return blobUpperHex;
  }

  @Override
  public int defaultPolarMaxFetchSize() {
    return defaultPolarMaxFetchSize;
  }

  @Override
  public boolean callFunctionMode() {
    return callFunctionMode;
  }

  @Override
  public boolean isForceDriverType() {
    return forceDriverType;
  }

  public boolean isAllowSelectInExecuteUpdate() {
    return allowSelectInExecuteUpdate;
  }
}
