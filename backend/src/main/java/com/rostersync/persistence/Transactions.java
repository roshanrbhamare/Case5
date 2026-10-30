package com.rostersync.persistence;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Programmatic transactions. We use TransactionTemplate instead of {@code @Transactional}
 * for the write paths on purpose: the ingest loop needs one short transaction per record
 * inside a single method, and {@code @Transactional} on a method called from the same class
 * would be skipped (Spring proxies don't intercept self-calls).
 */
@Component
public class Transactions {

    private final TransactionTemplate tx;
    private final TransactionTemplate nested;

    public Transactions(PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
        this.nested = new TransactionTemplate(transactionManager);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** Runs work in one transaction and returns its result: commit on success, rollback on exception. */
    public <T> T call(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    public void run(Runnable work) {
        tx.executeWithoutResult(status -> work.run());
    }

    /**
     * Savepoint inside the current transaction: if work throws, only its own writes are rolled
     * back and the exception is rethrown; the outer transaction stays usable.
     */
    public <T> T inSavepoint(Supplier<T> work) {
        return nested.execute(status -> work.get());
    }
}
