package com.tenderops.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.ai.ConditionNode;
import com.tenderops.ai.MetaApiTransport;
import com.tenderops.common.PromptSanitizer;
import com.tenderops.company.CertificateRepository;
import com.tenderops.company.Company;
import com.tenderops.company.CompanyRepository;
import com.tenderops.company.FinancialRecordRepository;
import com.tenderops.company.ProjectRepository;
import com.tenderops.compliance.CompanySnapshot;
import com.tenderops.compliance.EvaluatedRequirement;
import com.tenderops.compliance.EvaluationContext;
import com.tenderops.compliance.RequirementEvaluator;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.runs.ComplianceResult;
import com.tenderops.runs.ComplianceResultRepository;
import com.tenderops.runs.ComplianceRun;
import com.tenderops.runs.ComplianceRunRepository;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.visual.AiCallLogger;
import com.tenderops.web.BadRequestException;
import com.tenderops.web.NotFoundException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Bounded verification-explanation agent (section 8). MODEL_DRIVEN when a key and the
 * tool-calling capability are present, else the deterministic orchestrator labelled
 * FALLBACK_DETERMINISTIC. The persisted verdict ALWAYS equals the Java engine output,
 * regardless of any model text (TC-AGT-03).
 */
@Service
public class AgentService {

  private static final Logger LOG = LoggerFactory.getLogger(AgentService.class);

  private final TenderRepository tenders;
  private final TenderVersionRepository versions;
  private final TenderRequirementRepository requirements;
  private final CompanyRepository companies;
  private final FinancialRecordRepository financials;
  private final ProjectRepository projects;
  private final CertificateRepository certificates;
  private final ComplianceRunRepository runs;
  private final ComplianceResultRepository results;
  private final RequirementEvaluator evaluator;
  private final AgentTools tools;
  private final AgentTx agentTx;
  private final MetaApiTransport transport;
  private final AiCallLogger callLogger;
  private final TenderopsProperties properties;
  private final ObjectMapper mapper;
  private final AgentLimits limits;

  @SuppressWarnings("checkstyle:ParameterNumber")
  @Autowired
  public AgentService(TenderRepository tenders, TenderVersionRepository versions,
      TenderRequirementRepository requirements, CompanyRepository companies,
      FinancialRecordRepository financials, ProjectRepository projects,
      CertificateRepository certificates, ComplianceRunRepository runs,
      ComplianceResultRepository results, RequirementEvaluator evaluator, AgentTools tools,
      AgentTx agentTx, MetaApiTransport transport, AiCallLogger callLogger,
      TenderopsProperties properties, ObjectMapper mapper, AgentLimits limits) {
    this.tenders = tenders;
    this.versions = versions;
    this.requirements = requirements;
    this.companies = companies;
    this.financials = financials;
    this.projects = projects;
    this.certificates = certificates;
    this.runs = runs;
    this.results = results;
    this.evaluator = evaluator;
    this.tools = tools;
    this.agentTx = agentTx;
    this.transport = transport;
    this.callLogger = callLogger;
    this.properties = properties;
    this.mapper = mapper;
    this.limits = limits;
  }

  /**
   * Runs the bounded loop inline and returns the agent run id. Not transactional:
   * steps commit one by one so the trace survives a later failure.
   */
  public long run(long versionId, long companyId, Long requirementId) {
    TenderVersion version = versions.findById(versionId)
        .orElseThrow(() -> new NotFoundException("Version not found"));
    Tender tender = tenders.findById(version.getTenderId())
        .orElseThrow(() -> new NotFoundException("Version not found"));
    if (tender.getCompanyId() != companyId) {
      throw new NotFoundException("Version not found");
    }
    List<ComplianceRun> versionRuns = runs.findByTenderVersionIdOrderByIdDesc(versionId);
    if (versionRuns.isEmpty()) {
      throw new BadRequestException("No compliance run to explain for this version");
    }
    ComplianceRun run = versionRuns.get(0);
    if (run.getCompanyId() != companyId) {
      throw new NotFoundException("Version not found");
    }
    List<ComplianceResult> runRows = results.findByRunIdOrderById(run.getId());
    if (runRows.isEmpty()) {
      throw new BadRequestException("Nothing to explain: the run has no results");
    }
    TenderRequirement target = targetRequirement(tender.getId(), runRows, requirementId);
    Company company = companies.findById(companyId)
        .orElseThrow(() -> new NotFoundException("Company not found"));
    CompanySnapshot snapshot = new CompanySnapshot(
        financials.findByCompanyIdOrderByFyEndDateDesc(companyId),
        projects.findByCompanyIdOrderById(companyId),
        certificates.findByCompanyIdOrderById(companyId));
    EvaluationContext evalContext = EvaluationContext.of(effectiveDeadline(version),
        effectiveEstimated(version), properties.ruleVersion(), company.getDataVersion());
    AgentTools.AgentContext ctx = new AgentTools.AgentContext(companyId, run.getId(),
        version.getId(), tender.getId(), snapshot, evalContext);

    AgentRun agentRun = agentTx.createRun(run.getId(), target.getId(),
        AgentRun.FALLBACK_DETERMINISTIC);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      long startMs = System.currentTimeMillis();
      boolean modelDriven = modelAvailable()
          && driveWithModel(agentRun, version, run, target, ctx, executor, startMs);
      if (!modelDriven) {
        driveDeterministic(agentRun, version, target, ctx, executor, startMs);
      }
      EvaluatedRequirement engine =
          evaluator.evaluate(target, snapshot, evalContext);
      String mode = modelDriven ? AgentRun.MODEL_DRIVEN : AgentRun.FALLBACK_DETERMINISTIC;
      agentTx.finishRun(agentRun.getId(), mode, engine.verdict().name());
      return agentRun.getId();
    } finally {
      executor.shutdownNow();
    }
  }

  private boolean modelAvailable() {
    String key = properties.metaApi().key();
    return key != null && !key.isBlank() && properties.muse() != null
        && properties.muse().toolCalling();
  }

  private TenderRequirement targetRequirement(long tenderId, List<ComplianceResult> runRows,
      Long requirementId) {
    if (requirementId != null) {
      TenderRequirement row = requirements.findById(requirementId).orElse(null);
      if (row == null || !tenderOwns(tenderId, row)) {
        throw new NotFoundException("Requirement not found for this tender");
      }
      return row;
    }
    Map<Long, TenderRequirement> byId = new LinkedHashMap<>();
    for (ComplianceResult row : runRows) {
      byId.computeIfAbsent(row.getRequirementId(),
          missing -> requirements.findById(missing).orElse(null));
    }
    byId.values().removeIf(value -> value == null);
    for (ComplianceResult row : runRows) {
      TenderRequirement candidate = byId.get(row.getRequirementId());
      if (candidate != null && candidate.isMandatory() && !"PASS".equals(row.getVerdict())) {
        return candidate;
      }
    }
    TenderRequirement first = byId.get(runRows.get(0).getRequirementId());
    if (first == null) {
      throw new BadRequestException("Nothing to explain: results reference no requirements");
    }
    return first;
  }

  private boolean tenderOwns(long tenderId, TenderRequirement row) {
    TenderVersion rowVersion = versions.findById(row.getTenderVersionId()).orElse(null);
    return rowVersion != null && rowVersion.getTenderId() == tenderId;
  }

  private OffsetDateTime effectiveDeadline(TenderVersion version) {
    OffsetDateTime deadline = version.getSubmissionDeadline();
    if (deadline != null) {
      return deadline;
    }
    for (TenderVersion candidate : versions.findByTenderIdOrderByVersionNumber(
        version.getTenderId())) {
      if (candidate.getVersionNumber() > version.getVersionNumber()) {
        break;
      }
      if (candidate.getSubmissionDeadline() != null) {
        deadline = candidate.getSubmissionDeadline();
      }
    }
    return deadline;
  }

  private BigDecimal effectiveEstimated(TenderVersion version) {
    BigDecimal estimated = version.getEstimatedValue();
    if (estimated != null) {
      return estimated;
    }
    for (TenderVersion candidate : versions.findByTenderIdOrderByVersionNumber(
        version.getTenderId())) {
      if (candidate.getVersionNumber() > version.getVersionNumber()) {
        break;
      }
      if (candidate.getEstimatedValue() != null) {
        estimated = candidate.getEstimatedValue();
      }
    }
    return estimated;
  }

  /** Fixed explanation plan; every step is recorded with its latency. */
  private void driveDeterministic(AgentRun agentRun, TenderVersion version,
      TenderRequirement target, AgentTools.AgentContext ctx, ExecutorService executor,
      long startMs) {
    List<PlannedCall> plan = new ArrayList<>();
    plan.add(new PlannedCall(AgentTools.GET_REQUIREMENT,
        Map.of("requirementId", target.getId())));
    // Data tools follow the top-level metric AND the condition-tree leaves, so
    // composite requirements (metric OTHER + tree) gather the right evidence.
    TreeEvidence evidence = treeEvidence(target);
    if (evidence.financial()) {
      plan.add(new PlannedCall(AgentTools.GET_FINANCIALS, Map.of()));
    }
    if (evidence.projects()) {
      Map<String, Object> args = new LinkedHashMap<>();
      if (evidence.domain() != null) {
        args.put("domain", evidence.domain());
      }
      plan.add(new PlannedCall(AgentTools.SEARCH_PROJECTS, args));
    }
    if (evidence.certificates()) {
      Map<String, Object> args = new LinkedHashMap<>();
      if (evidence.certName() != null) {
        args.put("name", evidence.certName());
      } else if ("SOLVENCY_CERTIFICATE".equals(target.getMetric())) {
        args.put("name", "solvency");
      }
      plan.add(new PlannedCall(AgentTools.VERIFY_CERTS, args));
    }
    if (plan.size() == 1) {
      plan.add(new PlannedCall(AgentTools.FIND_MISSING_DOCS, Map.of()));
    }
    if (!AgentTools.FIND_MISSING_DOCS.equals(plan.get(plan.size() - 1).name())) {
      plan.add(new PlannedCall(AgentTools.FIND_MISSING_DOCS, Map.of()));
    }
    plan.add(new PlannedCall(AgentTools.RUN_COMPLIANCE,
        Map.of("requirementId", target.getId())));
    int stepNo = 0;
    for (PlannedCall call : plan) {
      if (stepNo >= limits.maxSteps() || overBudget(startMs)) {
        break;
      }
      stepNo++;
      executeStep(agentRun, version, ctx, executor, stepNo, call.name(), call.args());
    }
  }

  /**
   * Model-driven loop against the (UNVERIFIED) tool-call response shape. Returns true
   * only if at least one model response was actually used.
   */
  private boolean driveWithModel(AgentRun agentRun, TenderVersion version, ComplianceRun run,
      TenderRequirement target, AgentTools.AgentContext ctx, ExecutorService executor,
      long startMs) {
    String key = properties.metaApi().key();
    String url = properties.metaApi().baseUrl() + properties.metaApi().responsesPath();
    List<Map<String, Object>> transcript = new ArrayList<>();
    int stepNo = 0;
    int modelCalls = 0;
    boolean used = false;
    while (stepNo < limits.maxSteps() && !overBudget(startMs)) {
      String input = modelInput(run, target, transcript);
      String body;
      try {
        body = mapper.writeValueAsString(Map.of("model",
            properties.metaApi().museModel(), "input", input, "max_output_tokens", 800));
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("Cannot serialize agent request", e);
      }
      long callStart = System.currentTimeMillis();
      String response;
      try {
        response = transport.postJson(url, key, body,
            properties.metaApi().requestTimeoutMs(), properties.metaApi().maxAttempts(),
            properties.metaApi().backoffInitialMs(), outcome -> {
            });
      } catch (Exception e) {
        callLogger.log(run.getId(), "MUSE", properties.metaApi().museModel(), "agent-loop",
            "ERROR", modelCalls + 1, System.currentTimeMillis() - callStart, "FALLBACK",
            e.getMessage());
        LOG.warn("Agent model call failed, engine decides: {}", e.getMessage());
        break;
      }
      long latency = System.currentTimeMillis() - callStart;
      modelCalls++;
      String outputText;
      try {
        outputText = MetaApiTransport.extractOutputText(mapper, response);
      } catch (Exception e) {
        callLogger.log(run.getId(), "MUSE", properties.metaApi().museModel(), "agent-loop",
            "ERROR", modelCalls, latency, "FALLBACK", "unparseable model response");
        break;
      }
      callLogger.log(run.getId(), "MUSE", properties.metaApi().museModel(), "agent-loop",
          "OK", modelCalls, latency, "LIVE", null);
      used = true;
      JsonNode parsed;
      try {
        parsed = mapper.readTree(outputText);
      } catch (JsonProcessingException e) {
        LOG.warn("Agent model output is not JSON, engine decides");
        break;
      }
      List<PlannedCall> calls = toolCallsOf(parsed);
      Map<String, Object> turn = new LinkedHashMap<>();
      turn.put("model", textOf(parsed, "explanation"));
      transcript.add(turn);
      if (calls.isEmpty() || isTrue(parsed, "done")) {
        if (stepNo < limits.maxSteps() && !overBudget(startMs)) {
          stepNo++;
          Map<String, Object> summary = new LinkedHashMap<>();
          summary.put("verdictAssertion", textOf(parsed, "verdictAssertion"));
          summary.put("explanation", textOf(parsed, "explanation"));
          summary.put("note", "Advisory model text; the persisted verdict always equals "
              + "the Java engine output.");
          persistStep(agentRun, version, ctx, stepNo, "MODEL_SUMMARY", Map.of(), summary,
              latency, null);
        }
        break;
      }
      for (PlannedCall call : calls) {
        if (stepNo >= limits.maxSteps() || overBudget(startMs)) {
          break;
        }
        stepNo++;
        AgentTools.ToolResult result =
            executeStep(agentRun, version, ctx, executor, stepNo, call.name(), call.args());
        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("tool", call.name());
        feedback.put("ok", result.ok());
        feedback.put("output", result.data());
        transcript.add(feedback);
      }
    }
    return used;
  }

  private String modelInput(ComplianceRun run, TenderRequirement target,
      List<Map<String, Object>> transcript) {
    StringBuilder input = new StringBuilder();
    input.append("You are a verification assistant explaining one tender requirement. ");
    input.append(PromptSanitizer.systemPromptStatement()).append(' ');
    input.append("Call tools as JSON only: {\"tool_calls\":[{\"name\":...,\"arguments\":...}],"
        + "\"verdictAssertion\":...,\"explanation\":...,\"done\":boolean}. Tools: ");
    input.append(AgentTools.schemas()).append(' ');
    input.append("Target requirement ").append(target.getId()).append(" clause ")
        .append(target.getClauseRef()).append(" metric ").append(target.getMetric())
        .append(" operator ").append(target.getOperator()).append(" amountText ")
        .append(target.getAmountText()).append(". Run decision ").append(run.getDecision())
        .append(" score ").append(run.getReadinessScore()).append(". Quote: ");
    input.append(PromptSanitizer.wrapExcerpt(target.getSourcePage(),
        target.getOriginalQuote()));
    for (Map<String, Object> turn : transcript) {
      input.append(" | ").append(turn);
    }
    return input.toString();
  }

  private List<PlannedCall> toolCallsOf(JsonNode parsed) {
    List<PlannedCall> calls = new ArrayList<>();
    JsonNode array = parsed.get("tool_calls");
    if (array == null || !array.isArray()) {
      return calls;
    }
    for (JsonNode item : array) {
      String name = textOf(item, "name");
      if (name == null) {
        continue;
      }
      Map<String, Object> args = new LinkedHashMap<>();
      JsonNode arguments = item.get("arguments");
      if (arguments != null && arguments.isObject()) {
        arguments.fields().forEachRemaining(entry -> args.put(entry.getKey(),
            jsonValue(entry.getValue())));
      }
      calls.add(new PlannedCall(name, args));
    }
    return calls;
  }

  private Object jsonValue(JsonNode node) {
    if (node.isTextual()) {
      return node.asText();
    }
    if (node.isIntegralNumber()) {
      return node.asLong();
    }
    if (node.isNumber()) {
      return node.asDouble();
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isNull()) {
      return null;
    }
    return node.toString();
  }

  private static String textOf(JsonNode node, String field) {
    JsonNode child = node.get(field);
    return child != null && child.isTextual() ? child.asText() : null;
  }

  private static boolean isTrue(JsonNode node, String field) {
    JsonNode child = node.get(field);
    return child != null && child.isBoolean() && child.asBoolean();
  }

  private boolean overBudget(long startMs) {
    return System.currentTimeMillis() - startMs > limits.runBudgetMs();
  }

  private AgentTools.ToolResult executeStep(AgentRun agentRun, TenderVersion version,
      AgentTools.AgentContext ctx, ExecutorService executor, int stepNo, String toolName,
      Map<String, Object> args) {
    long start = System.currentTimeMillis();
    Future<AgentTools.ToolResult> future =
        executor.submit(() -> tools.execute(toolName, args, ctx));
    AgentTools.ToolResult result;
    String error = null;
    try {
      result = future.get(limits.toolTimeoutMs(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      error = "TOOL_TIMEOUT after " + limits.toolTimeoutMs() + "ms";
      result = AgentTools.ToolResult.error(error);
    } catch (Exception e) {
      error = e.getMessage() == null ? e.toString() : e.getMessage();
      result = AgentTools.ToolResult.error(error);
    }
    long latency = System.currentTimeMillis() - start;
    if (!result.ok() && error == null) {
      error = result.error();
    }
    persistStep(agentRun, version, ctx, stepNo, toolName, args, result.data(), latency,
        error);
    return result;
  }

  private void persistStep(AgentRun agentRun, TenderVersion version,
      AgentTools.AgentContext ctx, int stepNo, String toolName, Map<String, Object> args,
      Map<String, Object> output, long latency, String error) {
    String inputJson;
    String outputJson;
    try {
      inputJson = mapper.writeValueAsString(args == null ? Map.of() : args);
      outputJson = mapper.writeValueAsString(output == null ? Map.of() : output);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize agent step", e);
    }
    String trimmedError = error != null && error.length() > 2000 ? error.substring(0, 2000)
        : error;
    agentTx.saveStep(version.getId(), ctx.companyId(), agentRun.getId(), stepNo, toolName,
        inputJson, outputJson, latency, trimmedError);
  }

  private record PlannedCall(String name, Map<String, Object> args) {
  }

  private record TreeEvidence(boolean financial, boolean projects, boolean certificates,
      String domain, String certName) {
  }

  private TreeEvidence treeEvidence(TenderRequirement target) {
    boolean financial = false;
    boolean projects = false;
    boolean certificates = false;
    String domain = null;
    String certName = null;
    List<String> metrics = new ArrayList<>();
    if (target.getMetric() != null) {
      metrics.add(target.getMetric());
    }
    if (target.getDomainTags() != null && !target.getDomainTags().isBlank()) {
      domain = target.getDomainTags().split(",")[0].trim();
    }
    if (target.getCertificateName() != null) {
      certName = target.getCertificateName();
    }
    if (target.getConditionTree() != null && !target.getConditionTree().isBlank()) {
      try {
        ConditionNode root = mapper.readValue(target.getConditionTree(), ConditionNode.class);
        List<ConditionNode> leaves = new ArrayList<>();
        collectLeaves(root, leaves);
        for (ConditionNode leaf : leaves) {
          if (leaf.metric() != null) {
            metrics.add(leaf.metric());
          }
          if (domain == null && leaf.domainTags() != null && !leaf.domainTags().isEmpty()) {
            domain = leaf.domainTags().get(0);
          }
          if (certName == null && leaf.certificateName() != null) {
            certName = leaf.certificateName();
          }
        }
      } catch (JsonProcessingException e) {
        LOG.debug("Agent plan ignores unparseable condition tree");
      }
    }
    for (String metric : metrics) {
      switch (metric) {
        case "AVG_ANNUAL_TURNOVER", "NET_WORTH" -> financial = true;
        case "SIMILAR_PROJECTS_COUNT_VALUE", "SIMILAR_PROJECTS_80_50_40" -> projects = true;
        case "CERTIFICATE_VALID", "SOLVENCY_CERTIFICATE" -> certificates = true;
        default -> {
          // OTHER and unknown metrics need no dedicated data tool.
        }
      }
    }
    return new TreeEvidence(financial, projects, certificates, domain, certName);
  }

  private static void collectLeaves(ConditionNode node, List<ConditionNode> leaves) {
    if (node == null) {
      return;
    }
    if (!node.isBranch()) {
      leaves.add(node);
      return;
    }
    if (node.children() != null) {
      for (ConditionNode child : node.children()) {
        collectLeaves(child, leaves);
      }
    }
  }
}
