/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.core;

import com.aliyun.polardb2.polarora.UnNamedProc;
import com.aliyun.polardb2.util.CanEstimateSize;

/**
 * Stores information on the parsed JDBC query. It is used to cut parsing overhead when executing
 * the same query through {@link java.sql.Connection#prepareStatement(String)}.
 */
public class CachedQuery implements CanEstimateSize {
  /**
   * Cache key. {@link String} or {@code com.aliyun.polardb2.util.CanEstimateSize}.
   */
  public final Object key;
  public final Query query;
  public final boolean isFunction;
  public final boolean outParamBeforeFunc;
  /* POLAR: DO anonymous block with $N INOUT parameters */
  public final boolean isDoBlock;
  public final int doBlockParamCount;
  /* POLAR: Oracle sequence pseudocolumn (e.g. seq.nextval / seq.currval) */
  public final boolean isSequencePseudocol;

  private int executeCount;

  /* POLAR */
  public UnNamedProc unProc;

  public CachedQuery(Object key, Query query, boolean isFunction, boolean outParamBeforeFunc, UnNamedProc proc) {
    this(key, query, isFunction, outParamBeforeFunc, proc, false, 0);
  }

  public CachedQuery(Object key, Query query, boolean isFunction, boolean outParamBeforeFunc,
      UnNamedProc proc, boolean isDoBlock, int doBlockParamCount) {
    this(key, query, isFunction, outParamBeforeFunc, proc, isDoBlock, doBlockParamCount, false);
  }

  public CachedQuery(Object key, Query query, boolean isFunction, boolean outParamBeforeFunc,
      UnNamedProc proc, boolean isDoBlock, int doBlockParamCount, boolean isSequencePseudocol) {
    assert key instanceof String || key instanceof CanEstimateSize
        : "CachedQuery.key should either be String or implement CanEstimateSize."
        + " Actual class is " + key.getClass();
    this.key = key;
    this.query = query;
    this.isFunction = isFunction;
    this.outParamBeforeFunc = outParamBeforeFunc;
    this.unProc = proc;
    this.isDoBlock = isDoBlock;
    this.doBlockParamCount = doBlockParamCount;
    this.isSequencePseudocol = isSequencePseudocol;
  }

  public void increaseExecuteCount() {
    if (executeCount < Integer.MAX_VALUE) {
      executeCount++;
    }
  }

  public void increaseExecuteCount(int inc) {
    int newValue = executeCount + inc;
    if (newValue > 0) { // if overflows, just ignore the update
      executeCount = newValue;
    }
  }

  /**
   * Number of times this statement has been used.
   *
   * @return number of times this statement has been used
   */
  public int getExecuteCount() {
    return executeCount;
  }

  @Override
  public long getSize() {
    long queryLength;
    if (key instanceof String) {
      queryLength = ((String) key).length() * 2L; // 2 bytes per char, revise with Java 9's compact strings
    } else {
      queryLength = ((CanEstimateSize) key).getSize();
    }
    return queryLength * 2 /* original query and native sql */
        + 100L /* entry in hash map, CachedQuery wrapper, etc */;
  }

  @Override
  public String toString() {
    return "CachedQuery{"
        + "executeCount=" + executeCount
        + ", query=" + query
        + ", isFunction=" + isFunction
        + ", outParamBeforeFunc=" + outParamBeforeFunc
        + '}';
  }
}
