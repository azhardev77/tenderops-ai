package com.tenderops.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.company.Certificate;
import com.tenderops.company.CertificateRepository;
import com.tenderops.company.FinancialRecord;
import com.tenderops.company.FinancialRecordRepository;
import com.tenderops.company.Project;
import com.tenderops.company.ProjectRepository;
import com.tenderops.compliance.CompanySnapshot;
import com.tenderops.compliance.EvaluatedRequirement;
import com.tenderops.compliance.EvaluationContext;
import com.tenderops.compliance.RequirementEvaluator;
import com.tenderops.runs.ChecklistService;
import com.tenderops.runs.ComplianceResult;
import com.tenderops.runs.ComplianceResultRepository;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Agent tools (section 8): strict JSON-schema-style arguments, read-only except the
 * Java-engine call. Invalid arguments return a structured error so the model can
 * recover; tool outputs never decide anything (the engine verdict always wins).
 */
@Service
public class AgentTools {

  public static final String GET_FINANCIALS = "get_company_financials";
  public static final String SEARCH_PROJECTS = "search_company_projects";
  public static final String VERIFY_CERTS = "verify_certificates";
  public static final String GET_REQUIREMENT = "get_requirement";
  public static final String FIND_MISSING_DOCS = "find_missing_documents";
  public static final String RUN_COMPLIANCE = "run_deterministic_compliance";

  public static final Set<String> TOOL_NAMES = Set.of(GET_FINANCIALS, SEARCH_PROJECTS,
      VERIFY_CERTS, GET_REQUIREMENT, FIND_MISSING_DOCS, RUN_COMPLIANCE);

  public record ToolResult(boolean ok, Map<String, Object> data, String error) {
    public static ToolResult ok(Map<String, Object> data) {
      return new ToolResult(true, data, null);
    }

    public static ToolResult error(String error) {
      return new ToolResult(false, Map.of("error", error), error);
    }
  }

  /** Everything a tool may read: one company, one run, one tender version. */
  public record AgentContext(long companyId, long runId, long versionId, long tenderId,
      CompanySnapshot snapshot, EvaluationContext evalContext) {
  }

  private final FinancialRecordRepository financials;
  private final ProjectRepository projects;
  private final CertificateRepository certificates;
  private final TenderRequirementRepository requirements;
  private final ComplianceResultRepository results;
  private final ChecklistService checklists;
  private final RequirementEvaluator evaluator;
  private final ObjectMapper mapper;

  public AgentTools(FinancialRecordRepository financials, ProjectRepository projects,
      CertificateRepository certificates, TenderRequirementRepository requirements,
      ComplianceResultRepository results, ChecklistService checklists,
      RequirementEvaluator evaluator, ObjectMapper mapper) {
    this.financials = financials;
    this.projects = projects;
    this.certificates = certificates;
    this.requirements = requirements;
    this.results = results;
    this.checklists = checklists;
    this.evaluator = evaluator;
    this.mapper = mapper;
  }

  /** Compact tool schemas for the model prompt (names, params, types). */
  public static String schemas() {
    return """
        get_company_financials{} \
        search_company_projects{domain?:string,minValue?:money-string} \
        verify_certificates{name?:string} get_requirement{requirementId:integer} \
        find_missing_documents{} run_deterministic_compliance{requirementId:integer}""";
  }

  public ToolResult execute(String toolName, Map<String, Object> args, AgentContext ctx) {
    Map<String, Object> safeArgs = args == null ? Map.of() : args;
    return switch (toolName == null ? "" : toolName) {
      case GET_FINANCIALS -> {
        String unknown = rejectUnknown(safeArgs, Set.of());
        yield unknown != null ? ToolResult.error(unknown) : financials(ctx);
      }
      case SEARCH_PROJECTS -> searchProjects(safeArgs, ctx);
      case VERIFY_CERTS -> verifyCerts(safeArgs, ctx);
      case GET_REQUIREMENT -> getRequirement(safeArgs, ctx);
      case FIND_MISSING_DOCS -> {
        String unknown = rejectUnknown(safeArgs, Set.of());
        yield unknown != null ? ToolResult.error(unknown) : missingDocs(ctx);
      }
      case RUN_COMPLIANCE -> runCompliance(safeArgs, ctx);
      default -> ToolResult.error("Unknown tool '" + toolName + "'. Known tools: "
          + String.join(", ", TOOL_NAMES.stream().sorted().toList()));
    };
  }

  private ToolResult financials(AgentContext ctx) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (FinancialRecord record
        : financials.findByCompanyIdOrderByFyEndDateDesc(ctx.companyId())) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("fyLabel", record.getFyLabel());
      row.put("fyEndDate", String.valueOf(record.getFyEndDate()));
      row.put("turnover", plain(record.getTurnover()));
      row.put("netWorth", plain(record.getNetWorth()));
      row.put("currency", record.getCurrency());
      row.put("audited", record.isAudited());
      row.put("documentId", record.getDocumentId());
      rows.add(row);
    }
    return ToolResult.ok(Map.of("records", rows));
  }

  private ToolResult searchProjects(Map<String, Object> args, AgentContext ctx) {
    String unknown = rejectUnknown(args, Set.of("domain", "minValue"));
    if (unknown != null) {
      return ToolResult.error(unknown);
    }
    String domain = stringArg(args, "domain");
    if (args.containsKey("domain") && domain == null) {
      return ToolResult.error("search_company_projects.domain must be a string");
    }
    BigDecimal minValue = null;
    if (args.containsKey("minValue")) {
      String raw = stringArg(args, "minValue");
      if (raw == null) {
        return ToolResult.error("search_company_projects.minValue must be a money string");
      }
      try {
        minValue = new BigDecimal(raw.trim());
      } catch (NumberFormatException e) {
        return ToolResult.error("search_company_projects.minValue is not a number: " + raw);
      }
      if (minValue.compareTo(BigDecimal.ZERO) < 0) {
        return ToolResult.error("search_company_projects.minValue cannot be negative");
      }
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Project project : projects.findByCompanyIdOrderById(ctx.companyId())) {
      if (domain != null && !domain.equalsIgnoreCase(project.getDomainCategory())) {
        continue;
      }
      if (minValue != null && (project.getContractValue() == null
          || project.getContractValue().compareTo(minValue) < 0)) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", project.getName());
      row.put("contractValue", plain(project.getContractValue()));
      row.put("currency", project.getCurrency());
      row.put("status", project.getStatus());
      row.put("completionDate", String.valueOf(project.getCompletionDate()));
      row.put("domainCategory", project.getDomainCategory());
      rows.add(row);
    }
    return ToolResult.ok(Map.of("projects", rows));
  }

  private ToolResult verifyCerts(Map<String, Object> args, AgentContext ctx) {
    String unknown = rejectUnknown(args, Set.of("name"));
    if (unknown != null) {
      return ToolResult.error(unknown);
    }
    String name = stringArg(args, "name");
    if (args.containsKey("name") && name == null) {
      return ToolResult.error("verify_certificates.name must be a string");
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Certificate certificate : certificates.findByCompanyIdOrderById(ctx.companyId())) {
      if (name != null
          && !certificate.getName().toLowerCase().contains(name.toLowerCase())) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", certificate.getName());
      row.put("levelText", certificate.getLevelText());
      row.put("levelNumber", certificate.getLevelNumber());
      row.put("issueDate", String.valueOf(certificate.getIssueDate()));
      row.put("expiryDate", String.valueOf(certificate.getExpiryDate()));
      row.put("amount", plain(certificate.getAmount()));
      row.put("currency", certificate.getCurrency());
      row.put("hasDocument", certificate.getDocumentId() != null);
      rows.add(row);
    }
    return ToolResult.ok(Map.of("certificates", rows));
  }

  private ToolResult getRequirement(Map<String, Object> args, AgentContext ctx) {
    String unknown = rejectUnknown(args, Set.of("requirementId"));
    if (unknown != null) {
      return ToolResult.error(unknown);
    }
    Long id = longArg(args, "requirementId");
    if (id == null) {
      return ToolResult.error("get_requirement.requirementId is required and must be "
          + "an integer");
    }
    TenderRequirement requirement = requirements.findById(id).orElse(null);
    if (requirement == null) {
      return ToolResult.error("No requirement with id " + id);
    }
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", requirement.getId());
    row.put("clauseRef", requirement.getClauseRef());
    row.put("category", requirement.getCategory());
    row.put("mandatory", requirement.isMandatory());
    row.put("metric", requirement.getMetric());
    row.put("operator", requirement.getOperator());
    row.put("amountText", requirement.getAmountText());
    row.put("amountValue", plain(requirement.getAmountValue()));
    row.put("countValue", requirement.getCountValue());
    row.put("lookbackYears", requirement.getLookbackYears());
    row.put("domainTags", requirement.getDomainTags());
    row.put("certificateName", requirement.getCertificateName());
    row.put("certificateLevel", requirement.getCertificateLevel());
    row.put("sourcePage", requirement.getSourcePage());
    row.put("originalQuote", requirement.getOriginalQuote());
    row.put("quoteVerified", requirement.isQuoteVerified());
    return ToolResult.ok(Map.of("requirement", row));
  }

  private ToolResult missingDocs(AgentContext ctx) {
    List<ComplianceResult> rows = results.findByRunIdOrderById(ctx.runId());
    Map<Long, TenderRequirement> byId = new LinkedHashMap<>();
    for (TenderRequirement requirement
        : requirements.findByTenderVersionIdOrderById(ctx.versionId())) {
      byId.put(requirement.getId(), requirement);
    }
    for (ComplianceResult row : rows) {
      byId.computeIfAbsent(row.getRequirementId(),
          missing -> requirements.findById(missing).orElse(null));
    }
    byId.values().removeIf(value -> value == null);
    return ToolResult.ok(Map.of("checklist", checklists.checklist(rows, byId)));
  }

  private ToolResult runCompliance(Map<String, Object> args, AgentContext ctx) {
    String unknown = rejectUnknown(args, Set.of("requirementId"));
    if (unknown != null) {
      return ToolResult.error(unknown);
    }
    Long id = longArg(args, "requirementId");
    if (id == null) {
      return ToolResult.error("run_deterministic_compliance.requirementId is required "
          + "and must be an integer");
    }
    TenderRequirement requirement = requirements.findById(id).orElse(null);
    if (requirement == null) {
      return ToolResult.error("No requirement with id " + id);
    }
    EvaluatedRequirement evaluated =
        evaluator.evaluate(requirement, ctx.snapshot(), ctx.evalContext());
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("requirementId", evaluated.requirementId());
    row.put("clauseRef", evaluated.clauseRef());
    row.put("verdict", evaluated.verdict().name());
    row.put("reasonCode", evaluated.reasonCode());
    row.put("evidenceIds", evaluated.evidenceIds());
    try {
      row.put("trace", mapper.readTree(evaluated.traceJson()));
    } catch (Exception e) {
      row.put("trace", evaluated.traceJson());
    }
    return ToolResult.ok(Map.of("evaluation", row));
  }

  private static String rejectUnknown(Map<String, Object> args, Set<String> known) {
    for (String key : args.keySet()) {
      if (!known.contains(key)) {
        return "Unknown argument '" + key + "'. Known arguments: "
            + (known.isEmpty() ? "(none)" : String.join(", ", known.stream().sorted()
                .toList()));
      }
    }
    return null;
  }

  private static String stringArg(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value == null) {
      return null;
    }
    return value instanceof String s ? s : null;
  }

  private static Long longArg(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value instanceof Integer i) {
      return i.longValue();
    }
    if (value instanceof Long l) {
      return l;
    }
    return null;
  }

  private static String plain(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }
}
