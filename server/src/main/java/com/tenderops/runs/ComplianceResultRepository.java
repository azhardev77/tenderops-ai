package com.tenderops.runs;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplianceResultRepository extends JpaRepository<ComplianceResult, Long> {

  List<ComplianceResult> findByRunIdOrderById(Long runId);
}
