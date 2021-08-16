package com.cerner.test;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@NoArgsConstructor
@ConfigurationProperties("rabbit-load-generator")
public class RabbitLoadGeneratorProperties {

  protected String rabbitServiceName;
  protected final List<ScenarioConfig> scenarios = new ArrayList<>();

  @Data
  @NoArgsConstructor
  public static class ScenarioConfig {
    protected String topicExchange = "rabbit.load.generator.topic";
    protected String queueNamePrefix = "rabbit-load-generator-";
    protected String routingKeyPrefix = "RabbitLoadGenerator.";

    protected int connections = 25;
    protected int channelsPerConnection = 10;
    protected int queuesPerChannel = 10;
    protected int consumersPerQueue = 1;
    protected int bindingsPerQueue = 1;

    protected boolean autoDelete = true;
    protected boolean isDurable;
    protected boolean isQuorum;

    protected boolean deleteQueuesOnShutdown = true;
    protected boolean renameAutoDeleteQueuesOnRecovery = true;

    protected long publishInterval = 10000;
    protected boolean publishPersistent;
    protected int publishThreads = 5;

    protected final List<Long> recoveryDelays = new ArrayList<>();
    protected int maxTopologyRecoveryRetries = 100;
    protected int maxConnectionResetRecoveryRetries = 0;

    public boolean isAutoDelete() {
      return autoDelete && !isDurable && !isQuorum;
    }

    public boolean isDurable() {
      return isDurable || isQuorum;
    }
  }
}
