package com.ledgercore.fraud;

import com.ledgercore.config.FraudConfig;
import com.ledgercore.config.Redis;
import com.ledgercore.transfer.TransferStatus;
import io.lettuce.core.Range;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.sync.RedisCommands;
import org.jooq.DSLContext;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.ledgercore.jooq.Tables.TRANSFERS;

// redis is called before the transfer transaction opens, the database reads go through that transaction
public class FraudFactsCollector {

    private final DSLContext db;
    private final Redis redis;
    private final FraudConfig config;
    private final Clock clock;

    public FraudFactsCollector(DSLContext db, Redis redis, FraudConfig config, Clock clock) {
        this.db = db;
        this.redis = redis;
        this.config = config;
        this.clock = clock;
    }

    // counts this attempt before any transaction opens, so a slow redis never holds a database connection
    public int recordAttempt(long accountId) {
        try {
            return countInRedis(accountId);
        } catch (RedisException e) {
            return countInDatabase(accountId) + 1;
        }
    }

    public FraudFacts collect(DSLContext tx, long fromId, long toId, long amount, int attemptsInWindow) {
        List<Long> history = recentCompletedAmounts(tx, fromId);
        return new FraudFacts(amount, attemptsInWindow, history.size(), average(history), hasSentTo(tx, fromId, toId));
    }

    // a sorted set per account, scored by time, with one unique member per attempt
    private int countInRedis(long accountId) {
        return redis.call(commands -> countInRedis(commands, accountId));
    }

    private int countInRedis(RedisCommands<String, String> commands, long accountId) {
        String key = "fraud:velocity:" + accountId;
        long now = clock.millis();
        long windowMillis = config.velocityWindow().toMillis();
        commands.zadd(key, now, UUID.randomUUID().toString());
        commands.zremrangebyscore(key, Range.from(Range.Boundary.unbounded(), Range.Boundary.including(now - windowMillis)));
        long count = commands.zcard(key);
        // an idle account's key goes away by itself
        commands.pexpire(key, windowMillis);
        return (int) count;
    }

    // only stored transfers count here, so a 422 for insufficient funds is not seen, a bit more lenient than redis
    private int countInDatabase(long accountId) {
        OffsetDateTime windowStart = OffsetDateTime.ofInstant(clock.instant().minus(config.velocityWindow()), ZoneOffset.UTC);
        return db.fetchCount(TRANSFERS,
                TRANSFERS.FROM_ACCOUNT_ID.eq(accountId).and(TRANSFERS.CREATED_AT.gt(windowStart)));
    }

    private List<Long> recentCompletedAmounts(DSLContext tx, long accountId) {
        return tx.select(TRANSFERS.AMOUNT)
                .from(TRANSFERS)
                .where(TRANSFERS.FROM_ACCOUNT_ID.eq(accountId)
                        .and(TRANSFERS.STATUS.eq(TransferStatus.COMPLETED.name())))
                .orderBy(TRANSFERS.CREATED_AT.desc(), TRANSFERS.ID.desc())
                .limit(config.anomalyHistorySize())
                .fetch(TRANSFERS.AMOUNT);
    }

    private boolean hasSentTo(DSLContext tx, long fromId, long toId) {
        return tx.fetchExists(TRANSFERS,
                TRANSFERS.FROM_ACCOUNT_ID.eq(fromId)
                        .and(TRANSFERS.TO_ACCOUNT_ID.eq(toId))
                        .and(TRANSFERS.STATUS.eq(TransferStatus.COMPLETED.name())));
    }

    private long average(List<Long> amounts) {
        if (amounts.isEmpty()) {
            return 0;
        }
        long sum = 0;
        for (long amount : amounts) {
            sum = Math.addExact(sum, amount);
        }
        return sum / amounts.size();
    }
}
