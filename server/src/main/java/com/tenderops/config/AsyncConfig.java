package com.tenderops.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

  @Bean(name = "workflowExecutor")
  public Executor workflowExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
