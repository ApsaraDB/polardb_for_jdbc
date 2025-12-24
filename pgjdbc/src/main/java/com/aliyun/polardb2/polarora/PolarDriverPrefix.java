package com.aliyun.polardb2.polarora;

import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Enumeration;
import java.util.Locale;

public enum PolarDriverPrefix {
  POSTGRES("postgresql", "jdbc:postgresql:", "POSTGRES Database"),
  POLARDB("polardb", "jdbc:polardb:", "POLARDB Database Compatible with Oracle 2.0"),
  POLARDB2("polardb2", "jdbc:polardb2:", "POLARDB Database Compatible with Oracle 2.0");

  private String mode;
  private String prefix;
  private int prefixLen;
  private String productName;

  PolarDriverPrefix(String mode, String prefix, String productName) {
    this.mode = mode;
    this.prefix = prefix;
    this.prefixLen = prefix.length();
    this.productName = productName;
  }

  // 0 means off, 1 means on, 2 means auto
  private static int compOraclePrefix() {
    String urlAllowOraclePrefix = System.getProperty("POLARDB.JDBC.urlAllowOraclePrefix");

    if (urlAllowOraclePrefix == null) {
      return 1;
    }
    urlAllowOraclePrefix = urlAllowOraclePrefix.toLowerCase(Locale.ROOT);
    if (urlAllowOraclePrefix.equals("off") || urlAllowOraclePrefix.equals("0") || urlAllowOraclePrefix.equals("false")) {
      return 0;
    } else if (urlAllowOraclePrefix.equals("auto")) {
      return 2;
    } else {
      return 1;
    }
  }

  public static PolarDriverPrefix buildCompMode(String url) {
    PolarDriverPrefix ret = null;

    if (url == null || url.isEmpty()) {
      return null;
    }

    for (PolarDriverPrefix mode : PolarDriverPrefix.values()) {
      if (url.startsWith(mode.getPrefix())) {
        ret = mode;
        break;
      }
    }

    switch (compOraclePrefix()) {
      /* force close this */
      case 0:
        return null;
      /* force open this */
      case 1:
        return ret;
      /* auto choose this, only not find oracle jdbc use this */
      case 2: {
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
          Driver driver = drivers.nextElement();
          if (driver.getClass().getName().toLowerCase(Locale.ROOT).contains("oracle.jdbc")) {
            return null;
          }
        }
        return ret;
      }
      /* Can't go to the place, just for complier clean */
      default:
        throw new RuntimeException("Unexpected run path for compOraclePrefix");
    }
  }

  public static PolarDriverPrefix forName(String name) {
    for (PolarDriverPrefix mode : PolarDriverPrefix.values()) {
      if (mode.getMode().equalsIgnoreCase(name)) {
        return mode;
      }
    }
    return null;
  }

  public String getPrefix() {
    return prefix;
  }

  public int getPrefixLen() {
    return prefixLen;
  }

  public String getMode() {
    return mode;
  }

  public String getProductName() {
    return productName;
  }

  public static boolean checkUrlPrefix(String url) {
    if (url == null || url.isEmpty()) {
      return false;
    }

    PolarDriverPrefix compMode = buildCompMode(url);
    return compMode == null ? false : true;
  }
}
