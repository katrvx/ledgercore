package com.ledgercore.idempotency;

import com.ledgercore.http.ConflictException;
import com.ledgercore.http.Json;
import com.ledgercore.http.NotFoundException;
import com.ledgercore.http.Problem;
import com.ledgercore.http.RequestBody;
import com.ledgercore.http.UnprocessableException;
import com.ledgercore.http.ValidationException;
import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;
import spark.Request;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;

public class IdempotencyService {

    private static final int MAX_KEY_LENGTH = 255;

    private final DSLContext db;
    private final IdempotencyCache cache;
    private final IdempotencyRepository repository;

    public IdempotencyService(DSLContext db, IdempotencyCache cache, IdempotencyRepository repository) {
        this.db = db;
        this.cache = cache;
        this.repository = repository;
    }

    // runs the action once per key and gives every retry the same response back
    public StoredResponse run(Request request, Function<DSLContext, StoredResponse> action) {
        String key = validKey(request.headers("Idempotency-Key"));
        String hash = requestHash(request);

        Optional<IdempotencyRecord> done = cache.find(key).or(() -> repository.find(key));
        if (done.isPresent()) {
            return replay(done.get(), hash);
        }
        Optional<String> holder = cache.lock(key, hash);
        if (holder.isPresent()) {
            if (!holder.get().equals(hash)) {
                throw differentRequest();
            }
            throw new ConflictException("a request with this Idempotency-Key is still in progress");
        }
        try {
            IdempotencyRecord record = execute(key, hash, action);
            // saved before the lock goes, so a retry never sees neither a lock nor a response
            cache.save(key, record);
            return replay(record, hash);
        } finally {
            cache.unlock(key);
        }
    }

    // the key row is written in the same transaction as the money movement
    private IdempotencyRecord execute(String key, String hash, Function<DSLContext, StoredResponse> action) {
        try {
            return db.transactionResult(trx -> {
                StoredResponse response = action.apply(trx.dsl());
                IdempotencyRecord record = new IdempotencyRecord(hash, response);
                repository.insert(trx.dsl(), key, record);
                return record;
            });
        } catch (UnprocessableException e) {
            return storeFailure(key, hash, 422, e.getMessage());
        } catch (NotFoundException e) {
            return storeFailure(key, hash, 404, e.getMessage());
        } catch (IntegrityConstraintViolationException e) {
            return storedByDuplicate(key, e);
        }
    }

    // the business transaction has rolled back, so the failed answer gets a small transaction of its own
    private IdempotencyRecord storeFailure(String key, String hash, int status, String detail) {
        StoredResponse response = new StoredResponse(status, null, Json.write(Problem.of(status, detail)));
        IdempotencyRecord record = new IdempotencyRecord(hash, response);
        try {
            db.transaction(trx -> repository.insert(trx.dsl(), key, record));
            return record;
        } catch (IntegrityConstraintViolationException e) {
            return storedByDuplicate(key, e);
        }
    }

    // redis was down and a duplicate finished first, so its answer is the answer
    private IdempotencyRecord storedByDuplicate(String key, IntegrityConstraintViolationException e) {
        return repository.find(key).orElseThrow(() -> e);
    }

    private StoredResponse replay(IdempotencyRecord record, String hash) {
        if (!record.requestHash().equals(hash)) {
            throw differentRequest();
        }
        return record.response();
    }

    private UnprocessableException differentRequest() {
        return new UnprocessableException("Idempotency-Key was already used with a different request");
    }

    private String validKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ValidationException("Idempotency-Key header is required");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new ValidationException("Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        // the key goes into redis key names and the database, so no spaces or control characters
        for (char c : key.toCharArray()) {
            if (c < '!' || c > '~') {
                throw new ValidationException("Idempotency-Key must contain only visible ASCII characters");
            }
        }
        return key;
    }

    // method and path are part of the hash, so one key can't be reused on another endpoint
    private String requestHash(Request request) {
        String canonical = request.requestMethod() + " " + request.pathInfo() + "\n" + Json.canonical(RequestBody.read(request));
        return sha256(canonical);
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
