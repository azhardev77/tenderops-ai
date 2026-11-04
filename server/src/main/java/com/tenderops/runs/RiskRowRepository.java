package com.tenderops.runs;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskRowRepository extends JpaRepository<RiskRow, Long> {

  List<RiskRow> findByRunIdOrderById(Long runId);
}
