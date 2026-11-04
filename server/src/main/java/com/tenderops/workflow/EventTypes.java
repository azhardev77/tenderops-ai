package com.tenderops.workflow;

/** Event types and stage names (section 7.2). */
public final class EventTypes {

  public static final String STAGE_STARTED = "STAGE_STARTED";
  public static final String STAGE_PROGRESS = "STAGE_PROGRESS";
  public static final String STAGE_COMPLETED = "STAGE_COMPLETED";
  public static final String STAGE_FAILED = "STAGE_FAILED";
  public static final String PAGE_PARSED = "PAGE_PARSED";
  public static final String REGION_DETECTED = "REGION_DETECTED";
  public static final String REQUIREMENT_EXTRACTED = "REQUIREMENT_EXTRACTED";
  public static final String VERDICT_COMPUTED = "VERDICT_COMPUTED";
  public static final String RISK_RAISED = "RISK_RAISED";
  public static final String DECISION_UPDATED = "DECISION_UPDATED";
  public static final String AI_CALL = "AI_CALL";
  public static final String AGENT_STEP = "AGENT_STEP";
  public static final String AMENDMENT_STAGE = "AMENDMENT_STAGE";
  public static final String JOB_COMPLETED = "JOB_COMPLETED";
  public static final String JOB_FAILED = "JOB_FAILED";

  private EventTypes() {
  }
}
