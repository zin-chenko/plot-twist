package io.plottwist.moderation.config;

import io.plottwist.moderation.policy.application.RuleEngine;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.io.Resource;

@Configuration
public class PolicyConfiguration {
  @Bean
  RuleEngine ruleEngine(
      @Value("${moderation.rules:classpath:policy/basic-v1.tsv}") Resource source,
      @Value("${MODERATION_MEDIA_REVIEW_REQUIRED:false}") boolean reviewMedia)
      throws IOException {
    try (var input = source.getInputStream()) {
      byte[] bytes = input.readNBytes(1048577);
      if (bytes.length > 1048576) throw new IllegalArgumentException("Rules file too large");
      return new RuleEngine(bytes, reviewMedia);
    }
  }
}
