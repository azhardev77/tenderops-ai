package com.tenderops.amendment;

import static com.tenderops.compliance.ComplianceFixtures.GOLD_DOMAINS;
import static com.tenderops.compliance.ComplianceFixtures.cert;
import static com.tenderops.compliance.ComplianceFixtures.fin;
import static com.tenderops.compliance.ComplianceFixtures.proj;
import static com.tenderops.compliance.ComplianceFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.compliance.BidDecisionEngine;
import com.tenderops.compliance.CompanySnapshot;
import com.tenderops.compliance.EvaluatedRequirement;
import com.tenderops.compliance.EvaluationContext;
import com.tenderops.compliance.ReadinessScore;
import com.tenderops.compliance.RequirementEvaluator;
import com.tenderops.compliance.RequirementView;
import com.tenderops.company.Certificate;
import com.tenderops.company.FinancialRecord;
import com.tenderops.company.Project;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * TC-AMD-07: selective re-verification equals full re-evaluation on 120 seeded-random
 * amendment scenarios (verdicts, reasons, traces, decision, score).
 */
class AmendmentDifferentialTest {

  private static final long SEED = 20261105L;
  private static final int ITERATIONS = 120;

  private final RequirementEvaluator evaluator = new RequirementEvaluator(new ObjectMapper());

  private static BigDecimal randomMoney(Random random) {
    long rupees = 10_000_000L + random.nextInt(2_000_000_000);
    return BigDecimal.valueOf(rupees).setScale(random.nextInt(5));
  }

  private static RequirementView randomView(Random random, String clause) {
    String[] metrics = {"AVG_ANNUAL_TURNOVER", "NET_WORTH",
        "SIMILAR_PROJECTS_COUNT_VALUE", "CERTIFICATE_VALID", "SOLVENCY_CERTIFICATE"};
    String[] operators = {"GTE", "GT", "LTE", "LT", "EQ"};
    String metric = metrics[random.nextInt(metrics.length)];
    String operator = operators[random.nextInt(operators.length)];
    boolean mandatory = random.nextBoolean();
    if ("CERTIFICATE_VALID".equals(metric)) {
      String[] names = {"CMMI", "ISO_9001", "ISO_27001"};
      String name = names[random.nextInt(names.length)];
      Integer level = "CMMI".equals(name) && random.nextBoolean()
          ? 1 + random.nextInt(5) : null;
      return view(clause, mandatory, metric, operator, null, null, null, List.of(),
          name, level, null);
    }
    if ("SIMILAR_PROJECTS_COUNT_VALUE".equals(metric)) {
      return view(clause, mandatory, metric, operator, randomMoney(random),
          1 + random.nextInt(3), random.nextBoolean() ? 7 : null, GOLD_DOMAINS, null,
          null, null);
    }
    Integer lookback = "AVG_ANNUAL_TURNOVER".equals(metric) ? 3 : null;
    return view(clause, mandatory, metric, operator, randomMoney(random), null, lookback,
        List.of(), null, null, null);
  }

  private static CompanySnapshot randomSnapshot(Random random) {
    List<FinancialRecord> fins = new ArrayList<>();
    int finCount = random.nextInt(5);
    LocalDate[] ends = {LocalDate.of(2024, 3, 31), LocalDate.of(2025, 3, 31),
        LocalDate.of(2026, 3, 31), LocalDate.of(2027, 3, 31)};
    String[] labels = {"FY2023-24", "FY2024-25", "FY2025-26", "FY2026-27"};
    for (int i = 0; i < finCount && i < 4; i++) {
      fins.add(fin(i + 1L, labels[i], ends[i], randomMoney(random),
          random.nextBoolean() ? randomMoney(random) : null,
          random.nextDouble() < 0.85, random.nextDouble() < 0.85 ? 100L + i : null));
    }
    List<Project> projs = new ArrayList<>();
    int projCount = random.nextInt(5);
    String[] domains = {"SMART_CITY", "HIGHWAY", "METRO_RAIL"};
    for (int i = 0; i < projCount; i++) {
      Project project = proj(10L + i, "Random " + i, randomMoney(random),
          random.nextDouble() < 0.8 ? LocalDate.of(2025, 6, 30)
              : LocalDate.of(2015, 4, 1),
          domains[random.nextInt(domains.length)],
          random.nextDouble() < 0.75 ? 200L + i : null);
      if (random.nextDouble() < 0.1) {
        project.setStatus("IN_PROGRESS");
      }
      projs.add(project);
    }
    List<Certificate> certs = new ArrayList<>();
    int certCount = random.nextInt(4);
    String[] names = {"CMMI", "ISO_9001", "ISO_27001", "BANK_SOLVENCY_CERTIFICATE"};
    for (int i = 0; i < certCount; i++) {
      String name = names[random.nextInt(names.length)];
      certs.add(cert(20L + i, name,
          "CMMI".equals(name) ? 1 + random.nextInt(5) : null,
          random.nextDouble() < 0.7 ? LocalDate.of(2027, 8, 31)
              : LocalDate.of(2026, 3, 31),
          random.nextDouble() < 0.85 ? 300L + i : null,
          name.contains("SOLVENCY") ? randomMoney(random) : null));
    }
    return new CompanySnapshot(fins, projs, certs);
  }

  @Test
  void selectiveEqualsFull() {
    Random random = new Random(SEED);
    for (int iter = 0; iter < ITERATIONS; iter++) {
      try {
        CompanySnapshot snapshot = randomSnapshot(random);
        OffsetDateTime deadline =
            OffsetDateTime.parse("2026-12-15T17:00+05:30");
        EvaluationContext baseCtx = EvaluationContext.of(deadline,
            new BigDecimal("600000000.0000"), "1.0", 1L);
        int baseCount = 4 + random.nextInt(4);
        List<MergedRequirement> base = new ArrayList<>();
        for (int i = 0; i < baseCount; i++) {
          base.add(new MergedRequirement(500L + i,
              randomView(random, "R." + (i + 1))));
        }
        Map<String, EvaluatedRequirement> baseByFingerprint = new LinkedHashMap<>();
        for (MergedRequirement item : base) {
          EvaluatedRequirement evaluated = evaluator.evaluateView(
              item.requirementId(), item.view(), snapshot, baseCtx);
          baseByFingerprint.put(AmendmentService.fingerprintOf(item.view()), evaluated);
        }

        List<MergedRequirement> merged = new ArrayList<>();
        long nextId = 600L;
        int inserts = random.nextInt(3);
        for (MergedRequirement item : base) {
          double pick = random.nextDouble();
          if (pick < 0.6) {
            merged.add(new MergedRequirement(nextId++, item.view()));
          } else if (pick < 0.9) {
            merged.add(new MergedRequirement(nextId++, mutate(random, item.view())));
          }
        }
        for (int i = 0; i < inserts; i++) {
          merged.add(new MergedRequirement(nextId++, randomView(random, "X." + i)));
        }
        boolean deadlineMoved = random.nextDouble() < 0.25;
        boolean dataBumped = random.nextDouble() < 0.25;
        EvaluationContext newCtx = EvaluationContext.of(
            deadlineMoved ? deadline.plusDays(5) : deadline,
            new BigDecimal("600000000.0000"), "1.0", dataBumped ? 2L : 1L);

        List<EvaluatedRequirement> selective = AmendmentService.selectiveReverify(
            evaluator, merged, baseByFingerprint, newCtx, baseCtx.contextHash(),
            snapshot);
        List<EvaluatedRequirement> full = new ArrayList<>();
        for (MergedRequirement item : merged) {
          full.add(evaluator.evaluateView(item.requirementId(), item.view(), snapshot,
              newCtx));
        }

        assertThat(selective.size()).isEqualTo(full.size());
        for (int i = 0; i < full.size(); i++) {
          assertThat(selective.get(i).verdict()).isEqualTo(full.get(i).verdict());
          assertThat(selective.get(i).reasonCode())
              .isEqualTo(full.get(i).reasonCode());
          assertThat(selective.get(i).traceJson()).isEqualTo(full.get(i).traceJson());
        }
        assertThat(BidDecisionEngine.decide(selective).recommendation())
            .isEqualTo(BidDecisionEngine.decide(full).recommendation());
        assertThat(ReadinessScore.of(selective))
            .isEqualByComparingTo(ReadinessScore.of(full));
      } catch (AssertionError e) {
        throw new AssertionError(
            "seed " + SEED + " iteration " + iter + " failed: " + e.getMessage(), e);
      }
    }
  }

  private static RequirementView mutate(Random random, RequirementView view) {
    double pick = random.nextDouble();
    if (pick < 0.5 && view.amountValue() != null) {
      BigDecimal changed = randomMoney(random);
      return new RequirementView(view.clauseRef(), view.mandatory(), view.metric(),
          view.operator(), changed.toPlainString(), changed, view.currency(),
          view.countValue(), view.lookbackYears(), view.domainTags(),
          view.certificateName(), view.certificateLevel(), view.conditionTree(),
          view.quoteVerified(), view.confidence());
    }
    if (pick < 0.75 && view.countValue() != null) {
      return new RequirementView(view.clauseRef(), view.mandatory(), view.metric(),
          view.operator(), view.amountText(), view.amountValue(), view.currency(),
          1 + random.nextInt(4), view.lookbackYears(), view.domainTags(),
          view.certificateName(), view.certificateLevel(), view.conditionTree(),
          view.quoteVerified(), view.confidence());
    }
    String flipped = "GTE".equals(view.operator()) ? "GT" : "GTE";
    return new RequirementView(view.clauseRef(), view.mandatory(), view.metric(),
        flipped, view.amountText(), view.amountValue(), view.currency(),
        view.countValue(), view.lookbackYears(), view.domainTags(),
        view.certificateName(), view.certificateLevel(), view.conditionTree(),
        view.quoteVerified(), view.confidence());
  }
}
