# \[5\] JDBC用户手册

## 概述 

本文将介绍如何在Java应用中使用JDBC连接PolarDB PostgreSQL版（兼容Oracle 1.0版本、兼容Oracle 2.0版本）数据库。

## 前提条件

*   已经在PolarDB集群创建用户，如何创建用户请参见[创建数据库账号](https://help.aliyun.com/document_detail/118194.htm#task-1580301)。
    
*   已经将需要访问PolarDB集群的主机IP地址添加到白名单，如何添加白名单请参见[设置集群白名单](https://help.aliyun.com/document_detail/118183.htm#task-1580301)。
    

## 背景信息

JDBC（Java Database Connectivity）为Java应用程序提供了访问数据库的编程接口。PolarDB PostgreSQL版（兼容Oracle 1.0版本、兼容Oracle 2.0版本）数据库的JDBC是基于开源的PostgreSQL JDBC开发而来，使用PostgreSQL本地网络协议进行通信，允许Java程序使用标准的、独立于数据库的Java代码连接数据库。

### 下载驱动

#### Maven 中心仓库（推荐）

驱动已发布至 [Maven 中心仓库](https://central.sonatype.com/artifact/com.aliyun/polardb)，在 `pom.xml` 中添加如下依赖即可（要求 JDK 1.8 及以上）：

```xml
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>polardb</artifactId>
    <version>42.5.7.0.15</version>
</dependency>
```

Gradle 方式：

```kotlin
implementation("com.aliyun:polardb:42.5.7.0.15")
```

#### 独立 jar 包下载

| JDK版本 | 独立版本 |
| --- | --- |
| 1.6 | [请至钉钉文档查看附件《polardb-42.2.13.0.11.jre6.jar》。](https://alidocs.dingtalk.com/i/nodes/gwva2dxOW4vRkd9DUNL65LqnJbkz3BRL?corpId=&iframeQuery=anchorId%3DX02mcwzwed8ba00dwg0b2m) |
| 1.7 | [请至钉钉文档查看附件《polardb-42.2.13.0.11.jre7.jar》。](https://alidocs.dingtalk.com/i/nodes/gwva2dxOW4vRkd9DUNL65LqnJbkz3BRL?corpId=&iframeQuery=anchorId%3DX02mcwzwfhiz0be9frfg2) |
| 1.8 | [请至钉钉文档查看附件《polardb-42.5.7.0.14.jar》。](https://alidocs.dingtalk.com/i/nodes/gwva2dxOW4vRkd9DUNL65LqnJbkz3BRL?corpId=&iframeQuery=anchorId%3DX02mjjsnp9vt5lbpr6msz) |

注意：如果您需要同时连接1.0版本的数据库，请使用兼容此版本的驱动程序。在其他所有情况下，建议使用独立版本的驱动程序。需要特别指出的是，未来的更新将主要针对独立版本进行优化和发布。

## 重点功能介绍

### 连接级参数功能

下面介绍2.0JDBC中，连接级别参数功能，以下功能都通过一个连接参数配置，支持的参数如下列表所示。所有的新增参数的生效范围都控制为连接级别，随Connection的生命周期生效。

| 参数名 | 默认值 | 可选值 | 释义 |
| --- | --- | --- | --- |
| autoCommit | true | true/false | 参数形式的自动提交 |
| autoCommitSpecCompliant | true | true/false | 是否允许自动提交下继续调用commit/rollback方法 |
| blobAsBytea | true（false on 1.0） | true/false | Oracle 兼容的 BLOB |
| clobAsText | true（false on 1.0） | true/false | Oracle 兼容的 CLOB |
| collectWarning | true | true/false | 是否收集告警（防止内存溢出） |
| defaultPolarMaxFetchSize | 0 | \[数字\] | 配合MaxFetchSize实现结果集条数控制 |
| extraFloatDigits | null | \[数字\] | 小数长度 |
| mapDateToTimestamp | true | true/false | 对应将date类型转为Timestamp |
| namedParam | false | true/false | 是否支持通过：xxx绑定参数 |
| oracleCase | false | true/false/strict | 是否返回列名、表名的大写 |
| resetNlsFormat | false | true/false | 启用后在连接初始化时重置 nls_date_format/nls_timestamp_format/nls_timestamptz_format 为标准格式，以确保日期时间解析一致性 |
| boolAsInt | false | true/false | 是否支持Oracle语义的布尔值表示<br>*   True：布尔值表示为1/0<br>    <br>*   False（默认）：布尔值表示为True/False |
| numberStripTrailingZeros | true | true/false | 是否对 NUMERIC/DECIMAL 类型去除尾部零。启用后 getString()/getObject() 返回值去除小数尾零（如 `911.000` → `911`，`3.10` → `3.1`），行为与 Oracle NUMBER 一致 |
| bigintAsNumeric | true | true/false | 启用后 BIGINT 列的 getObject() 返回 BigDecimal 而非 Long，便于与 Oracle NUMBER 行为对齐（如 COUNT(*) 结果直接作为 BigDecimal 使用） |
| allowSelectInExecuteUpdate | true | true/false | 允许 executeUpdate() 执行 SELECT 语句而不抛异常，返回更新计数为 0。兼容 Oracle 业务中将 SELECT 作为 executeUpdate 调用的场景 |
| blobUpperHex | true | true/false | bytea/blob 列的 getString() 返回大写十六进制无前缀格式（如 `AABBCC`），而非 PostgreSQL 标准的 `\xaabbcc` 格式，与 Oracle RAW 输出一致 |
| unknownLength | 4000 | \[数字\] | 未知长度类型的默认返回长度，设为 4000 以兼容 Oracle VARCHAR2 最大长度限制 |

### 数据类型解析

● 【Date类型】64位DATE类型的支持 内核支持了64位的DATE，数据表示格式与Oracle相同，带有时分秒信息，对应驱动可以以Timestamp的方式去处理这个Date。将所有的date类型（Types.DATE，或者DATEOID）映射成Timestamp类型；驱动将Date视为Timestamp进行处理。

● 【Interval类型】支持Oracle模式的Interval输入 PG社区的驱动不支持形如+12 12:03:12.111的Interval输入格式，由于目前Oracle模式下这种形式是标准输出，因此支持这种形式的输出。

● 【Number类型】支持NUMBER的GET行为 Java.sql的标准实现中没有getNumber相关的函数，只有getInt等函数。如果一个函数的参数类型是NUMBER，允许使用getInt、setInt、RegisterParam等接口将参数以Int形式传递。

● 【Blob类型】Blob处理为Bytea，Clob处理为Text 针对Java.sql.Blob和Java.sql.Clob接口的实现；内核已经为Blob、Clob添加了映射；在Java层面也按照Bytea、Text的方式去处理。主类实现了getBytes、setBytes、position、getBinaryStream等方法。

*   【Boolean类型】支持布尔类型转义为1/0
    

为了保证老版本的兼容性，`setBoolean` 接口方法在设置时默认采用 `True/False`。然而，用户可以通过激活 `boolAsInt` 参数来切换至与 Oracle 兼容的 `1/0` 语义，以此适应Oracle兼容的数据库交互需求。

*   注意：针对数字类型转为boolean类型，老版本（<=42.5.4.0.10）处理规则为：1 或相当于 1的数字视为True；0或相当于0的数字视为False，其他数字值驱动报错；新版本（>=42.5.4.0.11) 处理规则为：0或相当于0的数字视为False，其他非0的数字值都视为True，新版本驱动这一行为与Oracle驱动保持一致。
    

### PLSQL适配

● 支持不带$$符号的存储过程 支持在创建FUNCTION/PROCEDURE等过程时省略$$符号，并支持在语法解析时截断/字符。

● 支持冒号变量名作为参数 支持使用:xxx这种方式传递参数，其中xxx为冒号开头的变量名。

● 支持匿名块绑定参数

● 支持屏蔽PLSQL的警告信息 防止循环中存储过多的警告信息导致内存超限。

### Oracle q-quote 字面量语法

自 **42.5.7.0.14** 版本起，驱动完整支持 Oracle q-quote 语法的所有合法 delimiter（包括 `'` 作 delimiter 的 `q''..''` 特殊形态）。在 PreparedStatement 中 q-quote 内部的 `?` 和 `:xxx` 不会被误判为绑定参数：

```sql
-- 标准 q-quote：使用 [ ] 作为定界符
SELECT q'[It's a test]' FROM dual;
-- 结果: It's a test

-- 单引号作 delimiter 的特殊形态
SELECT q''te:s't ? :44'' FROM dual;
-- 结果: te:s't ? :44
```

```java
// q-quote 内的 ? 和 :param 不会被识别为绑定参数
PreparedStatement ps = conn.prepareStatement(
    "SELECT q'[where id = ? and name = :test]' FROM dual");
ResultSet rs = ps.executeQuery();
rs.next();
System.out.println(rs.getString(1));
// 输出: where id = ? and name = :test
```

### TABLE OF / INDEX BY 集合类型

自 **42.5.7.0.14** 版本起，驱动支持通过 `CallableStatement` 接收 PolarDB 的 TABLE OF / INDEX BY 集合类型作为 OUT 参数。使用 `Types.ARRAY` 注册 OUT 参数，通过 `getArray()` 获取 `PgArray` 对象，其中保留了关联数组的 key 信息。

**典型用法：**

```java
// 假设数据库中有如下类型和函数：
// CREATE TYPE str_table IS TABLE OF VARCHAR2(100) INDEX BY BINARY_INTEGER;
// CREATE FUNCTION get_names() RETURN str_table IS ...

CallableStatement cs = conn.prepareCall("{ ? = call get_names() }");
cs.registerOutParameter(1, Types.ARRAY);
cs.execute();

// 方式一：直接获取 Array 的 Java 数组
Array arr = cs.getArray(1);
String[] values = (String[]) arr.getArray();
// values = ["alpha", "beta", "gamma"]

// 方式二：通过 ResultSet 遍历，并获取关联数组的 key
ResultSet rs = arr.getResultSet();
while (rs.next()) {
    int key = rs.getInt(1);      // 关联数组的 key（如 1, 2, 3）
    String val = rs.getString(2); // 对应的值
    System.out.println(key + " => " + val);
}
cs.close();
```

### 数值类型 OUT 参数自动转换

自 **42.5.7.0.14** 版本起，`CallableStatement` 的 OUT 参数支持全数值族类型自动互转。即使数据库函数返回 `BIGINT`，用户注册为 `Types.NUMERIC` 也能正确获取值，反之亦然。支持的类型包括：SMALLINT、INTEGER、BIGINT、NUMERIC、DECIMAL、REAL、FLOAT、DOUBLE。

**典型用法（适用于 DBMS_SQL.EXECUTE 等返回 BIGINT 的场景）：**

```java
// 数据库函数实际返回 BIGINT，但业务框架按 Oracle 惯例注册为 NUMERIC
CallableStatement cs = conn.prepareCall("{ ? = call dbms_sql.execute(?) }");
cs.registerOutParameter(1, Types.NUMERIC);  // 注册为 NUMERIC
cs.setInt(2, cursorId);
cs.execute();

// 驱动自动完成 BIGINT → NUMERIC 转换
BigDecimal result = cs.getBigDecimal(1);
System.out.println(result); // 正常获取值，无类型不匹配错误
cs.close();
```

## 连接示例

*   **加载JDBC驱动**
    
    在应用中执行以下命令加载 JDBC 驱动：
    
    ```javascript
    Class.forName("com.aliyun.polardb2.Driver");
    ```
    
    ⚠️ 如果是通过项目导入的方式导入JDBC，以上驱动都会自动注册完成，不需要额外注册
    
*   **连接数据库**
    
    在JDBC中，一个数据库通常用一个URL来表示，示例如下。
    
    ```bash
    jdbc:polardb://pc-***.o.polardb.rds.aliyuncs.com:1521/polardb_test?user=test&password=Pw123456
    ```
    
    | **参数** | **示例** | **说明** |
    | --- | --- | --- |
    | URL前缀 | jdbc:polardb:// | 连接PolarDB的URL统一使用jdbc:polardb://作为前缀。 |
    | 连接地址 | pc-\*\*\*.o.polardb.rds.aliyuncs.com | PolarDB集群的连接地址，如何查看连接地址请参见[查看或申请连接地址](https://help.aliyun.com/document_detail/139516.html#task-imd-wlq-tdb)。 |
    | 端口 | 1521 | PolarDB集群的端口，默认为1521。 |
    | 数据库 | polardb\_test | 需要连接的数据库名。 |
    | 用户名 | test | PolarDB集群的用户名。 |
    | 密码 | Pw123456 | PolarDB集群用户名对应的密码。 |
    
    ⚠️ 我们支持用户使用 jdbc:postgresql:// 协议连接数据库，但为避免与原生 PostgreSQL 驱动产生冲突导致其他连接异常，需要在连接字符串末尾添加 forceDriverType=true 参数来显式启用，使用方式：
    
    ```sql
    jdbc:postgresql://1.1.1.1:5432/postgres?forceDriverType=true
    ```
    
*   **查询并处理结果**
    
    访问数据库执行查询时，需要创建一个Statement、PreparedStatment或者CallableStatement对象。
    
    上述示例中使用了Statement，使用PreparedStatment示例如下：
    
    ```csharp
    PreparedStatement st = conn.prepareStatement("select id, name from foo where id > ?");
    st.setInt(1, 10);
    resultSet = st.executeQuery();
    while (resultSet.next()) {
        System.out.println("id:" + resultSet.getInt(1));
        System.out.println("name:" + resultSet.getString(2));
    }
    ```
    
*   **Out参数使用**
    

访问数据库存储过程时，如果要使用带有OUT参数的存储过程，需要创建CallableStatement对象。并使用

registerOutParameter，使用示例如下：

```csharp
String sql = "CREATE or replace PROCEDURE test_in_out_procedure(a IN integer, b INOUT integer, c OUT integer)\n" +
                    "AS $$\n" +
                    "BEGIN\n" +
                    "    c = a + b;\n" +
                    "    b = a;\n" +
                    "return;\n" +
                    "END;\n" +
                    "$$;";
conn.createStatement().execute(sql);
CallableStatement stmt = conn.prepareCall("{call test_in_out_procedure(?,?,?)}");
stmt.setInt(1, 1);
stmt.setInt(2, 2);
stmt.registerOutParameter(2, Types.INTEGER);
stmt.registerOutParameter(3, Types.INTEGER);

stmt.execute();

System.out.println("get $2 = " + stmt.getInt(2));
System.out.println("get $3 = " + stmt.getInt(3));

// OUTPUT:
// get $2 = 1
// get $3 = 3
```

自42.5.4.0.10.9版本后，新增函数绑定OUT参数的功能，需求内核版本大于20250430，且内核开启 \`polar\_enable\_call\_function\_syntax\` 参数；使用例子为：

```sql
-- SQL 例子
-- Create Or Replace Function Test_Jdbc_Func (
--     piStoreGid  In      INT,      
--     poErr_Msg   Out     Varchar2       
--   ) Return Number
--   Is 
--   Begin 
--     poErr_Msg := '1';
--     return(3);
--   End;

String callString = "{ ? = call Test_Jdbc_Func(?, ?) }";
try (CallableStatement cstmt = connect.prepareCall(callString)) {
    cstmt.registerOutParameter(1, Types.NUMERIC);
    // 设置输入参数
    cstmt.setInt(2, Types.INTEGER);

    // 注册输出参数
    cstmt.registerOutParameter(3, Types.VARCHAR);

    // 执行存储过程
    cstmt.executeUpdate();

    PgConnection connection = (PgConnection) connect;
    System.out.println(connection.callFunctionMode());
    System.out.println(connection.getParameterStatus("polar_enable_call_function_syntax"));

    // 输出结果
    System.out.println("v_ret: " + cstmt.getInt(1));
    System.out.println("v_ret3: " + cstmt.getString(3));
}
```

*   使用createStruct 语法
    

自**42.5.4.0.12**版本后，数据库驱动支持createStruct语法，使用Struct结构体作为函数的入参，下面是一个使用示例；

```sql
@Test
  public void testSelectBoolean1() throws Exception {
    Object[] addressAttributes = new Object[] {
        Integer.valueOf(42),                     // Integer
        new BigDecimal("9999.99"),               // java.math.BigDecimal
        Boolean.TRUE,                            // Boolean
        new Date(),                              // java.util.Date
        new Timestamp(System.currentTimeMillis()), // java.sql.Timestamp
        "这是一个测试字符串",                      // String
        new StringBuilder("可变字符串"),           // StringBuilder
        null,                                    // null
    };
    Struct addressStruct = conn.createStruct("test_object", addressAttributes);
    CallableStatement stmt = conn.prepareCall("{? = call test_object_func(?)}");
    stmt.registerOutParameter(1, Types.VARCHAR);
    stmt.setObject(2, addressStruct);
    stmt.execute();
    System.out.println(stmt.getObject(1).toString());
  }
```

## 相关工具适配

### Hibernate 适配

如果您的工程使用Hibernate连接数据库，请在您的Hibernate配置文件`hibernate.cfg.xml`中配置PolarDB数据库的驱动类和方言。如果您的工程使用Hibernate连接数据库，请在您的Hibernate配置文件`hibernate.cfg.xml`中配置PolarDB数据库的驱动类和方言。

**说明** Hibernate需要为3.6及以上版本才支持PostgresPlusDialect方言。

*   PolarDB 兼容Ora版本1.0版本
    

```xml
<property name="connection.driver_class">com.aliyun.polardb.Driver</property>
<property name="connection.url">jdbc:polardb://pc-***.o.polardb.rds.aliyuncs.com:1521/polardb_test</property>
<property name="dialect">org.hibernate.dialect.PostgresPlusDialect</property>
```

*   PolarDB 兼容Ora版本2.0版本
    

```xml
<property name="connection.driver_class">com.aliyun.polardb2.Driver</property>
<property name="connection.url">jdbc:polardb://pc-***.o.polardb.rds.aliyuncs.com:1521/polardb_test</property>
<property name="dialect">org.hibernate.dialect.PostgresPlusDialect</property>
```

### druid连接池配置

*   如果使用的是PolarDB 兼容Ora版本1.0版本，且连接池版本在[Druid 1.1.24](https://github.com/alibaba/druid/tags)以后，无需配置`driver name`和`dbtype`参数。以后，无需配置`driver name`和`dbtype`参数。
    
*   其他的之前的版本，需要显式设置`driver name`和`dbtype`参数，如下所示：其他的之前的版本，需要显式设置`driver name`和`dbtype`参数，如下所示：
    
    *   PolarDB 兼容Ora版本1.0版本
        

```javascript
dataSource.setDriverClassName("com.aliyun.polardb.Driver");
dataSource.setDbType("postgresql");
```

*   PolarDB 兼容Ora版本2.0版本
    

```javascript
dataSource.setDriverClassName("com.aliyun.polardb2.Driver");
dataSource.setDbType("postgresql");
```

**说明** Druid 1.1.24之前版本没有适配PolarDB，因此`dbtype`需要设置为`postgresql`**说明** Druid 1.1.24之前版本没有适配PolarDB，因此`dbtype`需要设置为`postgresql`

如果您需要在Druid连接池中对数据库密码进行加密，请参见[数据库密码加密](https://github.com/alibaba/druid/wiki/%E4%BD%BF%E7%94%A8ConfigFilter?spm=a2c4g.147247.0.0.30705a4ddFytCS#2-%E6%95%B0%E6%8D%AE%E5%BA%93%E5%AF%86%E7%A0%81%E5%8A%A0%E5%AF%86)。

### WebSphere 适配

使用 WebSphere时，配置PolarDB的JDBC作为数据源，步骤如下所示：

1.  数据库类型选择**用户自定义的**。
    
2.  实现类名为：
    

*   PolarDB 兼容Ora版本1.0版本
    

`com.aliyun.polardb.ds.PGConnectionPoolDataSource``com.aliyun.polardb.ds.PGConnectionPoolDataSource`

*   PolarDB 兼容Ora版本2.0版本
    

`com.aliyun.polardb2.ds.PGConnectionPoolDataSource``com.aliyun.polardb2.ds.PGConnectionPoolDataSource`

1.  类路径选择JDBC jar包所在路径。
    

### Spring 框架适配

在 Spring 框架中使用新版本 JDBC 时（>= 42.5.4.0.11），可直接传入结构体类型（Struct）作为存储过程参数，无需额外代码改造。以下示例通过 `GetUserProcedure` 方法调用存储过程 `get_user_info`，其中参数 `c` 为复合类型 `com`，通过构建对应的结构体对象实现复合类型传值。

```sql
public class GetUserProcedure extends StoredProcedure {
    private static final String PROCEDURE_NAME = "get_user_info";

    public GetUserProcedure(DataSource dataSource) {
        super(dataSource, PROCEDURE_NAME);
        init();
    }

    private void init() {
        // 声明输入参数
        declareParameter(new SqlParameter("p_user_id", Types.NUMERIC));
        declareParameter(new SqlParameter("c", Types.STRUCT, "com"));

        compile(); // 必须调用 compile()
    }

    public Map<String, Object> getUserInfo(Integer userId) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("p_user_id", userId);
        Calendar cal = Calendar.getInstance();
        cal.set(2023, Calendar.OCTOBER, 1, 12, 30, 45); // 注意：Calendar 的月份从 0 开始
        cal.set(Calendar.MILLISECOND, 0);

        Rec rec = new Rec();
        rec.t1 = 1;
        rec.t2 = "some text";
        rec.t3 = new Date(cal.getTime().getTime());
        rec.t4 = true;
        rec.t5 = null;
        inputs.put("c", rec);

        return execute(inputs); // 执行存储过程
    }
}
```

## 常见问题

*   Q：如何选择JDBC驱动，是否可以使用开源社区驱动？
    
    A：PolarDB PostgreSQL版（兼容Oracle）兼容版在开源PostgreSQL的基础上实现了众多兼容性相关的特性，有些特性需要驱动层配合实现，因此，推荐使用PolarDB的JDBC驱动。相关驱动可以在官网驱动下载页面下载。
    
*   Q：公共Maven仓库是否有PolarDB JDBC驱动？
    
    A：按照官网描述，JDBC驱动需要在官网下载jar包，对于Maven工程需要手动安装该jar包至本地仓库使用，目前仅支持官网下载JDBC驱动包一种方式。
    
*   Q：如何查看版本号？
    
    A：通过运行java -jar 驱动名来查看版本号。
    
*   Q：是否支持在URL中配置多个IP和端口？
    
    A：PolarDB PostgreSQL版（兼容Oracle）的JDBC驱动支持在URL中配置多个IP和端口，示例如下：
    
    ```javascript
    jdbc:poalardb://1.2.XX.XX:5432,2.3.XX.XX:5432/postgres
    ```
    
    **说明** 配置多个IP后，创建连接时会依次尝试通过这些IP创建连接，若都不能创建连接，则连接创建失败。每个IP尝试创建连接的超时时间默认为10s，即connectTimeout，若要修改超时时间，可在连接串中添加该参数进行设置。
    
*   Q：游标类型如何选择？
    
    A：如果是java 1.8之前的JDK，使用Types.REF；如果是java 1.8及其之后的版本，可以使用Types.REF\_CURSOR。
    
*   Q：是否支持默认返回大写的列名？
    

A：可以在JDBC连接串中添加参数oracleCase=true，该参数会将返回的列名默认转换为大写，示例如下：

```bash
jdbc:poalardb://1.2.XX.XX:5432,2.3.XX.XX:5432/postgres?oracleCase=true
```

### 版本更新说明

#### 版本42.5.7.0.15 (2026-07-29)

**发布方式变更**

*   正式发布至 Maven 中心仓库：坐标为 `com.aliyun:polardb`（groupId 统一变更为 `com.aliyun`，与组织已验证的 namespace 一致；驱动类名、包名不受影响，仍为 `com.aliyun.polardb2.Driver`）。
    
*   开源许可协议变更为 Apache License 2.0：全项目 License 引用（POM、jar manifest、META-INF/LICENSE、文档）统一更新，新增 NOTICE 文件保留上游 PostgreSQL JDBC Driver 的 BSD-2-Clause 原始版权声明，随 jar 一并分发。
    

**新增功能**

*   autocommit 下服务端游标批量抓取：新增连接参数 `autocommitFetch`（默认启用），autocommit 模式下配合 `defaultRowFetchSize` 也能使用服务端游标分批抓取结果集，避免大结果集一次性载入内存。
    
*   Oracle 兼容登录名预处理：登录用户名为纯大写时自动转为小写后发送，对齐 Oracle 大小写不敏感的登录习惯（混合大小写用户名保持原样）。
    
*   SYNONYM 同义词类型解析：`registerOutParameter(idx, type, typeName)`、`createArrayOf` 等接口传入的类型名支持通过 Oracle 同义词（all_synonyms）解析到实际的自定义 TABLE OF / RECORD 类型。
    
*   `CallableStatement.getClob(int)` 实现：OUT 参数支持以 Clob 形式读取文本类型返回值。
    
*   复合类型字段的 Java 类型还原：Struct/PGobject 字段值按数据库列类型还原为对应的 Java 对象（数值、日期等），不再统一以字符串返回。
    
*   VARCHAR OUT 参数数字 getter 读取：注册为 VARCHAR 的 OUT 参数若内容为数字，可直接用 `getInt()` / `getBigDecimal()` 等数值 getter 读取，兼容 ojdbc 行为。
    
*   带返回值占位符的函数调用转 EXEC 语法：`{? = call f(...)}` 形式支持转换为 EXEC 调用语法执行。
    
*   DECLARE 匿名块 OUT 参数支持：`DECLARE ... BEGIN ... END` 形式的匿名块支持绑定 OUT 模式参数。
    

**安全修复**

*   SCRAM 认证增加 PBKDF2 迭代次数上限校验：防止恶意/被劫持服务端通过返回超大迭代次数诱导客户端进行海量哈希计算造成拒绝服务。
    

**缺陷修复**

*   修复匿名块全 IN 参数时向应用暴露内部结果集的问题：全 IN 参数的匿名块执行后不再返回多余结果集，对齐 Oracle 行为。
    
*   修复 `parseEnd` 对多词标签的解析：`END` 后跟多个单词的标签（如 `END my label`）不再解析失败。
    
*   修复空复合类型字面量处理异常：INOUT 参数传入空复合类型时 `trimMoney` 处理抛异常的问题。
    
*   NUMBER(p,s) 精度溢出报错对齐 Oracle：数值超出声明精度时的报错行为与 Oracle 一致。
    

**工程优化**

*   构建发布链路对齐 gradle-nexus/publish-plugin 标准：提供 `publishToSonatype` / `closeSonatypeStagingRepository` / `findSonatypeStagingRepository` / `releaseSonatypeStagingRepository` 标准任务链，支持 Central Portal OSSRH 兼容 API 自动化发布（含 closed staging 仓库自动清理）；凭据遵循 `sonatypeUsername` / `sonatypePassword` 项目属性约定。
    
*   新增 `assembleRelease` 打包指令：适配内部发布平台，自动按正式版（去 -SNAPSHOT）构建全部产物；修复 `-Prelease` 模式下签名任务重复注册导致构建失败的问题。
    
*   移除上游遗留的失效 GitHub Actions CI 工作流，README 按 PolarDB 驱动定位全面重写。
    
*   补充测试：NLS 日期格式转换、数值族 OUT 参数互转、PreparedStatement 用例优化等。
    

#### 版本42.5.7.0.14 (2026-05-28)

**新增功能**

*   Oracle q-quote 字面量完整支持：支持所有合法 delimiter（包括 `[` `]`、`{` `}`、`<` `>`、`(` `)` 及任意字符），特别支持 `'` 作 delimiter 的 `q''..''` 形态。PreparedStatement 中 q-quote 内部的 `?` 和 `:xxx` 不会被误判为绑定参数。
    
*   TABLE OF / INDEX BY 集合类型支持：完整支持 PolarDB 关联数组（PL/SQL INDEX BY）类型，包括跨包引用的 TABLE OF RECORD 类型。CallableStatement 可通过 `registerOutParameter(idx, Types.ARRAY)` 接收关联数组返回值，`getArray()` 返回的 PgArray 保留 key 信息。
    
*   DO 匿名块与 $N INOUT 参数：支持 `DO $$ ... $$` 匿名块中绑定 `$1`、`$2` 等位置参数，并支持 INOUT 模式的参数传递。
    
*   数值类型 OUT 参数全族互转：重构 `CallableStatement` 的 OUT 参数类型转换机制，采用 toNumeric + fromNumeric 两阶段归一化策略，完整覆盖 SMALLINT / INTEGER / BIGINT / NUMERIC / DECIMAL / REAL / FLOAT / DOUBLE 双向自动转换。解决了 `DBMS_SQL.EXECUTE` 等返回 BIGINT 但框架注册 NUMERIC 时报 `Types=-5 vs Types=2` 不匹配的问题。
    
*   NUMBER 尾零去除：新增连接参数 `numberStripTrailingZeros`（默认 true），getString() / getObject() 对 NUMERIC/DECIMAL/BIGINT 类型自动去除尾部零（如 `911.000` → `911`），行为与 Oracle NUMBER 一致。解决了 Spring `queryForList()` 等场景下 BigDecimal 尾零未去除的问题。
    
*   BIGINT 映射为 BigDecimal：新增连接参数 `bigintAsNumeric`（默认 true），BIGINT 列的 `getObject()` 返回 `BigDecimal` 而非 `Long`，对齐 Oracle NUMBER 行为。
    
*   executeUpdate 支持 SELECT：新增连接参数 `allowSelectInExecuteUpdate`（默认 true），允许 `executeUpdate()` 执行 SELECT 语句不抛异常，兼容 Oracle 业务中的历史代码习惯。
    
*   BLOB 十六进制输出：新增连接参数 `blobUpperHex`（默认 true），bytea/blob 列 `getString()` 返回大写十六进制无前缀格式（如 `AABBCC`），与 Oracle RAW 输出一致。
    
*   Oracle 序列伪列支持：支持 `sequence.NEXTVAL` / `sequence.CURRVAL` 等 Oracle 风格序列伪列调用及相关处理。
    
*   Oracle NLS 日期格式支持：支持 Oracle `NLS_DATE_FORMAT` 设置下的日期字符串解析，包括 RR 格式两位年份转换（如 `01-JAN-25`）。
    
*   TEXT 列返回 Clob：当 `clobAsText=true` 时，TEXT 类型列的 `getObject()` 返回 `java.sql.Clob` 对象，兼容 Oracle CLOB 接口用法。
    
*   registerOutParameter 自定义类型名：`registerOutParameter(idx, type, typeName)` 支持通过类型名解析自定义 TABLE OF / RECORD 类型。
    
*   复合类型数组与 Struct 解析：支持 `createArrayOf` 创建复合类型数组，支持 Struct 类型数组元素的序列化，包括跨包 TABLE OF RECORD 类型。
    

**参数默认值变更**

*   `resetNlsFormat` 默认值由 `true` 改为 `false`：不再在连接时强制重置 NLS 日期格式，尊重服务端配置，避免审计日志中出现非预期的 SET 语句。
    
*   `unknownLength` 默认值由 `Integer.MAX_VALUE` 改为 `4000`：对齐 Oracle VARCHAR2(4000) 最大长度限制，解决部分框架读取 columnSize 异常的问题。
    

**缺陷修复**

*   修复 q-quote 单引号 delimiter 解析失败：`q''te:s't ? :44''` 之前会报 `Unterminated string literal` 错误，现已正确识别 `''` 作为结束标记。
    
*   修复 TABLE OF 数组外层括号解析错误：PolarDB 服务端返回的 `(1 => "alpha", ...)` 带外层圆括号，导致第一个 key 多 `(` 、最后一个 value 多 `)` 的问题。
    
*   修复 `CallableStatement.executeQuery()` 返回 null：当存储过程无结果集时不再返回 null，改为返回空 ResultSet，避免 HikariCP 等连接池包装层 NPE。
    
*   修复复合类型序列化多个问题：
    *   Struct 字段含括号或逗号时未加双引号包裹，导致 `malformed record literal`
    *   PGobject 数组元素缺失外层括号，单字段 PGobject 值未自动包裹
    *   Object[][] 类型复合数组编码错误
    *   复合类型 null 日期字段编码异常
    *   复合记录字面量字段内括号转义错误
    
*   修复 Oracle 模式集合类型参数 OID 解析错误：存储过程集合类型出参的类型转换异常。
    
*   修复 `setObject(localDateTime, Types.DATE)` 类型处理异常：传入 `LocalDateTime` 并指定 `Types.DATE` 时不再抛出转换异常。
    
*   修复 `bigintAsNumeric=true` 时二进制模式递归溢出：解决了开启 bigintAsNumeric 后 server-side prepare 场景下的栈溢出问题。
    
*   修复日期时间类型转换异常：CallableStatement 中日期/时间类型 OUT 参数的类型转换错误。
    
*   修复函数调用参数数量校验错误：参数数组大小及数量校验逻辑不正确导致调用失败。
    
*   修复 DO-block 执行时字段结构丢失导致崩溃：DO 匿名块执行时 resultFields 为 null 导致的 NPE。
    
*   修复未注册的 INOUT 参数多余输出列导致崩溃：对未显式注册的 INOUT 参数多余返回列做容错处理。
    
*   修复元数据列大小限制问题：VARCHAR 列大小限制为 4000，字符和 LOB 列大小限制逻辑修正，兼容 Oracle 元数据行为。
    
*   修复 Oracle 兼容 DATE 列的类型名称显示问题：元数据中 DATE 列的 `getColumnTypeName()` 返回正确的类型名。
    
*   修复 `clobAsText` 设置时 TEXT 列返回类型错误：确保 TEXT 列在 `clobAsText=true` 时正确返回 Clob 对象。
    
*   统一时间戳字符串格式输出：确保所有场景下 Timestamp 的 `getString()` 格式统一。
    
*   取消 DateStyle 强制为 ISO：不再在连接时强制设置 `DateStyle=ISO`，尊重服务器端的配置。
    
*   统一转换错误码为正数：将服务器返回的负数错误码统一转换为正数，对齐 Oracle SQLCODE 规范。
    
*   修复 SQL 语句正则匹配逻辑错误：解析器对 SQL 语句类型的判断逻辑修正。
    

**工程优化**

*   重构 PgConnection 类：提升代码结构和可维护性。
    
*   添加原始类型 OID 跟踪：支持类型映射追踪，便于调试类型转换问题。
    
*   更新 forbiddenapis 插件版本至 3.10。
    

#### 版本42.5.7.0.13 (2025-12-24)

*   核心组件升级：将 JDBC 驱动版本同步至社区 42.5.x 系列的最新稳定版（42.5.7），引入了最新的安全补丁与性能优化。
    
*   连接稳定性增强：深度修复了在特定连接池场景下可能出现的连接泄漏隐患，提升了长连接环境下的资源管理可靠性。
    
*   第三方生态兼容性优化：调整 `getDatabaseProductName` 返回值为 `PostgreSQL`。调整 `DRIVER_NAME` 返回值为 `PolarDB-2.0 JDBC Driver`；确保第三方框架（如 MyBatis、Hibernate 等）能够准确识别驱动类型，避免因识别偏差导致的兼容性异常。
    
*   驱动冲突规避：移除了对 `jdbc:oracle:thin:` 连接协议的支持。此举旨在消除在多驱动并存的项目中与原生 Oracle 驱动的潜在冲突，确保驱动加载逻辑的唯一性与准确性。
    
*   Oracle 迁移适配增强：优化了 getTables 接口的检索逻辑，支持通过大写表名查找表。该特性适配了从 Oracle 迁移至 PolarDB 的 Java 业务代码逻辑，降低了应用迁移的改造成本。
    

#### 版本42.5.4.0.12 (2025-08-13)

*   支持createStruct语法，使用方法见本文 createStruct 章节
    
*   支持postgres连接头，使用方法见本文 支持postgres连接头 章节
    

#### 版本42.5.4.0.11 (2025-07-10)

*   支持通过PgCallableStatement接口读写函数以及存储过程，支持各种类型的 IN & OUT & INOUT参数 （[使用文档](http://polardb-pg.alibaba.net/PolarDB-for-PostgreSQL/zh/manual/v14/tools/jdbc_call_function.html)） 
    
*   支持SQLCODE 错误码字段，与数据库内核的错误处理机制兼容（[使用文档](http://polardb-pg.alibaba.net/PolarDB-for-PostgreSQL/zh/manual/v14/plsql/error-handling.html)）
    
*   支持 Spring Framework 中使用 Types.STRUCT结构体，使用方法见本文 Spring 框架适配 章节
    
*   优化数字类型到布尔值的转换规则 ，更新后的转换逻辑见 本文的 数字类型与布尔值转换 章节。
    
*   增强类型绑定支持 
    

#### 版本42.5.4.0.10.9 （2025-03-19）

*   支持Oracle风格的函数绑定参数功能
    
*   修复一个 END 会导致解析失败的缺陷
    

#### 版本42.5.4.0.10.7（2025-01-06）

*   支持兼容Oracle方式的注释功能（即支持 `/* /* Comments */`功能）
    
*   修复了Mybatis调用Clob接口时，空值遇到报错 Misuse of castNonNull  的问题
    

#### 版本42.5.4.0.10.6 （2024-12-04）

*   支持高版本JDBC上的channel binding功能
    
*   升级escapeSyntaxCallMode参数默认值为callIfNoReturn，以适配Oracle用户的参数绑定行为
    
*   修复一个attidentity识别错误的缺陷，可能导致列类型获取不正确
    
*   修复一个CASE WHEN END 会导致解析失败的缺陷
    

#### 版本42.5.4.0.10.5 （2024-10-24）

*   优化了 resetNlsFormat 参数的设置，确保在连接时正确配置，并避免在审计日志中留下非预期的执行记录。
    
*   修复了逻辑复制测试中因 java.nio.Buffer 类型接口无法识别而导致的错误。
    
*   修复了存储过程中CASE WHEN .. END 识别结束解析不正确的情况。
    

#### 版本 42.5.4.0.10.4（2024-09-02）

*   修复了PL块中绑定不正确的问题。由于此问题对性能的影响，默认已关闭该功能。
    
*   支持在同一类型内部进行隐式转换，允许字符类型（如 VARCHAR、CHAR）和数字类型（如 NUMERIC、INTEGER、DOUBLE）作为 INOUT 参数相互转换。
    
*   驱动中元信息的 `getDatabaseProductName()` 函数返回值现为："POLARDB2 Database Compatible with Oracle"。
    

#### 版本 42.5.4.0.10.2（2024-07-19）

*   修复了在 Mybatis 中，若对象实体注册类型为 Timestamp 时，数据库无法正确推断参数类型的问题。
    

## 交流答疑群

![image.png](https://alidocs.oss-cn-zhangjiakou.aliyuncs.com/res/ybEnBVZj3dM7nP13/img/0a3877ab-50b5-47fe-963c-971f79696be1.png)