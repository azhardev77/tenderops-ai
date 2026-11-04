package com.tenderops.web;

import com.tenderops.runs.ComplianceResult;
import com.tenderops.runs.ComplianceResultRepository;
import com.tenderops.runs.ComplianceRun;
import com.tenderops.runs.ComplianceRunRepository;
import com.tenderops.runs.RiskRow;
import com.tenderops.runs.RiskRowRepository;
import com.tenderops.security.CurrentUser;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.workflow.WorkflowJob;
import com.tenderops.workflow.WorkflowJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard aggregates (section 9): real counts over the caller's company. "Latest"
 * means the latest run of each tender's highest version; runs that never finished
 * are excluded from performance and time-saved figures.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

  public record DeadlineDto(long tenderId, String reference, OffsetDateTime deadline) {
  }

  public record PerformanceDto(Long avgMsPerPage, Long lastRunMs) {
  }

  public record TimeSavedDto(String percent, String manualMinutes, String assistedMinutes,
      String aiMode, boolean representative) {
  }

  public record StatsResponse(long totalTenders, long processingTenders, long completedTenders,
      Map<String, Long> decisionBreakdown, long pendingReviews,
      List<DeadlineDto> upcomingDeadlines, long criticalIssues, PerformanceDto performance,
      TimeSavedDto timeSaved) {
  }

  private final TenderRepository tenders;
  private final TenderVersionRepository versions;
  private final ComplianceRunRepository runs;
  private final ComplianceResultRepository results;
  private final RiskRowRepository risks;
  private final WorkflowJobRepository jobs;
  private final Clock clock;

  public DashboardController(TenderRepository tenders, TenderVersionRepository versions,
      ComplianceRunRepository runs, ComplianceResultRepository results,
      RiskRowRepository risks, WorkflowJobRepository jobs, Clock clock) {
    this.tenders = tenders;
    this.versions = versions;
    this.runs = runs;
    this.results = results;
    this.risks = risks;
    this.jobs = jobs;
    this.clock = clock;
  }

  @GetMapping("/stats")
  public StatsResponse stats() {
    CurrentUser current = CurrentUser.require();
    long companyId = current.companyId();
    List<Tender> companyTenders = tenders.findByCompanyIdOrderByIdDesc(companyId);
    Map<Long, TenderVersion> versionById = new LinkedHashMap<>();
    Map<Long, List<TenderVersion>> versionsByTender = new LinkedHashMap<>();
    for (Tender tender : companyTenders) {
      List<TenderVersion> tenderVersions =
          versions.findByTenderIdOrderByVersionNumber(tender.getId());
      versionsByTender.put(tender.getId(), tenderVersions);
      for (TenderVersion version : tenderVersions) {
        versionById.put(version.getId(), version);
      }
    }

    Map<Long, ComplianceRun> latestByTender = new LinkedHashMap<>();
    List<ComplianceRun> finished = new ArrayList<>();
    for (Tender tender : companyTenders) {
      List<TenderVersion> tenderVersions = versionsByTender.get(tender.getId());
      if (tenderVersions == null || tenderVersions.isEmpty()) {
        continue;
      }
      TenderVersion latest = tenderVersions.get(tenderVersions.size() - 1);
      List<ComplianceRun> versionRuns =
          runs.findByTenderVersionIdOrderByIdDesc(latest.getId());
      if (!versionRuns.isEmpty()) {
        latestByTender.put(tender.getId(), versionRuns.get(0));
      }
      for (TenderVersion version : tenderVersions) {
        for (ComplianceRun run : runs.findByTenderVersionIdOrderByIdDesc(version.getId())) {
          if (run.getFinishedAt() != null) {
            finished.add(run);
          }
        }
      }
    }

    Map<String, Long> breakdown = new LinkedHashMap<>();
    breakdown.put("BID", 0L);
    breakdown.put("CONDITIONAL_BID", 0L);
    breakdown.put("NO_BID", 0L);
    breakdown.put("INSUFFICIENT_DATA", 0L);
    long pendingReviews = 0;
    long criticalIssues = 0;
    for (ComplianceRun run : latestByTender.values()) {
      breakdown.computeIfPresent(run.getDecision(), (key, count) -> count + 1);
      for (ComplianceResult row : results.findByRunIdOrderById(run.getId())) {
        if ("UNKNOWN".equals(row.getVerdict()) || "NEEDS_REVIEW".equals(row.getVerdict())) {
          pendingReviews++;
        }
      }
      for (RiskRow risk : risks.findByRunIdOrderById(run.getId())) {
        if ("CRITICAL".equals(risk.getSeverity())) {
          criticalIssues++;
        }
      }
    }

    long processingTenders = 0;
    for (Tender tender : companyTenders) {
      List<TenderVersion> tenderVersions = versionsByTender.get(tender.getId());
      boolean active = false;
      for (TenderVersion version : tenderVersions) {
        if (!jobs.findByTenderVersionIdAndStatusIn(version.getId(),
            List.of("QUEUED", "RUNNING")).isEmpty()) {
          active = true;
          break;
        }
      }
      if (active) {
        processingTenders++;
      }
    }
    long completedTenders = finished.stream()
        .map(run -> versionById.get(run.getTenderVersionId()))
        .filter(version -> version != null).map(TenderVersion::getTenderId).distinct()
        .count();

    OffsetDateTime now = OffsetDateTime.now(clock);
    List<DeadlineDto> upcoming = new ArrayList<>();
    for (TenderVersion version : versionById.values()) {
      if (version.getSubmissionDeadline() != null
          && version.getSubmissionDeadline().isAfter(now)) {
        upcoming.add(new DeadlineDto(version.getTenderId(), version.getReference(),
            version.getSubmissionDeadline()));
      }
    }
    upcoming.sort(Comparator.comparing(DeadlineDto::deadline));
    if (upcoming.size() > 5) {
      upcoming = upcoming.subList(0, 5);
    }

    Long avgMsPerPage = null;
    Long lastRunMs = null;
    if (!finished.isEmpty()) {
      long totalMs = 0;
      long totalPages = 0;
      ComplianceRun latest = null;
      for (ComplianceRun run : finished) {
        long ms = Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis();
        if (ms < 0) {
          ms = 0;
        }
        if (latest == null || run.getFinishedAt().isAfter(latest.getFinishedAt())) {
          latest = run;
          lastRunMs = ms;
        }
        TenderVersion version = versionById.get(run.getTenderVersionId());
        if (version != null && version.getPageCount() > 0) {
          totalMs += ms;
          totalPages += version.getPageCount();
        }
      }
      if (totalPages > 0) {
        avgMsPerPage = totalMs / totalPages;
      }
    }

    TimeSavedDto timeSaved = null;
    finished.sort(Comparator.comparing(ComplianceRun::getFinishedAt).reversed());
    for (ComplianceRun run : finished) {
      if (run.getTimeSavedPercent() != null) {
        boolean live = "LIVE".equals(run.getMuseMode()) && "LIVE".equals(run.getSamMode());
        timeSaved = new TimeSavedDto(run.getTimeSavedPercent().toPlainString(),
            run.getManualMinutes() == null ? null : run.getManualMinutes().toPlainString(),
            run.getAssistedMinutes() == null ? null
                : run.getAssistedMinutes().toPlainString(),
            live ? "LIVE" : "FALLBACK", live);
        break;
      }
    }

    return new StatsResponse(companyTenders.size(), processingTenders, completedTenders,
        breakdown, pendingReviews, upcoming, criticalIssues,
        new PerformanceDto(avgMsPerPage, lastRunMs), timeSaved);
  }
}
