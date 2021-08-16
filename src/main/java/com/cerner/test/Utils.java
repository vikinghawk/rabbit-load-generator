package com.cerner.test;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Address;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultSocketConfigurator;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.RecoveryDelayHandler.ExponentialBackoffDelayHandler;
import com.rabbitmq.client.RecoveryListener;
import com.rabbitmq.client.TopologyRecoveryException;
import com.rabbitmq.client.impl.DefaultCredentialsProvider;
import com.rabbitmq.client.impl.ForgivingExceptionHandler;
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection;
import com.rabbitmq.client.impl.recovery.RecordedConsumer;
import com.rabbitmq.client.impl.recovery.RecordedExchange;
import com.rabbitmq.client.impl.recovery.RecoveredQueueNameSupplier;
import com.rabbitmq.client.impl.recovery.TopologyRecoveryFilter;
import com.rabbitmq.client.impl.recovery.TopologyRecoveryRetryLogic;
import java.io.IOException;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.core.env.Environment;

public class Utils {

  private static final Logger log = LoggerFactory.getLogger(Utils.class);

  private Utils() {}

  public static void initTasConfig(
      final RabbitProperties rabbitProps,
      final Environment environment,
      final RabbitLoadGeneratorProperties loadGenProps) {
    if ((rabbitProps.getHost() == null || rabbitProps.getHost().equalsIgnoreCase("localhost"))
        && loadGenProps.getRabbitServiceName() != null) {
      final String propPrefix = "vcap.services." + loadGenProps.getRabbitServiceName();
      final String rabbitClusterUris = environment.getProperty(propPrefix + ".credentials.uris");
      if (rabbitClusterUris == null) {
        return;
      }
      rabbitProps.setAddresses(rabbitClusterUris);
    }
  }

  public static AutorecoveringConnection createConnection(
      final RabbitProperties props,
      final RabbitLoadGeneratorProperties.ScenarioConfig customProps,
      final String name)
      throws Exception {
    log.info("Connecting to {} for connection={}", props.determineAddresses(), name);
    final ConnectionFactory cf = getConnectionFactory(props, customProps, name);
    final AutorecoveringConnection connection =
        (AutorecoveringConnection)
            cf.newConnection(
                Executors.newCachedThreadPool(),
                Address.parseAddresses(props.determineAddresses()),
                name);
    connection.addRecoveryListener(
        new RetryingRecoveryListener(
            connection, name, (RetryingExceptionHandler) cf.getExceptionHandler(), customProps));
    connection.addShutdownListener(
        cause -> {
          if (cause.isInitiatedByApplication()) {
            return;
          }
          if (cause.isHardError()) {
            log.error(
                "Error occurred on connection={} due to reason={}", name, cause.getReason(), cause);
          } else {
            log.error(
                "Error occurred on a channel for connection={} due to reason={}",
                name,
                cause.getReason(),
                cause);
          }
        });
    log.info("Connected to {} for connection={}", connection.getAddress(), name);
    return connection;
  }

  private static ConnectionFactory getConnectionFactory(
      final RabbitProperties props,
      final RabbitLoadGeneratorProperties.ScenarioConfig customProps,
      final String name)
      throws KeyManagementException, NoSuchAlgorithmException {
    final ConnectionFactory cf = new ConnectionFactory();
    if (props.getSsl().determineEnabled()) {
      cf.useSslProtocol();
      cf.enableHostnameVerification();
    }
    cf.setHost(props.determineHost());
    cf.setPort(props.determinePort());
    cf.setVirtualHost(props.determineVirtualHost());
    cf.setCredentialsProvider(
        new DefaultCredentialsProvider(props.determineUsername(), props.determinePassword()));
    // enable recovery
    cf.setAutomaticRecoveryEnabled(true);
    cf.setTopologyRecoveryEnabled(true);
    cf.setTopologyRecoveryFilter(
        new TopologyRecoveryFilter() {
          @Override
          public boolean filterExchange(final RecordedExchange recordedExchange) {
            // ignore our topic exchange. It is durable & doesn't need recovered
            // trying to recover one can cause issues if the channel that declared it has been
            // closed
            return !recordedExchange.getName().equalsIgnoreCase(customProps.getTopicExchange());
          }

          @Override
          public boolean filterConsumer(final RecordedConsumer recordedConsumer) {
            return !"amq.rabbitmq.reply-to".equals(recordedConsumer.getQueue());
          }
        });
    List<Long> delays = customProps.getRecoveryDelays();
    if (delays.isEmpty()) {
      delays = Arrays.asList(0L, 1000L, 1000L, 2000L, 3000L, 5000L, 8000L, 13000L);
    }
    cf.setRecoveryDelayHandler(new ExponentialBackoffDelayHandler(delays));
    cf.setTopologyRecoveryRetryHandler(
        TopologyRecoveryRetryLogic.RETRY_ON_QUEUE_NOT_FOUND_RETRY_HANDLER
            .retryAttempts(customProps.getMaxTopologyRecoveryRetries())
            .backoffPolicy(nbAttempts -> Thread.sleep(Math.min(nbAttempts * 200L, 5000L)))
            .build());
    if (customProps.isAutoDelete() && customProps.isRenameAutoDeleteQueuesOnRecovery()) {
      cf.setRecoveredQueueNameSupplier(RECOVERED_QUEUE_NAMER);
    }

    // configure timeouts
    cf.setRequestedHeartbeat(30);
    cf.setShutdownTimeout(5000);
    cf.setChannelRpcTimeout(300000);
    cf.setConnectionTimeout(60000);
    cf.useBlockingIo();
    cf.setSocketConfigurator(
        new DefaultSocketConfigurator() {
          @Override
          public void configure(final Socket socket) throws IOException {
            super.configure(socket);
            socket.setSoTimeout(15000);
          }
        });
    cf.setChannelShouldCheckRpcResponseType(true);
    cf.setExceptionHandler(new RetryingExceptionHandler(name));
    return cf;
  }

  private static final RecoveredQueueNameSupplier RECOVERED_QUEUE_NAMER =
      q -> {
        String postfix = "-recovered@" + System.currentTimeMillis();
        int idx = q.getName().indexOf("-recovered@");
        return (idx == -1 ? q.getName() : q.getName().substring(0, idx)) + postfix;
      };

  static class RetryingRecoveryListener implements RecoveryListener {

    private final AutorecoveringConnection connection;
    private final String name;
    private final RetryingExceptionHandler exceptionHandler;
    private final RabbitLoadGeneratorProperties.ScenarioConfig props;
    private int recoveryRetries = 0;
    private volatile long lastConnectionLoss;

    RetryingRecoveryListener(
        final AutorecoveringConnection connection,
        final String name,
        final RetryingExceptionHandler exceptionHandler,
        final RabbitLoadGeneratorProperties.ScenarioConfig props) {
      this.connection = connection;
      this.name = name;
      this.exceptionHandler = exceptionHandler;
      this.props = props;
    }

    @Override
    public void handleRecoveryStarted(final Recoverable recoverable) {
      lastConnectionLoss = System.nanoTime();
      log.info("Recovery started for connection={}", name);
    }

    @Override
    public void handleTopologyRecoveryStarted(final Recoverable recoverable) {
      log.info("Reconnected connection={}, starting topology recovery.", name);
    }

    @Override
    public void handleRecovery(final Recoverable recoverable) {
      final int errorCount = exceptionHandler.topologyRecoveryErrors.getAndSet(0);
      if (errorCount > 0) {
        if (recoveryRetries++ <= props.getMaxConnectionResetRecoveryRetries()) {
          log.warn(
              "Finished recovery for connection={} with {} recovery errors! Force closing connection in {} seconds to retry recovery again. Attempt {} of {}",
              name,
              errorCount,
              recoveryRetries,
              recoveryRetries,
              props.getMaxConnectionResetRecoveryRetries());
          forceClose(connection, recoveryRetries, lastConnectionLoss);
        } else {
          log.error(
              "WARNING! Finished recovery for connection={} with {} recovery errors! Reached max of {} recovery retry attempts. Continuing on with failed recovery.",
              name,
              errorCount,
              props.getMaxConnectionResetRecoveryRetries());
          recoveryRetries = 0;
        }
      } else {
        recoveryRetries = 0;
        log.info("Finished recovery for connection={} with no recovery errors", name);
      }
    }

    void forceClose(
        final AutorecoveringConnection connection,
        final long waitSeconds,
        final long expectedLastConnectionLoss) {
      final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
      exec.schedule(
          () -> {
            try {
              if (!connection.isOpen() || expectedLastConnectionLoss != lastConnectionLoss) {
                log.info("Not force closing connection={} as it was already closed", name);
              } else {
                connection
                    .getDelegate()
                    .close(
                        AMQP.CONNECTION_FORCED,
                        "Force closing connection to retry failed recovery",
                        false,
                        new Exception("Force closing connection to retry failed recovery"));
              }
            } catch (final Exception e) {
              log.error("Error force closing connection={} to retry failed recovery", name, e);
            } finally {
              exec.shutdown();
            }
          },
          waitSeconds,
          TimeUnit.SECONDS);
    }
  }

  static class RetryingExceptionHandler extends ForgivingExceptionHandler {

    final String name;
    final AtomicInteger topologyRecoveryErrors = new AtomicInteger();

    RetryingExceptionHandler(final String name) {
      this.name = name;
    }

    @Override
    public void handleTopologyRecoveryException(
        final Connection conn, final Channel ch, final TopologyRecoveryException exception) {
      topologyRecoveryErrors.incrementAndGet();
      super.handleTopologyRecoveryException(conn, ch, exception);
    }

    @Override
    protected void log(final String message, final Throwable e) {
      if (isSocketClosedOrConnectionReset(e)) {
        log.warn(
            "Exception on connection={}. {} (Exception message: {}", name, message, e.getMessage());
      } else {
        log.error("Exception on connection={}. {}", name, message, e);
      }
    }

    private static boolean isSocketClosedOrConnectionReset(final Throwable e) {
      return e instanceof IOException
          && ("Connection reset".equals(e.getMessage())
              || "Socket closed".equals(e.getMessage())
              || "Connection reset by peer".equals(e.getMessage()));
    }
  }
}
