package com.tenderops.workflow;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/** Bounded retry for transient stage failures: IO errors retry, business errors fail fast. */
public final class RetryPolicy {

  public static final int MAX_ATTEMPTS = 3;

  private RetryPolicy() {
  }

  public static <T> T execute(Callable<T> action, int maxAttempts,
      Predicate<Exception> isTransient) throws Exception {
    int attempt = 0;
    while (true) {
      attempt++;
      try {
        return action.call();
      } catch (Exception e) {
        if (!isTransient.test(e) || attempt >= Math.max(1, maxAttempts)) {
          throw e;
        }
        sleep(100L * attempt);
      }
    }
  }

  public static boolean isTransient(Exception e) {
    if (e instanceof IOException) {
      return true;
    }
    return e instanceof RuntimeException && e.getCause() instanceof IOException;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
