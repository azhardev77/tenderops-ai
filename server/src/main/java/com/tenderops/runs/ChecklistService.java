package com.tenderops.runs;

import com.tenderops.tender.TenderRequirement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Missing-document checklist (section 6.12): one entry per UNKNOWN result, derived from
 * the requirement's own fields (never golden literals).
 */
@Service
public class ChecklistService {

  public record ChecklistItem(String clauseRef, String requirement, String missingItem,
      String correctiveAction) {
  }

  public List<ChecklistItem> checklist(List<ComplianceResult> rows,
      Map<Long, TenderRequirement> byId) {
    List<ChecklistItem> out = new ArrayList<>();
    for (ComplianceResult row : rows) {
      if (!"UNKNOWN".equals(row.getVerdict())) {
        continue;
      }
      TenderRequirement requirement = byId.get(row.getRequirementId());
      if (requirement == null) {
        continue;
      }
      out.add(new ChecklistItem(requirement.getClauseRef(), requirement.getOriginalQuote(),
          missingItem(requirement, row.getReasonCode()),
          correctiveAction(requirement, row.getReasonCode())));
    }
    return out;
  }

  private static String missingItem(TenderRequirement requirement, String reasonCode) {
    String metric = requirement.getMetric() == null ? "" : requirement.getMetric();
    return switch (metric) {
      case "AVG_ANNUAL_TURNOVER" -> "Audited turnover records for the last "
          + window(requirement) + " financial years";
      case "NET_WORTH" -> "Audited net-worth record for the last financial year";
      case "SOLVENCY_CERTIFICATE" -> "Bank Solvency Certificate of not less than "
          + amount(requirement);
      case "CERTIFICATE_VALID" -> "Valid " + certificate(requirement) + " certificate";
      case "SIMILAR_PROJECTS_COUNT_VALUE", "SIMILAR_PROJECTS_80_50_40" ->
          "Completion certificates for qualifying similar projects"
              + ("NO_PROJECT_RECORDS".equals(reasonCode) ? " (no project records on file)"
                  : "");
      default -> "Evidence supporting clause " + requirement.getClauseRef();
    };
  }

  private static String correctiveAction(TenderRequirement requirement, String reasonCode) {
    String base = "Add the missing record or document to the company database and Re-Verify.";
    if ("MISSING_SUPPORTING_DOCUMENT".equals(reasonCode)) {
      return "Attach the supporting document to the existing company record and Re-Verify.";
    }
    if ("CERTIFICATE_VALID".equals(requirement.getMetric())) {
      return "Upload the " + certificate(requirement)
          + " certificate with a valid expiry date and Re-Verify.";
    }
    return base;
  }

  private static String window(TenderRequirement requirement) {
    return requirement.getLookbackYears() == null ? "3"
        : String.valueOf(requirement.getLookbackYears());
  }

  private static String amount(TenderRequirement requirement) {
    if (requirement.getAmountText() != null && !requirement.getAmountText().isBlank()) {
      return requirement.getAmountText().trim();
    }
    if (requirement.getAmountValue() != null) {
      return "Rs. " + requirement.getAmountValue().toPlainString();
    }
    return "the required amount";
  }

  private static String certificate(TenderRequirement requirement) {
    StringBuilder name = new StringBuilder(
        requirement.getCertificateName() == null ? "required"
            : requirement.getCertificateName());
    if (requirement.getCertificateLevel() != null) {
      name.append(" Level ").append(requirement.getCertificateLevel());
    }
    return name.toString();
  }
}
