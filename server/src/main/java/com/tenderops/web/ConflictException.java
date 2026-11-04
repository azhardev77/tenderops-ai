package com.tenderops.web;

/** A 409 Conflict: the request collides with existing state (e.g. duplicate FY). */
public class ConflictException extends RuntimeException {

  public ConflictException(String message) {
    super(message);
  }
}
