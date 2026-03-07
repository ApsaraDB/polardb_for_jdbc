package com.aliyun.polardb2.polarora;

import com.aliyun.polardb2.core.ParameterList;
import com.aliyun.polardb2.core.QueryExecutor;
import com.aliyun.polardb2.core.SetupQueryRunner;
import com.aliyun.polardb2.core.v3.SimpleParameterList;
import com.aliyun.polardb2.jdbc.PgConnection;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Random;

public class UnNamedProc {
  private boolean isUnamedProc;
  private boolean isCallableQuery;
  private String fakeUnamedProcSql;
  private String unamedProcName;
  private String originalSql;

  public UnNamedProc(String sql, boolean isCallableQuery) throws SQLException {
    this.originalSql = sql;
    this.isCallableQuery = isCallableQuery;
    this.isUnamedProc = false;

    /* POLAR: init */
    polar_init_unnamed_proc();
  }

  public boolean isUnamedProc() {
    return isUnamedProc;
  }

  public String getUnamedProcSql() {
    return fakeUnamedProcSql;
  }

  public void polar_unamed_proc_process_begin(ParameterList preparedParameters,
      PgConnection connection) throws SQLException {
    String procSql = "create procedure " + unamedProcName + "(";

    /* POLAR */
    if (!(preparedParameters instanceof SimpleParameterList)) {
      return;
    }

    SimpleParameterList simpleParamemterList = (SimpleParameterList) (preparedParameters);
    String procBody = getUnamedProcBody();

    if (procBody.length() == 0) {
      throw new SQLException("Cannot get the unamed proc body");
    }

    for (int i = 0; i < simpleParamemterList.getParameterCount(); i++) {
      String type = "";
      String targetType = "";
      if (simpleParamemterList.isInOutParam(i)) {
        type = " INOUT ";
      } else if (simpleParamemterList.isInParam(i)) {
        type = " IN ";
      } else if (simpleParamemterList.isOutParam(i)) {
        type = " OUT ";
      }

      targetType = simpleParamemterList.getTypeToString(i);
      if (targetType.equalsIgnoreCase("UNSPECIFIED")) {
        targetType = "TEXT";
      }

      procSql += "par_" + i + type + targetType;

      if (i != simpleParamemterList.getParameterCount() - 1) {
        procSql += ",";
      }
    }

    procSql += ")\nis\n" + procBody;

    this.polar_execute_oneshot_sql(connection, procSql);
  }

  public void polar_unamed_proc_process_end(PgConnection connection, boolean error) throws SQLException {
    this.polar_execute_oneshot_sql(connection, "drop procedure " + unamedProcName + ";");
  }

  private void polar_init_unnamed_proc() {
    String sqlLowerCase = originalSql.toLowerCase(Locale.US).trim();
    StringBuilder os = new StringBuilder();
    int declareStartIndex = sqlLowerCase.indexOf("declare");
    int beginStartIndex = sqlLowerCase.toLowerCase(Locale.US).indexOf("begin");
    int endStartIndex = sqlLowerCase.indexOf("end");
    int paramIndex = sqlLowerCase.indexOf("?");

    // check "begin;", if start with "begin;", do not use unnamed proc.
    if (beginStartIndex >= 0) {
      String beginFollow = sqlLowerCase.substring(beginStartIndex + "begin".length()).trim();
      if (beginFollow.length() > 0 && beginFollow.charAt(0) == ';') {
        return;
      }
    }

    isUnamedProc =
        ((paramIndex != -1) && ((declareStartIndex == 0) || ((beginStartIndex == 0) && (endStartIndex != -1))));

    /* POLAR: Oracle-compatible anonymous DO blocks (begin...end with ? parameters) must NOT
     * be handled by UnNamedProc (which creates a temporary stored procedure).
     * Instead they are handled directly as DO blocks by Parser.modifyJdbcCall.
     * The only begin...end pattern that stays with UnNamedProc is "declare ... begin...end"
     * (declareStartIndex == 0), which is a full PL/SQL block needing a procedure wrapper.
     * Pure begin...end anonymous blocks with ? are Oracle DO blocks — skip them here. */
    if (isUnamedProc && declareStartIndex != 0 && beginStartIndex == 0) {
      isUnamedProc = false;
      return;
    }

    if (isUnamedProc) {
      Random rd = new Random();
      int randomSeq = rd.nextInt(1000000);
      String fakeCallStmtSql = "";
      boolean containParam = false;
      boolean inSingleQuote = false;
      boolean inDoubleQuote = false;
      boolean inLineComment = false;
      boolean inComment = false;
      boolean inDollarQuota = false;
      char previousChar = 0;
      int     osPosition = 0;
      int     paramNumber = 0;

      if (isCallableQuery) {
        fakeCallStmtSql += "{";
      }
      fakeCallStmtSql += "call polar_unamed_proc_" + randomSeq + "(";

      for (int i = 0; i < originalSql.length(); ++i) {
        char ch = originalSql.charAt(i);

        switch (ch) {
          case '\'':
            if (!inDoubleQuote && !inDollarQuota) {
              inSingleQuote = !inSingleQuote;
            }
            break;

          case '"':
            if (!inSingleQuote && !inDollarQuota) {
              inDoubleQuote = !inDoubleQuote;
            }
            break;

          case '$':
            if (previousChar == '$' && !inSingleQuote && !inDoubleQuote) {
              inDollarQuota = !inDollarQuota;
            }
            break;

          case '-':
            if (previousChar == '-' && !inSingleQuote && !inDoubleQuote && !inDollarQuota) {
              inLineComment = true;
            }
            break;

          case '/':
            if (!inSingleQuote && !inDoubleQuote && !inDollarQuota) {
              if (previousChar == '/') {
                inLineComment = true;
              } else if (previousChar == '*') {
                inComment = false;
              }
            }
            break;

          case '\r':
          case '\n':
            if (!inSingleQuote && !inDoubleQuote && !inDollarQuota) {
              inLineComment = false;
            }
            break;

          case '*':
            if (previousChar == '/' && !inSingleQuote && !inDoubleQuote && !inDollarQuota) {
              inComment = true;
            }
            break;

          case '?':
            if (!inSingleQuote && !inDoubleQuote && !inLineComment && !inComment && !inDollarQuota) {
              containParam = true;
              paramNumber = paramNumber + 1;
              fakeCallStmtSql = fakeCallStmtSql + "?,";
              os.append(originalSql.substring(osPosition, i));
              os.append("$" + paramNumber);
              osPosition = i + 1;
            }
            break;
        }

        previousChar = ch;
      }

      if (containParam) {
        fakeCallStmtSql = fakeCallStmtSql.substring(0, fakeCallStmtSql.length() - 1);
      }
      fakeCallStmtSql = fakeCallStmtSql + ")";
      if (isCallableQuery) {
        fakeCallStmtSql += "}";
      }

      fakeUnamedProcSql = fakeCallStmtSql;
      unamedProcName = "polar_unamed_proc_" + randomSeq;

      if (!containParam) {
        isUnamedProc = false;
      } else {
        os.append(originalSql.substring(osPosition, originalSql.length()));
        originalSql = os.toString();
      }
    }
  }

  private String getUnamedProcBody() {
    String trimSql = this.originalSql.toLowerCase(Locale.US).trim();
    int declareStartIndex = trimSql.indexOf("declare");
    int beginStartIndex = trimSql.indexOf("begin");
    int endStartIndex = trimSql.indexOf("end");

    /*
     * 8 is the 'declare' string length, declareStartIndex + 8 is
     * started with the procedure body.
     */
    if (declareStartIndex == 0) {
      return trimSql.substring(declareStartIndex + 8, trimSql.length());
    } else if (beginStartIndex == 0 && endStartIndex != -1) {
      return trimSql.substring(beginStartIndex, trimSql.length());
    }

    return "";
  }

  private void polar_execute_oneshot_sql(PgConnection connection, String sql) throws SQLException {
    QueryExecutor qe = connection.getQueryExecutor();
    SetupQueryRunner.run(qe, sql, false);
  }
}
