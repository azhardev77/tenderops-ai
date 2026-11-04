package com.tenderops.runs;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BidDecisionRowRepository extends JpaRepository<BidDecisionRow, Long> {

  Optional<BidDecisionRow> findByRunId(Long runId);
}
