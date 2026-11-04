package com.tenderops.amendment;

import java.math.BigDecimal;
import java.util.List;

/** Clause-level amendment diff, serialized to amendments.diff_json. */
public record AmendmentDiff(List<String> inserted, List<String> deleted,
    List<ModifiedClause> modified, boolean deadlineChanged, String prevDecision,
    String newDecision, BigDecimal scoreDelta) {

  public record ModifiedClause(String clauseRef, boolean thresholdChanged,
      boolean operatorChanged, boolean conditionTreeChanged) {
  }
}
