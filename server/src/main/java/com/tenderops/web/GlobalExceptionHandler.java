package com.tenderops.web;

import com.tenderops.common.PdfRejectedException;
import com.tenderops.security.ResourceNotVisibleException;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 7807 errors without stack traces or internals (TC-SEC-05). */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setTitle("Validation failed");
    Map<String, String> errors = new TreeMap<>();
    e.getBindingResult().getFieldErrors()
        .forEach(f -> errors.put(f.getField(), f.getDefaultMessage()));
    problem.setProperty("errors", errors);
    return problem;
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadable(HttpMessageNotReadableException e) {
    return problem(HttpStatus.BAD_REQUEST, "Malformed request", "Request body is not valid JSON");
  }

  @ExceptionHandler(BadCredentialsException.class)
  public ProblemDetail handleBadCredentials(BadCredentialsException e) {
    return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid email or password");
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ProblemDetail handleAccessDenied(AccessDeniedException e) {
    return problem(HttpStatus.FORBIDDEN, "Forbidden", "Insufficient permissions");
  }

  @ExceptionHandler({NotFoundException.class, ResourceNotVisibleException.class})
  public ProblemDetail handleNotFound(RuntimeException e) {
    return problem(HttpStatus.NOT_FOUND, "Not found", "Not found");
  }

  @ExceptionHandler(EmailTakenException.class)
  public ProblemDetail handleEmailTaken(EmailTakenException e) {
    return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
  }

  @ExceptionHandler(ConflictException.class)
  public ProblemDetail handleConflictState(ConflictException e) {
    return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
  }

  @ExceptionHandler(JobAlreadyActiveException.class)
  public ResponseEntity<ProblemDetail> handleConflict(JobAlreadyActiveException e) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
  }

  @ExceptionHandler(AmendmentNotReadyException.class)
  public ResponseEntity<ProblemDetail> handleNotReady(AmendmentNotReadyException e) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
  }

  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<ProblemDetail> handleBadRequest(BadRequestException e) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
  }

  @ExceptionHandler(PdfRejectedException.class)
  public ResponseEntity<ProblemDetail> handlePdfRejected(PdfRejectedException e) {
    ProblemDetail problem =
        problem(HttpStatus.BAD_REQUEST, "Bad request", "PDF rejected: " + e.reason());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
  }

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException e) {
    ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
        e.getMessage());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", "60").body(problem);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
    return problem(HttpStatus.BAD_REQUEST, "Bad request", "Invalid request");
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception e) {
    log.error("Unexpected error", e);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
        "An unexpected error occurred");
  }

  private static ProblemDetail problem(HttpStatus status, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    return problem;
  }
}
