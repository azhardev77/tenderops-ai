package com.tenderops.config;

import com.tenderops.agent.AgentLimits;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TenderopsProperties.class)
public class AppConfig {

  @Bean
  public Clock systemClock() {
    return Clock.systemDefaultZone();
  }

  @Bean
  public AgentLimits agentLimits() {
    return AgentLimits.defaults();
  }
}
