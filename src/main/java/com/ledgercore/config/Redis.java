package com.ledgercore.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class Redis implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Redis.class);
    private static final Duration RETRY_CONNECT_AFTER = Duration.ofSeconds(5);

    private final RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private Instant nextConnectAttempt = Instant.MIN;
    private final AtomicBoolean up = new AtomicBoolean(true);

    // url null means redis is not configured: every call fails right away and nothing is logged as an outage
    public Redis(String url) {
        if (url == null) {
            client = null;
            log.info("redis is not configured, using the database instead");
            return;
        }
        client = RedisClient.create(url);
        client.setOptions(ClientOptions.builder()
                // when redis is down, fail right away so the caller can fall back to the database
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(500)))
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build())
                .build());
    }

    // every command goes through here, so an outage is logged once when it starts and once when it ends
    public <T> T call(Function<RedisCommands<String, String>, T> command) {
        if (!isEnabled()) {
            throw new RedisException("redis is not configured");
        }
        T result;
        try {
            result = command.apply(commands());
        } catch (RedisException e) {
            if (up.getAndSet(false)) {
                log.warn("redis is down, using the database instead: {}", e.getMessage());
            }
            throw e;
        }
        if (!up.getAndSet(true)) {
            log.info("redis is back");
        }
        return result;
    }

    public boolean isEnabled() {
        return client != null;
    }

    // connects on first use, so the app also starts when redis is down
    // once connected, lettuce reconnects by itself after an outage
    private synchronized RedisCommands<String, String> commands() {
        if (connection == null) {
            connect();
        }
        return connection.sync();
    }

    private void connect() {
        Instant now = Instant.now();
        if (now.isBefore(nextConnectAttempt)) {
            throw new RedisConnectionException("redis is down, not retrying yet");
        }
        try {
            connection = client.connect();
        } catch (RedisException e) {
            nextConnectAttempt = now.plus(RETRY_CONNECT_AFTER);
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        }
    }
}
