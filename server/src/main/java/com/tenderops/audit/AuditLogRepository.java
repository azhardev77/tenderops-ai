package com.tenderops.audit;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Insert-only audit store (R11, TC-SEC-06). Only save + reads are exposed:
 * no update/delete method exists on this interface.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {
  AuditLog save(AuditLog log);

  List<AuditLog> findByCompanyIdOrderByIdDesc(Long companyId, Pageable pageable);

  List<AuditLog> findByCompanyIdAndEntityTypeOrderByIdDesc(Long companyId, String entityType,
      Pageable pageable);

  long countByCompanyId(Long companyId);

  long countByCompanyIdAndEntityType(Long companyId, String entityType);
}
