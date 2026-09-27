package com.ledgercore.idempotency;

import org.jooq.DSLContext;

// the work behind one idempotent request, in two steps so slow calls don't hold a database connection
public interface IdempotentOperation {

    // runs first, with no transaction open yet
    default void beforeTransaction() {
    }

    StoredResponse inTransaction(DSLContext tx);
}
