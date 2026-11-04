package com.tenderops.web;

public class JobAlreadyActiveException extends RuntimeException {
  public JobAlreadyActiveException(long versionId) {
    super("A job is already active for tender version " + versionId);
  }
}
