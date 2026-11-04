package com.tenderops.runs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TC-OPS-04: the summary request survives high-effort reasoning (budget + effort). */
class SummaryServiceTest {

  @Test
  void summaryRequestCarriesReasoningBudget() throws Exception {
    String json = SummaryService.summaryRequestJson(new ObjectMapper(), "muse-spark-1.3",
        List.of("Tender reference unknown", "Decision INSUFFICIENT_DATA"));

    JsonNode root = new ObjectMapper().readTree(json);
    assertThat(root.get("model").asText()).isEqualTo("muse-spark-1.3");
    assertThat(root.get("input").asText()).contains("Tender reference unknown");
    assertThat(root.get("max_output_tokens").asInt()).isEqualTo(2000);
    assertThat(root.get("reasoning").get("effort").asText()).isEqualTo("low");
  }
}
