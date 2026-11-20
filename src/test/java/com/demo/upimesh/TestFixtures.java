package com.demo.upimesh;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.IdempotencyRecordRepository;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.MeshSimulatorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

/**
 * Test-only fixture for resetting state between integration tests.
 *
 * This class lives in src/test/java and is NOT on the production classpath.
 * It centralizes the cleanup logic previously duplicated across test classes
 * and avoids exposing a destructive delete-all method on the production
 * IdempotencyService.
 */
@Component
public class TestFixtures {

    @Autowired private IdempotencyService idempotency;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private MeshSimulatorService mesh;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * Full reset for test isolation: clears in-memory cache, deletes persistent
     * idempotency records and transactions, clears mesh device queues, and
     * restores the original demo account balances.
     */
    public void resetAll() {
        // 1. Clear in-memory duplicate cache (production semantics)
        idempotency.clear();

        // 2. Delete persistent records in a single transaction
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            idempotencyRepository.deleteAll();
            transactionRepository.deleteAll();
        });

        // 3. Clear mesh device queues
        mesh.resetMesh();

        // 4. Restore original demo balances
        Account alice = accountRepository.findById("alice@demo").orElseThrow();
        alice.setBalance(new BigDecimal("5000.00"));
        accountRepository.save(alice);

        Account bob = accountRepository.findById("bob@demo").orElseThrow();
        bob.setBalance(new BigDecimal("1000.00"));
        accountRepository.save(bob);

        Account carol = accountRepository.findById("carol@demo").orElseThrow();
        carol.setBalance(new BigDecimal("2500.00"));
        accountRepository.save(carol);

        Account dave = accountRepository.findById("dave@demo").orElseThrow();
        dave.setBalance(new BigDecimal("500.00"));
        accountRepository.save(dave);
    }
}