package com.ledgercore.idempotency;

import com.ledgercore.config.Redis;
import com.ledgercore.http.Json;
import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

// the fast path: every call here fails soft, the database stays the source of truth
public class IdempotencyCache {

    private static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final Duration RESPONSE_TTL = Duration.ofHours(24);

    private final Redis redis;

    public IdempotencyCache(Redis redis) {
        this.redis = redis;
    }

    // empty means this request holds the lock now, otherwise the hash of the request that holds it
    public Optional<String> lock(String key, String requestHash) {
        try {
            return redis.call(commands -> {
                String result = commands.set(lockKey(key), requestHash, SetArgs.Builder.nx().ex(LOCK_TTL));
                if ("OK".equals(result)) {
                    return Optional.<String>empty();
                }
                return Optional.ofNullable(commands.get(lockKey(key)));
            });
        } catch (RedisException e) {
            return Optional.empty();
        }
    }

    public void unlock(String key) {
        try {
            redis.call(commands -> commands.del(lockKey(key)));
        } catch (RedisException e) {
            // the lock expires by itself
        }
    }

    public Optional<IdempotencyRecord> find(String key) {
        try {
            String value = redis.call(commands -> commands.get(responseKey(key)));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(Json.read(value.getBytes(StandardCharsets.UTF_8), IdempotencyRecord.class));
        } catch (RedisException e) {
            return Optional.empty();
        }
    }

    public void save(String key, IdempotencyRecord record) {
        try {
            redis.call(commands -> commands.set(responseKey(key), Json.write(record), SetArgs.Builder.ex(RESPONSE_TTL)));
        } catch (RedisException e) {
            // the database still has the response
        }
    }

    private String lockKey(String key) {
        return "idempotency:lock:" + key;
    }

    private String responseKey(String key) {
        return "idempotency:response:" + key;
    }
}
