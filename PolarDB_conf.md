### 测试
#### build.local.properties
host=11.239.69.226
server=11.239.69.226
port=59245
database=test
username=test
password=test
privilegedUser=postgres
privilegedPassword=postgres

#### 测试配置
host    all             postgres        0.0.0.0/0               trust
host    all             all             0.0.0.0/0               md5

#### 测试参数
wal_level = logical
nls_timestamp_tz_format = 'YYYY-MM-DD HH24:MI:SS.FFTZO'
polar_enable_refcursor_implicit_inout = off
polar_enable_stmt_transaction_rollback = off
polar_enable_ddl_implicit_commit= off
polar_enable_comment_without_level_check = on

### SQL
create user postgres password 'postgres' superuser;
create user test password 'test';
create database test owner test;
