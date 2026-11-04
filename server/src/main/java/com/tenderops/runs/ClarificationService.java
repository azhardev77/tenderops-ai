package com.tenderops.runs;

import com.tenderops.tender.TenderRequirement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Pre-bid clarification questions (section 6.12): one per NEEDS_REVIEW result, phrased
 * from the reason code and the requirement's own fields.
 */
@Service
public class ClarificationService {

  public record ClarificationItem(String clauseRef, String question) {
  }

  public List<ClarificationItem> clarifications(List<ComplianceResult> rows,
      Map<Long, TenderRequirement> byId) {
    List<ClarificationItem> out = new ArrayList<>();
    for (ComplianceResult row : rows) {
      if (!"NEEDS_REVIEW".equals(row.getVerdict())) {
        continue;
      }
      TenderRequirement requirement = byId.get(row.getRequirementId());
      if (requirement == null) {
        continue;
      }
      out.add(new ClarificationItem(requirement.getClauseRef(),
          question(requirement, row.getReasonCode())));
    }
    return out;
  }

  private static String question(TenderRequirement requirement, String reasonCode) {
    String clause = "Clause " + requirement.getClauseRef();
    String code = reasonCode == null ? "" : reasonCode;
    return switch (code) {
      case "QUOTE_NOT_VERIFIED" -> clause + ": the extracted quote could not be verified "
          + "against page " + requirement.getSourcePage()
          + ". Please confirm the exact clause wording.";
      case "LOW_CONFIDENCE", "CONFIDENCE_OUT_OF_RANGE" -> clause
          + ": extraction confidence is below the 0.80 review threshold. "
          + "Please review the extracted values.";
      case "UNAUDITED_RECORD", "NO_AUDITED_RECORD" -> clause
          + ": company financial records are marked unaudited. "
          + "Please confirm whether audited statements are available.";
      case "MISSING_SUPPORTING_DOCUMENT", "MISSING_COMPLETION_CERTIFICATE" -> clause
          + ": the supporting company record has no attached document. "
          + "Please upload the document or confirm it is unavailable.";
      case "CURRENCY_UNSUPPORTED" -> clause
          + ": a record or requirement uses a non-INR currency (only INR is supported). "
          + "Please clarify the applicable amount.";
      case "AMOUNT_MISSING", "AMOUNT_UNPARSEABLE", "VALUE_MISSING" -> clause
          + ": the required amount could not be determined. "
          + "Please confirm the threshold value.";
      case "COUNT_MISSING", "AMBIGUOUS_WINDOW" -> clause
          + ": the project count or lookback window is ambiguous. "
          + "Please confirm the exact eligibility window.";
      case "METRIC_UNSUPPORTED" -> clause
          + ": the requirement type is not recognized by the deterministic engine. "
          + "Please verify it manually.";
      case "EXPIRY_MISSING" -> clause
          + ": a certificate has no recorded expiry date. "
          + "Please confirm the validity period.";
      case "INCOMPLETE_PROJECT_EVIDENCE" -> clause
          + ": project evidence is incomplete (missing value or dates). "
          + "Please complete the project record.";
      case "TREE_INVALID" -> clause
          + ": the condition tree could not be parsed. Please confirm the clause logic.";
      case "CERT_NAME_MISSING" -> clause
          + ": the certificate name could not be determined. "
          + "Please confirm which certificate applies.";
      case "ESTIMATED_VALUE_MISSING" -> clause
          + ": the tender has no estimated contract value, so the 80/50/40 rule "
          + "cannot be evaluated. Please confirm the estimate.";
      default -> clause + ": this item needs human review (" + code
          + "). Please confirm the evidence.";
    };
  }
}
