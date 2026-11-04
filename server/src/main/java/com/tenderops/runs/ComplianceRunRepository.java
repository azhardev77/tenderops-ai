package com.tenderops.runs;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplianceRunRepository extends JpaRepository<ComplianceRun, Long> {

  List<ComplianceRun> findByTenderVersionIdOrderByIdDesc(Long tenderVersionId);
}
