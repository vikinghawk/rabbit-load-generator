package com.cerner.test;

import com.cerner.test.RabbitLoadGeneratorProperties.ScenarioConfig;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

public class RabbitLoadGenerator implements EnvironmentAware {

  private static final Logger log = LoggerFactory.getLogger(RabbitLoadGenerator.class);

  private final RabbitProperties rabbitProps;
  private final RabbitLoadGeneratorProperties props;
  private final Map<AutorecoveringConnection, Set<String>> connections = new LinkedHashMap<>();
  private Environment environment;
  private List<ScheduledExecutorService> execs = new ArrayList<>();

  public RabbitLoadGenerator(
      final RabbitProperties rabbitProps, final RabbitLoadGeneratorProperties testProps) {
    this.rabbitProps = rabbitProps;
    this.props = testProps;
  }

  @Override
  public void setEnvironment(final Environment environment) {
    this.environment = environment;
  }

  @PostConstruct
  public void start() throws Exception {
    Utils.initTasConfig(rabbitProps, environment, props);
    log.info("Using the following configurations: {}", props);
    for (final ScenarioConfig scenario : props.getScenarios()) {
      final long now = System.currentTimeMillis();
      final String exchange =
          scenario.getTopicExchange() + (scenario.isUniqueExchange() ? "." + now : "");
      final String queuePrefix = scenario.getQueueNamePrefix() + now + "-";
      final String routingKeyPrefix = scenario.getRoutingKeyPrefix() + now + ".";
      boolean exchangeDeclared = false;
      int totalQueueCount = 0;
      int totalBindingCount = 0;
      for (int i = 1; i <= scenario.getConnections(); i++) {
        final String connectionName = "RabbitLoadGenerator-" + now + "-" + i;
        final AutorecoveringConnection connection =
            Utils.createConnection(rabbitProps, scenario, connectionName);
        final Set<String> queuesToDelete = new LinkedHashSet<>();
        connections.put(connection, queuesToDelete);

        for (int j = 1; j <= scenario.getChannelsPerConnection(); j++) {
          final Channel channel = connection.createChannel();
          channel.addShutdownListener(
              cause -> {
                if (!cause.isHardError()
                    && !cause.isInitiatedByApplication()
                    && connection.isOpen()) {
                  log.error(
                      "Error occurred on consumer channel={} for connection={}. Reason={}",
                      channel.getChannelNumber(),
                      connectionName,
                      cause.getReason(),
                      cause);
                }
              });
          channel.basicQos(scenario.isAutoDelete() ? 20 : 5);
          if (!exchangeDeclared) {
            channel.exchangeDeclare(exchange, BuiltinExchangeType.TOPIC, true);
            exchangeDeclared = true;
          }
          for (int k = 1; k <= scenario.getQueuesPerChannel(); k++) {
            final String queueName = queuePrefix + i + "-" + j + "-" + k;
            final Map<String, Object> args = new LinkedHashMap<>(0);
            if (scenario.isQuorum()) {
              args.put("x-queue-type", "quorum");
            }
            channel.queueDeclare(
                queueName, scenario.isDurable(), false, scenario.isAutoDelete(), args);
            totalQueueCount++;
            if (scenario.isDeleteQueuesOnShutdown() && !scenario.isAutoDelete()) {
              queuesToDelete.add(queueName);
            }

            for (int l = 1; l <= scenario.getBindingsPerQueue(); l++) {
              channel.queueBind(queueName, exchange, routingKeyPrefix + ++totalBindingCount);
            }
            for (int l = 1; l <= scenario.getConsumersPerQueue(); l++) {
              channel.basicConsume(
                  queueName,
                  new DefaultConsumer(channel) {
                    @Override
                    public void handleDelivery(
                        final String consumerTag,
                        final Envelope envelope,
                        final BasicProperties properties,
                        final byte[] body)
                        throws IOException {
                      try {
                        log.debug(
                            "Got message={} from connection={}, channel={}",
                            new String(body, StandardCharsets.UTF_8),
                            connectionName,
                            getChannel().getChannelNumber());
                        if (scenario.getProcessWaitMillis() > 0) {
                          try {
                            Thread.sleep(scenario.getProcessWaitMillis());
                          } catch (InterruptedException e) {
                            /* no op */
                          }
                        }
                      } finally {
                        getChannel().basicAck(envelope.getDeliveryTag(), false);
                      }
                    }
                  });
            }
          }
        }
      }
      log.info(
          "Finished creating {} connections, {} queues, and {} bindings",
          connections.size(),
          totalQueueCount,
          totalBindingCount);
      // publish some messages to the queues just to generate load on the cluster
      if (scenario.getPublishInterval() > 0) {
        final AutorecoveringConnection pubConnection =
            Utils.createConnection(
                rabbitProps, scenario, "RabbitLoadGenerator-" + now + "-Publisher");
        connections.put(pubConnection, Collections.emptySet());
        final ScheduledExecutorService exec =
            Executors.newScheduledThreadPool(scenario.getPublishThreads());
        execs.add(exec);
        final LinkedBlockingQueue<Channel> channelPool =
            new LinkedBlockingQueue<>(scenario.getPublishThreads());
        for (int i = 0; i < scenario.getPublishThreads(); i++) {
          channelPool.add(pubConnection.createChannel());
        }
        for (int i = 1; i <= totalBindingCount; i++) {
          final String routingKey = routingKeyPrefix + i;
          final byte[] bytes = new byte[scenario.getPublishMsgSizeBytes()];
          ThreadLocalRandom.current().nextBytes(bytes);
          exec.scheduleAtFixedRate(
              () -> {
                try {
                  final Channel channel = channelPool.take();
                  try {
                    final BasicProperties.Builder basicProps = new BasicProperties.Builder();
                    basicProps.messageId(UUID.randomUUID().toString());
                    if (scenario.isPublishPersistent()) {
                      basicProps.deliveryMode(2);
                    }
                    channel.basicPublish(exchange, routingKey, basicProps.build(), bytes);
                  } finally {
                    channelPool.put(channel);
                  }
                } catch (final Exception e) {
                  if (pubConnection.isOpen()) {
                    log.error("Error publishing on routingKey={}", routingKey, e);
                  } else {
                    log.debug("Error publishing on closed connection", e);
                  }
                }
              },
              ThreadLocalRandom.current().nextLong(scenario.getPublishInterval()),
              scenario.getPublishInterval(),
              TimeUnit.MILLISECONDS);
        }
        log.info(
            "Publishing messages for {} routingKeys every {} ms",
            totalBindingCount,
            scenario.getPublishInterval());
      }
    }
  }

  @PreDestroy
  public void stop() {
    log.info("Shutting down...");
    execs.forEach(ExecutorService::shutdownNow);
    connections.forEach(
        (c, queues) -> {
          // delete queues
          if (!queues.isEmpty()) {
            try (Channel channel = c.createChannel()) {
              queues.forEach(
                  q -> {
                    try {
                      channel.queueDelete(q);
                    } catch (Exception e) {
                      log.error("Error deleting queue={}", q, e);
                    }
                  });
            } catch (Exception e) {
              log.error("Error deleting queues", e);
            }
          }
          // close connection
          try {
            c.close();
          } catch (IOException e) {
            log.error("Error closing connection", e);
          }
        });
    connections.clear();
    log.info("Closed {} connections", connections.size());
  }
}
