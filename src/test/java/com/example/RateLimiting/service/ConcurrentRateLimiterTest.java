package com.example.RateLimiting.service;

import com.example.RateLimiting.config.RateLimiterProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrentRateLimiterTest {

    @Test
    public void testConcurrentRequestsDoNotExceedCapacity() throws Exception {

        JedisPool jedisPool = new JedisPool("localhost", 6379);

        try {

            RateLimiterProperties properties = new RateLimiterProperties();
            properties.setCapacity(5);
            properties.setRefillRate(0); 

            RedisTokenBucketService service =
                    new RedisTokenBucketService(jedisPool, properties);

            String clientId = "race-test-client";

            int numberOfThreads = 20;
            int expectedCapacity = 5;

            String tokenKey = "rate_limiter:tokens:" + clientId;
            String refillKey = "rate_limiter:last_refill:" + clientId;

            try (Jedis jedis = jedisPool.getResource()) {

                jedis.del(tokenKey);
                jedis.del(refillKey);

                jedis.set(tokenKey, String.valueOf(expectedCapacity));
                jedis.set(
                        refillKey,
                        String.valueOf(System.currentTimeMillis())
                );
            }

            AtomicInteger allowedCount = new AtomicInteger(0);

            ExecutorService executor =
                    Executors.newFixedThreadPool(numberOfThreads);

            CountDownLatch readyLatch =
                    new CountDownLatch(numberOfThreads);

            CountDownLatch startLatch =
                    new CountDownLatch(1);

            CountDownLatch finishLatch =
                    new CountDownLatch(numberOfThreads);

            for (int i = 0; i < numberOfThreads; i++) {

                executor.submit(() -> {

                    readyLatch.countDown();

                    try {

                        startLatch.await();

                        boolean allowed =
                                service.isAllowed(clientId);

                        if (allowed) {
                            allowedCount.incrementAndGet();
                        }

                    } catch (InterruptedException e) {

                        Thread.currentThread().interrupt();

                    } finally {

                        finishLatch.countDown();
                    }
                });
            }

            readyLatch.await();

            long startTime = System.currentTimeMillis();

            startLatch.countDown();

            finishLatch.await();

            long endTime = System.currentTimeMillis();

            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            System.out.println();
            System.out.println("=================================");
            System.out.println("Concurrent Test Results");
            System.out.println("=================================");
            System.out.println("Threads           : " + numberOfThreads);
            System.out.println("Bucket Capacity   : " + expectedCapacity);
            System.out.println("Allowed Requests  : " + allowedCount.get());
            System.out.println("Execution Time    : " + (endTime - startTime) + " ms");
            System.out.println("=================================");
            System.out.println();

            Assertions.assertTrue(
                    allowedCount.get() <= expectedCapacity,
                    "RACE CONDITION DETECTED! Allowed Requests = "
                            + allowedCount.get()
                            + ", Capacity = "
                            + expectedCapacity
            );

        } finally {

            jedisPool.close();
        }
    }
}