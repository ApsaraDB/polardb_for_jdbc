# PolarDB JDBC Driver

[![Maven Central](https://img.shields.io/maven-central/v/com.aliyun/polardb.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.aliyun/polardb)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
![Java 8+](https://img.shields.io/badge/Java-8%2B-orange.svg)
![JDBC 4.2](https://img.shields.io/badge/JDBC-4.2-blue.svg)

PolarDB JDBC Driver allows Java programs to connect to a PolarDB for PostgreSQL database
(including the Oracle-compatible editions) using standard, database independent Java code.
It is an open source JDBC driver written in Pure Java (Type 4), derived from the
[PostgreSQL JDBC Driver (PgJDBC)](https://github.com/pgjdbc/pgjdbc), and communicates in the
PostgreSQL native network protocol.

On top of PgJDBC, this driver adds a large set of Oracle compatibility features for PolarDB,
such as Oracle-style `DATE`/`NUMBER` handling, `BLOB`/`CLOB` mapping, associative arrays,
`REF CURSOR`, named parameters and more. See [PolardbRealease.md](PolardbRealease.md) for the
full user manual (in Chinese) of the PolarDB-specific features and parameters.

## Supported PolarDB / PostgreSQL and Java versions

The driver requires **Java 8** (JDBC 4.2) or above, and is compatible with:

* **PolarDB for PostgreSQL** (Oracle-compatible 1.0 and 2.0 editions)
* **PostgreSQL 8.4 and higher** using version 3.0 of the wire protocol

If you find a bug or regression, please file an
[Issue](https://github.com/ApsaraDB/polardb_for_jdbc/issues).

## Get the Driver

### Maven Central

The driver is available on [Maven Central](https://central.sonatype.com/artifact/com.aliyun/polardb).
Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>polardb</artifactId>
    <version>42.5.7.0.16</version>
</dependency>
```

Or with Gradle:

```kotlin
implementation("com.aliyun:polardb:42.5.7.0.16")
```

The precompiled driver (jar) can also be downloaded from the
[Alibaba Cloud documentation](https://help.aliyun.com/zh/polardb/polardb-for-oracle/polardb-compatible-with-oracle-drive-2/)
for PolarDB.

### Building from source

The project is built with Gradle. JDK 8 or newer is required.

```bash
# Build the driver jar (skipping tests)
./gradlew build -x test

# The jar is generated under
# pgjdbc/build/libs/
```

To run the test suite a running PolarDB/PostgreSQL instance is required, see
[TESTING.md](TESTING.md) and [PolarDB_conf.md](PolarDB_conf.md) for the test
environment setup.

## Quick Start

```java
Class.forName("com.aliyun.polardb2.Driver");

String url = "jdbc:polardb://localhost:5432/test";
Properties props = new Properties();
props.setProperty("user", "test");
props.setProperty("password", "test");

try (Connection conn = DriverManager.getConnection(url, props);
     Statement st = conn.createStatement();
     ResultSet rs = st.executeQuery("SELECT 1")) {
    while (rs.next()) {
        System.out.println(rs.getInt(1));
    }
}
```

## Documentation

* [PolardbRealease.md](PolardbRealease.md) - PolarDB JDBC user manual: PolarDB-specific
  connection parameters, Oracle-compatible data type behaviour and feature notes (Chinese)
* [PolarDB_conf.md](PolarDB_conf.md) - test environment configuration reference
* [PgJDBC driver documentation](https://jdbc.postgresql.org/documentation/) - documentation of
  the upstream driver this project is based on
* [The Java™ Tutorials (JDBC)](https://docs.oracle.com/javase/tutorial/jdbc/) - general JDBC
  documentation

### Driver and DataSource class

| Implements                          | Class                                              |
| ----------------------------------- | -------------------------------------------------- |
| java.sql.Driver                     | **com.aliyun.polardb2.Driver**                     |
| javax.sql.DataSource                | com.aliyun.polardb2.ds.PGSimpleDataSource          |
| javax.sql.ConnectionPoolDataSource  | com.aliyun.polardb2.ds.PGConnectionPoolDataSource  |
| javax.sql.XADataSource              | com.aliyun.polardb2.xa.PGXADataSource              |

### Building the Connection URL

The driver recognises JDBC URLs of the form:
```
jdbc:polardb:database
jdbc:polardb:
jdbc:polardb://host/database
jdbc:polardb://host/
jdbc:polardb://host:port/database
jdbc:polardb://host:port/
jdbc:polardb://?service=myservice
```
The general format for a JDBC URL for connecting to a PolarDB server is as follows, with items in square brackets ([ ]) being optional:
```
jdbc:polardb:[//host[:port]/][database][?property1=value1[&property2=value2]...]
```
where:
 * **jdbc:polardb:** (Required) is known as the sub-protocol and is constant.
 * **host** (Optional) is the server address to connect. This could be a DNS or IP address, or it could be *localhost* or *127.0.0.1* for the local computer. To specify an IPv6 address your must enclose the host parameter with square brackets (jdbc:polardb://[::1]:5740/accounting). Defaults to `localhost`.
 * **port** (Optional) is the port number listening on the host. Defaults to `5432`.
 * **database** (Optional) is the database name. Defaults to the same name as the *user name* used in the connection.
 * **propertyX** (Optional) is one or more option connection properties. For more information see *Connection properties*.

### Logging

The driver uses java.util.logging for logging.
To configure log levels and control log output destination (e.g. file or console), configure your java.util.logging properties accordingly for the com.aliyun.polardb2 logger.
Note that the most detailed log levels, "`FINEST`", may include sensitive information such as connection details, query SQL, or command parameters.

#### Connection Properties

In addition to the standard connection parameters the driver supports a number of additional properties which can be used to specify additional driver behaviour specific to PolarDB. These properties may be specified in either the connection URL or an additional Properties object parameter to DriverManager.getConnection.

Note: the PolarDB-specific properties (e.g. `blobAsBytea`, `clobAsText`, `mapDateToTimestamp`, `oracleCase`, `namedParam`, `numberStripTrailingZeros`, ...) are documented in detail in [PolardbRealease.md](PolardbRealease.md).

| Property                      | Type |         Default         | Description                                                                                                                                                                                                                                                                                                                                     |
|-------------------------------| -- |:-----------------------:|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------    |
| user                          | String |          null           | The database user on whose behalf the connection is being made.                                                                                                                                                                                                                                                                               |
| password                      | String |          null           | The database user's password.                                                                                                                                                                                                                                                                                                                 |
| options                       | String |          null           | Specify 'options' connection initialization parameter.                                                                                                                                                                                                                                                                                        |
| service                       | String |          null           | Specify 'service' name described in pg_service.conf file. References: [The Connection Service File](https://www.postgresql.org/docs/current/libpq-pgservice.html) and [The Password File](https://www.postgresql.org/docs/current/libpq-pgpass.html). 'service' file can provide all properties including 'hostname=', 'port=' and 'dbname='. |
| ssl                           | Boolean |          false          | Control use of SSL (true value causes SSL to be required)                                                                                                                                                                                                                                                                                    |
| sslfactory                    | String | com.aliyun.polardb2.ssl.LibPQFactory | Provide a SSLSocketFactory class when using SSL.                                                                                                                                                                                                                                                                                      |
| sslfactoryarg (deprecated)    | String |          null           | Argument forwarded to constructor of SSLSocketFactory class.                                                                                                                                                                                                                                                                                  |
| sslmode                       | String |         prefer          | Controls the preference for opening using an SSL encrypted connection.                                                                                                                                                                                                                                                                        |
| sslcert                       | String |          null           | The location of the client's SSL certificate                                                                                                                                                                                                                                                                                                  |
| sslkey                        | String |          null           | The location of the client's PKCS#8 SSL key                                                                                                                                                                                                                                                                                                   |
| sslrootcert                   | String |          null           | The location of the root certificate for authenticating the server.                                                                                                                                                                                                                                                                           |
| sslhostnameverifier           | String |          null           | The name of a class (for use in [Class.forName(String)](https://docs.oracle.com/javase/8/docs/api/java/lang/Class.html#forName-java.lang.String-)) that implements javax.net.ssl.HostnameVerifier and can verify the server hostname.                                                                                                     |
| sslpasswordcallback           | String |          null           | The name of a class (for use in [Class.forName(String)](https://docs.oracle.com/javase/8/docs/api/java/lang/Class.html#forName-java.lang.String-)) that implements javax.security.auth.callback.CallbackHandler and can handle PasswordCallback for the ssl password.                                                                     |
| sslpassword                   | String |          null           | The password for the client's ssl key (ignored if sslpasswordcallback is set)                                                                                                                                                                                                                                                                 |
| sendBufferSize                | Integer |           -1            | Socket write buffer size                                                                                                                                                                                                                                                                                                                      |
| receiveBufferSize             | Integer |           -1            | Socket read buffer size                                                                                                                                                                                                                                                                                                                       |
| logServerErrorDetail          | Boolean |          true           | Allows server error detail (such as sql statements and values) to be logged and passed on in exceptions.  Setting to false will mask these errors so they won't be exposed to users, or logs.                                                                                                                                                 |
| allowEncodingChanges          | Boolean |          false          | Allow for changes in client_encoding                                                                                                                                                                                                                                                                                                          |
| logUnclosedConnections        | Boolean |          false          | When connections that are not explicitly closed are garbage collected, log the stacktrace from the opening of the connection to trace the leak source                                                                                                                                                                                         |
| binaryTransfer                | Boolean |          true           | Use binary format for sending and receiving data if possible. Setting this to false disables any binary transfer                                                                                                                                                                                                                              |
| binaryTransferEnable          | String |           ""            | Comma separated list of types to enable binary transfer. Either OID numbers or names                                                                                                                                                                                                                                                          |
| binaryTransferDisable         | String |           ""            | Comma separated list of types to disable binary transfer. Either OID numbers or names. Overrides values in the driver default set and values set with binaryTransferEnable.                                                                                                                                                                   |
| prepareThreshold              | Integer |            5            | Statement prepare threshold. A value of -1 stands for forceBinary                                                                                                                                                                                                                                                                             |
| preparedStatementCacheQueries | Integer |           256           | Specifies the maximum number of entries in per-connection cache of prepared statements. A value of 0 disables the cache.                                                                                                                                                                                                                      |
| preparedStatementCacheSizeMiB | Integer |            5            | Specifies the maximum size (in megabytes) of a per-connection prepared statement cache. A value of 0 disables the cache.                                                                                                                                                                                                                      |
| defaultRowFetchSize           | Integer |            0            | Positive number of rows that should be fetched from the database when more rows are needed for ResultSet by each fetch iteration                                                                                                                                                                                                              |
| loginTimeout                  | Integer |            0            | Specify how long in seconds max(2147484) to wait for establishment of a database connection.                                                                                                                                                                                                                                                  |
| connectTimeout                | Integer |           10            | The timeout value in seconds max(2147484) used for socket connect operations.                                                                                                                                                                                                                                                                 |
| socketTimeout                 | Integer |            0            | The timeout value in seconds max(2147484) used for socket read operations.                                                                                                                                                                                                                                                                    |
| sslResponseTimeout            | Integer |          5000           | Socket timeout in milliseconds waiting for a response from a request for SSL upgrade from the server.                                                                                                                                                                                                                                         |
| tcpKeepAlive                  | Boolean |          false          | Enable or disable TCP keep-alive.                                                                                                                                                                                                                                                                                                             |
| tcpNoDelay                    | Boolean |          true           | Enable or disable TCP no delay.                                                                                                                                                                                                                                                                                                               |
| ApplicationName               | String  | PostgreSQL JDBC Driver   | The application name (require server version >= 9.0). If assumeMinServerVersion is set to >= 9.0 this will be sent in the startup packets, otherwise after the connection is made                                                                                                                                                             |
| readOnly                      | Boolean |          false          | Puts this connection in read-only mode                                                                                                                                                                                                                                                                                                        |
| disableColumnSanitiser        | Boolean |          false          | Enable optimization that disables column name sanitiser                                                                                                                                                                                                                                                                                       |
| assumeMinServerVersion        | String |          null           | Assume the server is at least that version                                                                                                                                                                                                                                                                                                    |
| currentSchema                 | String |          null           | Specify the schema (or several schema separated by commas) to be set in the search-path                                                                                                                                                                                                                                                       |
| targetServerType              | String |           any           | Specifies what kind of server to connect, possible values: any, master, slave (deprecated), secondary, preferSlave (deprecated), preferSecondary, preferPrimary                                                                                                                                                                               |
| hostRecheckSeconds            | Integer |           10            | Specifies period (seconds) after which the host status is checked again in case it has changed                                                                                                                                                                                                                                                |
| loadBalanceHosts              | Boolean |          false          | If disabled hosts are connected in the given order. If enabled hosts are chosen randomly from the set of suitable candidates                                                                                                                                                                                                                  |
| socketFactory                 | String |          null           | Specify a socket factory for socket creation                                                                                                                                                                                                                                                                                                  |
| socketFactoryArg (deprecated) | String |          null           | Argument forwarded to constructor of SocketFactory class.                                                                                                                                                                                                                                                                                     |
| autosave                      | String |          never          | Specifies what the driver should do if a query fails, possible values: always, never, conservative                                                                                                                                                                                                                                            |
| cleanupSavepoints             | Boolean |          false          | In Autosave mode the driver sets a SAVEPOINT for every query. It is possible to exhaust the server shared buffers. Setting this to true will release each SAVEPOINT at the cost of an additional round trip.                                                                                                                                  |
| preferQueryMode               | String |        extended         | Specifies which mode is used to execute queries to database, possible values: extended, extendedForPrepared, extendedCacheEverything, simple                                                                                                                                                                                                  |
| reWriteBatchedInserts         | Boolean |          false          | Enable optimization to rewrite and collapse compatible INSERT statements that are batched.                                                                                                                                                                                                                                                    |
| escapeSyntaxCallMode          | String |         select          | Specifies how JDBC escape call syntax is transformed into underlying SQL (CALL/SELECT), for invoking procedures or functions (requires server version >= 11), possible values: select, callIfNoReturn, call                                                                                                                                   |
| maxResultBuffer               | String |          null           | Specifies size of result buffer in bytes, which can't be exceeded during reading result set. Can be specified as particular size (i.e. "100", "200M" "2G") or as percent of max heap memory (i.e. "10p", "20pct", "50percent")                                                                                                                |
| gssLib                        | String |          auto           | Permissible values are auto (default, see below), sspi (force SSPI) or gssapi (force GSSAPI-JSSE).                                                                                                                                                                                                                                            |
| gssResponseTimeout            | Integer |          5000           | Socket timeout in milliseconds waiting for a response from a request for GSS encrypted connection from the server.                                                                                                                                                                                                                            |
| gssEncMode                    | String |          allow          | Controls the preference for using GSSAPI encryption for the connection, values are disable, allow, prefer, and require                                                                                                                                                                                                                        |
| useSpnego                     | String |          false           | Use SPNEGO in SSPI authentication requests                                
| adaptiveFetch                 | Boolean |          false          | Specifies if number of rows fetched in ResultSet by each fetch iteration should be dynamic. Number of rows will be calculated by dividing maxResultBuffer size into max row size observed so far. Requires declaring maxResultBuffer and defaultRowFetchSize for first iteration.                                                             |
| adaptiveFetchMinimum          | Integer |            0            | Specifies minimum number of rows, which can be calculated by adaptiveFetch. Number of rows used by adaptiveFetch cannot go below this value.                                                                                                                                                                                                  |
| adaptiveFetchMaximum          | Integer |           -1            | Specifies maximum number of rows, which can be calculated by adaptiveFetch. Number of rows used by adaptiveFetch cannot go above this value. Any negative number set as adaptiveFetchMaximum is used by adaptiveFetch as infinity number of rows.                                                                                             |
| localSocketAddress            | String |          null           | Hostname or IP address given to explicitly configure the interface that the driver will bind the client side of the TCP/IP connection to when connecting.                                                                                                                                                                                     |
| quoteReturningIdentifiers     | Boolean |          true           | By default we double quote returning identifiers. Some ORM's already quote them. Switch allows them to turn this off                                                                                                                                                                                                                          |
| authenticationPluginClassName | String |          null           | Fully qualified class name of the class implementing the AuthenticationPlugin interface. If this is null, the password value in the connection properties will be used.                                                                                                                                                                       |
| unknownLength                 | Integer |   Integer.MAX_LENGTH    | Specifies the length to return for types of unknown length                                                                                                                                                                                                                                                                                    |
| stringtype                    | String |          null           | Specify the type to use when binding `PreparedStatement` parameters set via `setString()`                                                                                                                                                                                                                                                     |
| blobAsBytea                   | Boolean | false   | Treat Blobs as bytea fields instead of as LargeObjects |
| clobAsText                    | Boolean | false  | Treat Clobs as text fields instead of as LargeObjects |
| channelBinding                 | String |   prefer    | This option controls the client's use of channel binding. `require` means that the connection must employ channel binding, `prefer` means that the client will choose channel binding if available, and `disable` prevents the use of channel binding. |

#### System Properties

| Property                      | Type |         Default         | Description                                                                                                                                                                                                                                                                                                                                     |
|-------------------------------| -- |:-----------------------:|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------    |
| pgjdbc.config.cleanup.thread.ttl | long | 30000 |  The driver has an internal cleanup thread which monitors and cleans up unclosed connections. This property sets the duration (in milliseconds) the cleanup thread will keep running if there is nothing to clean up. |

## Contributing

For information on how to contribute to the project see the [Contributing Guidelines](CONTRIBUTING.md)

## License

This project is distributed under the [Apache License, Version 2.0](LICENSE),
copyright Alibaba Group Holding Limited. It is derived from the PostgreSQL JDBC
Driver, originally distributed under the BSD-2-Clause License, copyright
PostgreSQL Global Development Group.
