package com.tenderops.agent;

/**
 * Bounded agent loop limits (section 8): max 10 steps, 10 s per tool, 120 s per run.
 * A record (not config) so tests can inject tight budgets honestly.
 */
public record AgentLimits(int maxSteps, long toolTimeoutMs, long runBudgetMs) {

  public static AgentLimits defaults() {
    return new AgentLimits(10, 10_000L, 120_000L);
  }
}
