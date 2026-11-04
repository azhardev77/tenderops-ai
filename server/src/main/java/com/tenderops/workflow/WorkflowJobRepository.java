package com.tenderops.workflow;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowJobRepository extends JpaRepository<WorkflowJob, Long> {

  List<WorkflowJob> findByTenderVersionIdAndStatusIn(Long tenderVersionId,
      List<String> statuses);

  List<WorkflowJob> findByTenderVersionIdOrderByIdDesc(Long tenderVersionId);

  List<WorkflowJob> findByStatus(String status);
}
