package com.tenderops;

/**
 * Shared credentials for non-transactional HTTP tests. The H2 mem database is shared
 * across test classes in one JVM, so every class that seeds the demo user must use
 * the same password (first seed wins, later seeds reuse the row).
 */
public final class TestPasswords {

  public static final String DEMO = "demo-test-password-1";

  private TestPasswords() {
  }
}
