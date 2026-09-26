package com.ledgercore.idempotency;

import com.ledgercore.config.Redis;
import com.ledgercore.http.Json;
import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

// the fast path: every call here fails soft, the database stays the source of truth
public class IdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCache.class);
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final Duration RESPONSE_TTL = Duration.ofHours(24);

    private final Redis redis;

    public IdempotencyCache(Redis redis) {
        this.redis = redis;
    }

    // empty means this request holds the lock now, otherwise the hash of the request that holds it
    public Optional<String> lock(String key, String requestHash) {
        try {
            RedisCommands<String, String> commands = redis.commands();
            String result = commands.set(lockKey(key), requestHash, SetArgs.Builder.nx().ex(LOCK_TTL));
            if ("OK".equals(result)) {
                return Optional.empty();
            }
            return Optional.ofNullable(commands.get(lockKey(key)));
        } catch (RedisException e) {
            warn(e);
            return Optional.empty();
        }
    }

    public void unlock(String key) {
        try {
            redis.commands().del(lockKey(key));
        } catch (RedisException e) {
            warn(e);
        }
    }

    public Optional<IdempotencyRecord> find(String key) {
        try {
            String value = redis.commands().get(responseKey(key));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(Json.read(value.getBytes(StandardCharsets.UTF_8), IdempotencyRecord.class));
        } catch (RedisException e) {
            warn(e);
            return Optional.empty();
        }
    }

    public void save(String key, IdempotencyRecord record) {
        try {
            redis.commands().setex(responseKey(key), RESPONSE_TTL.toSeconds(), Json.write(record));
        } catch (RedisException e) {
            warn(e);
        }
    }

    private String lockKey(String key) {
        return "idempotency:lock:" + key;
    }

    private String responseKey(String key) {
        return "idempotency:response:" + key;
    }

    private void warn(RedisException e) {
        log.warn("redis unavailable, relying on the database: {}", e.getMessage());
    }
}
