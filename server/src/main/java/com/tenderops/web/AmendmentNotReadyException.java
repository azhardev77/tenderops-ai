package com.tenderops.web;

public class AmendmentNotReadyException extends RuntimeException {
  public AmendmentNotReadyException(String message) {
    super(message);
  }
}
