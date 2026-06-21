# Spring Cloud Rate Limiter

A distributed rate-limiting gateway built with **Spring Cloud Gateway** and **Redis**, implementing the token bucket algorithm to enforce consistent request limits across multiple service instances.

This project also documents a real concurrency bug found during development: a race condition in the token check-and-decrement logic, reproduced with a multithreaded test, and fixed using an atomic Redis Lua script.

## Features

- **Token bucket rate limiting** — each client is allocated a token bucket that refills at a configurable rate
- **Distributed by design** — token bucket state lives in Redis, so the rate limit holds correctly across multiple gateway instances, not just within a single process
- **Per-client identification** — clients are identified via `X-Forwarded-For` header (falls back to remote address), so each client gets an independent bucket
- **Custom Gateway Filter** — a `TokenBucketRateLimiterFilter` intercepts requests before they reach downstream services
- **Standard rate-limit headers** — responses include `X-RateLimit-Limit` and `X-RateLimit-Remaining`
- **429 on limit exceeded** — clients that exhaust their tokens receive an HTTP `429 Too Many Requests` response
- **Status endpoint** — `/gateway/rate-limit/status` exposes a client's current capacity and remaining tokens
- **Health endpoint** — `/gateway/health` for basic liveness checks

## Architecture

```
Client Request
      |
      v
GatewayConfig (routes /api/** requests)
      |
      v
TokenBucketRateLimiterFilter (intercepts, checks rate limit)
      |
      v
RateLimiterService (facade)
      |
      v
RedisTokenBucketService (token bucket logic, backed by Redis)
      |
      v
Redis (shared state across all gateway instances)
      |
      v
[Allowed] -> forwarded to backend service
[Blocked] -> 429 Too Many Requests
```

Each client's bucket state is stored in Redis under two keys:

```
rate_limiter:tokens:{clientId}        -> current token count
rate_limiter:last_refill:{clientId}   -> last refill timestamp (ms)
```

## Tech Stack

- **Java 17**
- **Spring Boot 3**
- **Spring Cloud Gateway** — reactive API gateway and routing
- **Redis** (via Jedis) — shared, distributed token bucket state
- **Gradle** — build tool
- **JUnit 5** — testing, including concurrency tests

## Getting Started

### Prerequisites

- Java 17+
- Redis running locally (default: `localhost:6379`)
- A backend service to route to (a simple mock server is included for testing)

### Configuration

Rate limiter settings are externalized in `application.properties`:

```properties
rate-limiter.capacity=10
rate-limiter.refill-rate=5
rate-limiter.api-server-url=http://localhost:8081
rate-limiter.timeout=5000

spring.redis.host=localhost
spring.redis.port=6379
```

### Running

```bash
# Start Redis (if not already running)
redis-server

# Start the mock backend service (for local testing)
python mock_server_simple.py

# Run the gateway
./gradlew bootRun
```

The gateway will be available at `http://localhost:8080`. Requests to `/api/**` are routed through the rate limiter to the configured backend.

### Quick Test

A test script is included to manually verify rate limiting behavior:

```bash
./quick-test.sh
```

This sends a burst of requests against a configured capacity and reports how many were allowed versus blocked with `429`.

## Finding and Fixing a Race Condition

The initial implementation of the token bucket check looked like this:

```java
public boolean isAllowed(String clientId) {
    refillTokens(clientId, jedis);
    long currentTokens = /* read token count from Redis */;

    if (currentTokens <= 0) {
        return false;
    }

    jedis.decr(tokenKey);
    return true;
}
```

This reads the current token count and decrements it as **two separate Redis calls**. Under concurrent requests, multiple threads can read the same token count before any of them decrement — allowing more requests through than the bucket's actual capacity.

### Proving the bug

A concurrency test (`ConcurrentRateLimiterTest`) was written to simulate this directly:

- A bucket is seeded with a known capacity (5 tokens)
- 20 threads are released **simultaneously** using a `CountDownLatch`, all calling `isAllowed()` at once
- The test asserts that the number of allowed requests never exceeds the bucket's capacity

Running this against the original implementation reproduced the race condition: in one run, **7 requests were allowed against a capacity of 5** — confirmed via the test failing with `AssertionFailedError: RACE CONDITION DETECTED!`. Because race conditions are timing-dependent, the test didn't fail on every run, which itself confirmed the bug was a genuine concurrency issue rather than a logic error — a deterministic bug would have failed consistently.

### The fix

The check-and-decrement was moved into a single **atomic Redis Lua script**, executed via `EVAL`. Since Redis executes Lua scripts atomically, no other client can interleave a read between the check and the decrement, eliminating the race window entirely.

After the fix, the same concurrency test was run repeatedly and consistently passed — token counts never exceeded the configured capacity, even under concurrent load from 20 simultaneous threads.

This was the core engineering lesson of the project: a rate limiter that passes simple sequential tests can still be fundamentally broken under real, concurrent traffic, and verifying correctness requires testing for concurrency explicitly, not just functional correctness.

## Project Structure

```
src/main/java/com/example/RateLimiting/
├── config/
│   ├── GatewayConfig.java          # Route definitions and filter wiring
│   ├── RateLimiterProperties.java  # Externalized rate limiter config
│   └── RedisProperties.java        # Redis connection pool setup
├── controller/
│   └── StatusController.java       # Health and rate-limit status endpoints
├── filter/
│   └── TokenBucketRateLimiterFilter.java  # Gateway filter, intercepts requests
├── service/
│   ├── RateLimiterService.java         # Facade over the Redis-backed logic
│   └── RedisTokenBucketService.java    # Token bucket algorithm + Redis access
└── RateLimitingApplication.java

src/test/java/com/example/RateLimiting/service/
└── ConcurrentRateLimiterTest.java  # Concurrency test that reproduces the race condition
```

## Future Improvements

- Sliding window algorithm as an alternative to token bucket, with a comparison of trade-offs
- Per-route rate limit configuration (different limits for different API paths)
- Metrics/observability (e.g. Micrometer) for monitoring rate-limit hits over time
- Horizontal scaling test — running multiple gateway instances behind a load balancer to verify Redis-backed limits hold consistently across instances

## License

This project is for educational and portfolio purposes.
