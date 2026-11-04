package com.tenderops.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** RetryPolicy: transient failures retry with backoff, business errors fail fast. */
class RetryPolicyTest {

  @Test
  void retriesTransientThenSucceeds() throws Exception {
    AtomicInteger calls = new AtomicInteger();

    String result = RetryPolicy.execute(() -> {
      if (calls.incrementAndGet() < 2) {
        throw new UncheckedIOException(new IOException("locked"));
      }
      return "done";
    }, 3, RetryPolicy::isTransient);

    assertThat(result).isEqualTo("done");
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void givesUpAfterMaxAttempts() {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(() -> RetryPolicy.execute(() -> {
      calls.incrementAndGet();
      throw new UncheckedIOException(new IOException("still locked"));
    }, 3, RetryPolicy::isTransient)).isInstanceOf(UncheckedIOException.class);
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void nonTransientFailsFast() {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(() -> RetryPolicy.execute(() -> {
      calls.incrementAndGet();
      throw new IllegalArgumentException("bad input");
    }, 3, RetryPolicy::isTransient)).isInstanceOf(IllegalArgumentException.class);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void ioErrorsAreTransient() {
    assertThat(RetryPolicy.isTransient(new UncheckedIOException(new IOException("x"))))
        .isTrue();
    assertThat(RetryPolicy.isTransient(new IOException("x"))).isTrue();
    assertThat(RetryPolicy.isTransient(new IllegalStateException("x"))).isFalse();
  }
}
