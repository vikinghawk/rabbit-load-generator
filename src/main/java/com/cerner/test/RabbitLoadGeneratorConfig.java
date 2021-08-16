package com.cerner.test;

import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({RabbitProperties.class, RabbitLoadGeneratorProperties.class})
public class RabbitLoadGeneratorConfig {

  @Bean
  @ConditionalOnProperty(
      value = "rabbit-load-generator.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public RabbitLoadGenerator rabbitLoadGenerator(
      final RabbitProperties rabbitProperties, final RabbitLoadGeneratorProperties testProps) {
    return new RabbitLoadGenerator(rabbitProperties, testProps);
  }
}
