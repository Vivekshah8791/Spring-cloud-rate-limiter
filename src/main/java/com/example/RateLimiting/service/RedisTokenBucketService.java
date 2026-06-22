package com.example.RateLimiting.service;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import com.example.RateLimiting.config.RateLimiterProperties;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Jedis;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RedisTokenBucketService {

    private final JedisPool jedisPool;

    private final RateLimiterProperties properties;

    private final String TOKENS_KEY_PREFIX = "rate_limiter:tokens:";
    private static final String LAST_REFILL_KEY_PREFIX = "rate_limiter:last_refill:";

public boolean isAllowed(String clientId) {

    String tokenKey = TOKENS_KEY_PREFIX + clientId;

    try (Jedis jedis = jedisPool.getResource()) {

        refillTokens(clientId, jedis);

        String luaScript = """
            local tokens = tonumber(redis.call('GET', KEYS[1]))

            if not tokens or tokens <= 0 then
                return 0
            end

            redis.call('DECR', KEYS[1])

            return 1
            """;

        Object result = jedis.eval(
                luaScript,
                1,
                tokenKey
        );

        return Long.valueOf(1).equals(result);
    }
}

   public long getCapacity(String clientId) {
    
        return properties.getCapacity();
    }

    public long getAvailableTokens(String clientId) {

        String tokenKey = TOKENS_KEY_PREFIX + clientId;

        try(Jedis jedis = jedisPool.getResource()) {

            refillTokens(clientId, jedis);
            String tokenStr = jedis.get(tokenKey);
            return tokenStr != null ? Long.parseLong(tokenStr) : properties.getCapacity();

        }
    }
    
    public void refillTokens(String clientId, Jedis jedis) {

        String tokensKey = TOKENS_KEY_PREFIX + clientId;
        String lastRefillKey = LAST_REFILL_KEY_PREFIX + clientId;

        long now = System.currentTimeMillis();
        String lastRefillStr = jedis.get(lastRefillKey);
        if(lastRefillStr == null){

            jedis.set(tokensKey, String.valueOf(properties.getCapacity()));
            jedis.set(lastRefillKey, String.valueOf(now));
            return;
        }

        long lastRefillTime = Long.parseLong(lastRefillStr);
        long elapsedTime = now - lastRefillTime;

        if(elapsedTime <= 0) {
            return;
        }

        long tokensToAdd = (elapsedTime * properties.getRefillRate()) / 1000;
        if(tokensToAdd <= 0) {
            return;
        }

        String tokenStr = jedis.get(tokensKey);

        long currentTokens = tokenStr !=null ? Long.parseLong(tokenStr) : properties.getCapacity();
        long newTokens = Math.min(properties.getCapacity(), currentTokens + tokensToAdd);

        jedis.set(tokensKey, String.valueOf(newTokens));
        jedis.set(lastRefillKey, String.valueOf(now));

    }

}

