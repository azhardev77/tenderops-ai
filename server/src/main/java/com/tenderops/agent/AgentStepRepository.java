package com.tenderops.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentStepRepository extends JpaRepository<AgentStep, Long> {
  List<AgentStep> findByAgentRunIdOrderByStepNo(Long agentRunId);
}
