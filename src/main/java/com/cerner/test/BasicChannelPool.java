package com.cerner.test;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasicChannelPool {

  private static final Logger log = LoggerFactory.getLogger(BasicChannelPool.class);

  private final Connection connection;
  private final int maxSize;
  private final LinkedBlockingQueue<Channel> pool = new LinkedBlockingQueue<>();
  private final AtomicInteger size = new AtomicInteger();
  private final boolean usePubConfirms;

  public BasicChannelPool(final Connection connection, final int maxSize) {
    this(connection, maxSize, false);
  }

  public BasicChannelPool(
      final Connection connection, final int maxSize, final boolean usePubConfirms) {
    this.connection = connection;
    this.maxSize = maxSize;
    this.usePubConfirms = usePubConfirms;
  }

  public Channel checkout() throws IOException, InterruptedException {
    Channel channel = pool.poll();
    if (channel == null) {
      if (size.incrementAndGet() <= maxSize) {
        channel = connection.createChannel();
        if (usePubConfirms) {
          channel.confirmSelect();
          channel.addConfirmListener(
              (tag, multiple) -> log.debug("Got ack for deliveryTag={}", tag),
              (tag, multiple) -> log.error("Got nack for deliveryTag={}", tag));
        }
      } else {
        channel = pool.poll(10, TimeUnit.SECONDS);
      }
    }
    return channel;
  }

  public void checkin(final Channel channel) {
    if (channel != null) {
      pool.offer(channel);
    }
  }
}
