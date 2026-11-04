package com.tenderops.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Responses-API output-text extraction, including incomplete-run diagnostics. */
class MetaApiTransportTest {

  @Test
  void incompleteResponseNamesTheReason() {
    String body = "{\"status\":\"incomplete\","
        + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}";

    assertThatThrownBy(
        () -> MetaApiTransport.extractOutputText(new ObjectMapper(), body))
        .isInstanceOf(TransportFormatException.class)
        .hasMessageContaining("max_output_tokens");
  }

  @Test
  void messageOutputTextStillExtracts() {
    String body = "{\"output\":[{\"type\":\"message\",\"content\":"
        + "[{\"type\":\"output_text\",\"text\":\"hello\"}]}]}";

    assertThat(MetaApiTransport.extractOutputText(new ObjectMapper(), body))
        .isEqualTo("hello");
  }
}
