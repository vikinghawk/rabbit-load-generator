package com.cerner.test;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BasicChannelPool {

  private final Connection connection;
  private final int maxSize;
  private final LinkedBlockingQueue<Channel> pool = new LinkedBlockingQueue<>();
  private final AtomicInteger size = new AtomicInteger();

  public BasicChannelPool(final Connection connection, final int maxSize) {
    this.connection = connection;
    this.maxSize = maxSize;
  }

  public Channel checkout() throws IOException, InterruptedException {
    Channel channel = pool.poll();
    if (channel == null) {
      if (size.incrementAndGet() <= maxSize) {
        channel = connection.createChannel();
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
