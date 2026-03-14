/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.core;

/**
 * Contains parse flags from {@link Parser#modifyJdbcCall(String, boolean, int, int, EscapeSyntaxCallMode, boolean)}.
 */
public class JdbcCallParseInfo {
  private final String sql;
  private final boolean isFunction;
  private final boolean outParamBeforeFunc;
  /* POLAR: DO anonymous block with $N INOUT parameters */
  private final boolean isDoBlock;
  private final int doBlockParamCount;
  /* POLAR: Oracle sequence pseudocolumn (e.g. seq.nextval / seq.currval) */
  private final boolean isSequencePseudocol;

  public JdbcCallParseInfo(String sql, boolean isFunction, boolean outParamBeforeFunc) {
    this.sql = sql;
    this.isFunction = isFunction;
    this.outParamBeforeFunc = outParamBeforeFunc;
    this.isDoBlock = false;
    this.doBlockParamCount = 0;
    this.isSequencePseudocol = false;
  }

  /* POLAR: constructor for DO anonymous block */
  public JdbcCallParseInfo(String sql, boolean isFunction, boolean outParamBeforeFunc,
      boolean isDoBlock, int doBlockParamCount) {
    this.sql = sql;
    this.isFunction = isFunction;
    this.outParamBeforeFunc = outParamBeforeFunc;
    this.isDoBlock = isDoBlock;
    this.doBlockParamCount = doBlockParamCount;
    this.isSequencePseudocol = false;
  }

  /* POLAR: constructor for Oracle sequence pseudocolumn */
  public JdbcCallParseInfo(String sql, boolean isFunction, boolean outParamBeforeFunc,
      boolean isDoBlock, int doBlockParamCount, boolean isSequencePseudocol) {
    this.sql = sql;
    this.isFunction = isFunction;
    this.outParamBeforeFunc = outParamBeforeFunc;
    this.isDoBlock = isDoBlock;
    this.doBlockParamCount = doBlockParamCount;
    this.isSequencePseudocol = isSequencePseudocol;
  }

  /**
   * SQL in a native for certain backend version.
   *
   * @return SQL in a native for certain backend version
   */
  public String getSql() {
    return sql;
  }

  /**
   * Returns if given SQL is a function.
   *
   * @return {@code true} if given SQL is a function
   */
  public boolean isFunction() {
    return isFunction;
  }

  /**
   * Returns if given SQL is outParamBeforeFunc
   *
   * @return {@code true} if given SQL is outParamBeforeFunc
   */
  public boolean outParamBeforeFunc() {
    return outParamBeforeFunc;
  }

  /**
   * Returns if given SQL is a DO anonymous block with $N INOUT parameters.
   *
   * @return {@code true} if given SQL is a DO anonymous block
   */
  public boolean isDoBlock() {
    return isDoBlock;
  }

  /**
   * Returns the number of INOUT parameters in a DO anonymous block.
   *
   * @return parameter count for DO anonymous block, 0 if not a DO block
   */
  public int getDoBlockParamCount() {
    return doBlockParamCount;
  }

  /**
   * POLAR: Returns if given SQL is an Oracle sequence pseudocolumn access
   * (e.g. seq_name.nextval or seq_name.currval).
   *
   * @return {@code true} if given SQL is a sequence pseudocolumn SELECT
   */
  public boolean isSequencePseudocol() {
    return isSequencePseudocol;
  }

}
