package com.tenderops.web;

import com.tenderops.audit.AuditLog;
import com.tenderops.audit.AuditLogRepository;
import com.tenderops.security.CurrentUser;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Insert-only audit log reads (R11): company-scoped, newest first. */
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditController {

  public record AuditDto(String action, String entityType, Long entityId, String before,
      String after, Long userId, OffsetDateTime createdAt) {
  }

  public record AuditListResponse(List<AuditDto> items, int page, int size, long total) {
  }

  private final AuditLogRepository audits;

  public AuditController(AuditLogRepository audits) {
    this.audits = audits;
  }

  @GetMapping
  public AuditListResponse list(@RequestParam(required = false) String entityType,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    CurrentUser current = CurrentUser.require();
    int safeSize = Math.min(Math.max(size, 1), 100);
    int safePage = Math.max(page, 0);
    List<AuditLog> rows;
    long total;
    if (entityType == null || entityType.isBlank()) {
      rows = audits.findByCompanyIdOrderByIdDesc(current.companyId(),
          PageRequest.of(safePage, safeSize));
      total = audits.countByCompanyId(current.companyId());
    } else {
      rows = audits.findByCompanyIdAndEntityTypeOrderByIdDesc(current.companyId(),
          entityType.trim(), PageRequest.of(safePage, safeSize));
      total = audits.countByCompanyIdAndEntityType(current.companyId(), entityType.trim());
    }
    List<AuditDto> items = new ArrayList<>();
    for (AuditLog row : rows) {
      items.add(new AuditDto(row.getAction(), row.getEntityType(), row.getEntityId(),
          row.getBeforeJson(), row.getAfterJson(), row.getUserId(), row.getCreatedAt()));
    }
    return new AuditListResponse(items, safePage, safeSize, total);
  }
}
