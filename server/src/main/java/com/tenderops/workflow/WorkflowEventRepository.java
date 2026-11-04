package com.tenderops.workflow;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowEventRepository extends JpaRepository<WorkflowEvent, Long> {

  List<WorkflowEvent> findByTenderVersionIdOrderBySeq(Long tenderVersionId);

  List<WorkflowEvent> findByTenderVersionIdAndSeqGreaterThanOrderBySeq(Long tenderVersionId,
      Long seq);

  List<WorkflowEvent> findByCompanyIdAndSeqGreaterThanOrderBySeq(Long companyId, Long seq);
}
