# Spring-cloud-rate-limiter
Distributed rate-limiting gateway built with Spring Cloud Gateway and Redis — implements the token bucket algorithm, with a documented race condition found via concurrent testing and fixed using an atomic Redis Lua script.
