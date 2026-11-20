package com.demo.upimesh;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.ClaimResult;
import com.demo.upimesh.model.IdempotencyRecord;
import com.demo.upimesh.model.IdempotencyRecord.IdempotencyStatus;
import com.demo.upimesh.model.IdempotencyRecordRepository;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates Flyway migrations, Hibernate schema validation (ddl-auto=validate),
 * and PostgreSQL-backed atomic settlement and concurrency locking guarantees.
 *
 * This test deliberately does NOT activate the H2 "test" profile. Its datasource
 * is pinned to the dedicated local PayMesh integration-test database so it can
 * never reach the development database or an unrelated PostgreSQL database.
 *
 * The demo credentials are supplied here as a test property so the test is not
 * broken by a PAYMESH_DEMO_USERNAME / PAYMESH_DEMO_PASSWORD environment variable
 * inherited from the developer shell. The datasource credentials are still
 * taken from the environment and are never hardcoded.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/paymesh_test",
        "spring.datasource.username=paymesh_test",
        "spring.datasource.password=${PAYMESH_TEST_DATABASE_PASSWORD:}",
        "paymesh.demo.username=test-demo",
        "paymesh.demo.password=test-demo-password",
        "spring.flyway.validate-on-migrate=false"
})
class PostgreSqlIntegrationTest {

    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;
    @Autowired private SettlementService settlementService;
    @Autowired private IdempotencyService idempotencyService;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetState() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(s -> {
            idempotencyRepository.deleteAll();
            transactionRepository.deleteAll();
            Account alice = accountRepository.findById("alice@demo").orElse(new Account("alice@demo", "Alice Smith", new BigDecimal("1000.00")));
            alice.setBalance(new BigDecimal("1000.00"));
            accountRepository.save(alice);

            Account bob = accountRepository.findById("bob@demo").orElse(new Account("bob@demo", "Bob Jones", new BigDecimal("500.00")));
            bob.setBalance(new BigDecimal("500.00"));
            accountRepository.save(bob);
        });
        idempotencyService.clear();
    }

    @Test
    void testPostgreSqlSchemaAndSeeding() {
        List<Account> accounts = accountRepository.findAll();
        assertFalse(accounts.isEmpty(), "Accounts should be present in PostgreSQL");

        Account alice = accountRepository.findById("alice@demo").orElse(null);
        assertNotNull(alice, "alice@demo should exist in PostgreSQL");
        assertTrue(alice.getBalance().compareTo(BigDecimal.ZERO) > 0, "Alice balance should be positive");
        assertTrue(accountRepository.existsById("bob@demo"));
    }

    @Test
    void postgreSqlAtomicSettlementCommitsFinancialAndIdempotencyTogether() {
        String hash = "1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff";
        ClaimResult claimResult = idempotencyService.tryClaim(hash, "pkt-1", "bridge-pg", 1);
        assertTrue(claimResult.isAcquired());
        UUID token = claimResult.claimToken();

        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("200.00"), "sig", "nonce-1", System.currentTimeMillis());

        Transaction tx = settlementService.settle(instruction, hash, token, "bridge-pg", 1);
        assertEquals(Transaction.Status.SETTLED, tx.getStatus());

        // Assert financial state in PostgreSQL
        Account alice = accountRepository.findById("alice@demo").orElseThrow();
        Account bob = accountRepository.findById("bob@demo").orElseThrow();
        assertEquals(new BigDecimal("800.00"), alice.getBalance());
        assertEquals(new BigDecimal("700.00"), bob.getBalance());

        // Assert idempotency record state in PostgreSQL
        IdempotencyRecord record = idempotencyRepository.findById(hash).orElseThrow();
        assertEquals(IdempotencyStatus.SETTLED, record.getStatus());
        assertEquals(tx.getId(), record.getTransactionId());
        assertNotNull(record.getCompletedAt());
    }

    @Test
    void postgreSqlOutdatedTokenCannotSettle() {
        String hash = "222233334444555566667777888899990000aaaabbbbccccddddeeeeffff1111";
        ClaimResult claimResult = idempotencyService.tryClaim(hash, "pkt-2", "bridge-pg", 1);
        assertTrue(claimResult.isAcquired());

        UUID fakeToken = UUID.randomUUID();
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("50.00"), "sig", "nonce-2", System.currentTimeMillis());

        assertThrows(IllegalStateException.class, () ->
                settlementService.settle(instruction, hash, fakeToken, "bridge-pg", 1));

        // Balances must remain unchanged
        Account alice = accountRepository.findById("alice@demo").orElseThrow();
        assertEquals(new BigDecimal("1000.00"), alice.getBalance());
        assertTrue(transactionRepository.findByPacketHash(hash).isEmpty());
    }

    @Test
    void postgreSqlAtomicInsufficientBalanceRejection() {
        String hash = "33334444555566667777888899990000aaaabbbbccccddddeeeeffff11112222";
        ClaimResult claimResult = idempotencyService.tryClaim(hash, "pkt-3", "bridge-pg", 1);
        assertTrue(claimResult.isAcquired());

        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("5000.00"), "sig", "nonce-3", System.currentTimeMillis());

        Transaction tx = settlementService.settle(instruction, hash, claimResult.claimToken(), "bridge-pg", 1);
        assertEquals(Transaction.Status.REJECTED, tx.getStatus());

        // Balances untouched
        Account alice = accountRepository.findById("alice@demo").orElseThrow();
        assertEquals(new BigDecimal("1000.00"), alice.getBalance());

        // Idempotency record atomically REJECTED with tx id
        IdempotencyRecord record = idempotencyRepository.findById(hash).orElseThrow();
        assertEquals(IdempotencyStatus.REJECTED, record.getStatus());
        assertEquals(tx.getId(), record.getTransactionId());
        assertEquals("insufficient_funds", record.getFailureReason());
    }

    @Test
    void postgreSqlPessimisticLockBlocksConcurrentStaleRecoveryUntilSettlementCommits() throws Exception {
        String hash = "4444555566667777888899990000aaaabbbbccccddddeeeeffff111122223333";
        // Pre-seed an almost-stale record with 120s age
        Instant twoMinutesAgo = Instant.now().minusSeconds(120);
        UUID workerToken = UUID.randomUUID();

        IdempotencyRecord initial = new IdempotencyRecord(
                hash, "pkt-4", IdempotencyStatus.CLAIMED, "worker-1", 1, twoMinutesAgo, workerToken);
        idempotencyRepository.saveAndFlush(initial);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        AtomicBoolean recoveryBlocked = new AtomicBoolean(false);
        AtomicInteger recoveryRows = new AtomicInteger(-1);

        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.00"), "sig", "nonce-4", System.currentTimeMillis());

        // Thread 1: Starts settlement under transaction, holds lock before completing
        Future<?> settlementFuture = pool.submit(() -> {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> {
                // Lock row
                IdempotencyRecord r = idempotencyRepository.findByPacketHashForUpdate(hash).orElseThrow();
                lockAcquired.countDown();
                try {
                    // Hold lock so Thread 2 attempts recovery concurrently
                    allowCommit.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                settlementService.settle(instruction, hash, workerToken, "worker-1", 1);
            });
        });

        // Thread 2: Attempts stale recovery while Thread 1 holds row lock
        Future<?> recoveryFuture = pool.submit(() -> {
            try {
                lockAcquired.await(5, TimeUnit.SECONDS);
                // Attempt recovery; in PostgreSQL, this blocks on the row lock held by Thread 1
                Instant now = Instant.now();
                Instant cutoff = now.minusSeconds(60);
                UUID candidateToken = UUID.randomUUID();
                int updated = idempotencyRepository.tryRecoverStaleClaimWithToken(
                        hash, IdempotencyStatus.CLAIMED, cutoff, "worker-2", 2, now, candidateToken);
                recoveryRows.set(updated);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Let Thread 2 queue up for row lock, then release Thread 1 to commit
        Thread.sleep(500);
        allowCommit.countDown();

        settlementFuture.get(5, TimeUnit.SECONDS);
        recoveryFuture.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        // Once Thread 1 committed SETTLED, Thread 2's recovery executed against the row whose status is now SETTLED,
        // so its WHERE status = 'CLAIMED' condition failed and returned 0 rows!
        assertEquals(0, recoveryRows.get(), "Concurrent recovery must not steal row while settlement holds lock and completes");
        IdempotencyRecord finalRecord = idempotencyRepository.findById(hash).orElseThrow();
        assertEquals(IdempotencyStatus.SETTLED, finalRecord.getStatus());
        assertEquals(workerToken, finalRecord.getClaimToken());
    }

    @Test
    void postgreSqlLedgerReconciliationRetrievesActualTransactionId() {
        String hash = "555566667777888899990000aaaabbbbccccddddeeeeffff1111222233334444";
        // Simulate crash window: ledger transaction exists, but idempotency record remained CLAIMED with null txId
        Transaction committedTx = new Transaction();
        committedTx.setPacketHash(hash);
        committedTx.setSenderVpa("alice@demo");
        committedTx.setReceiverVpa("bob@demo");
        committedTx.setAmount(new BigDecimal("150.00"));
        committedTx.setSignedAt(Instant.now());
        committedTx.setSettledAt(Instant.now());
        committedTx.setBridgeNodeId("crashed-bridge");
        committedTx.setHopCount(1);
        committedTx.setStatus(Transaction.Status.SETTLED);
        committedTx = transactionRepository.saveAndFlush(committedTx);

        IdempotencyRecord unhealed = new IdempotencyRecord(
                hash, "pkt-5", IdempotencyStatus.CLAIMED, "crashed-bridge", 1, Instant.now());
        unhealed.setTransactionId(null);
        idempotencyRepository.saveAndFlush(unhealed);

        // Ingestion of duplicate packet must trigger safe reconciliation
        ClaimResult result = idempotencyService.tryClaim(hash, "pkt-5", "new-bridge", 2);
        assertFalse(result.isAcquired());
        assertEquals(ClaimResult.Status.TERMINAL_DUPLICATE, result.status());

        // Verify idempotency record was healed with the actual transaction ID from the ledger
        IdempotencyRecord healed = idempotencyRepository.findById(hash).orElseThrow();
        assertEquals(IdempotencyStatus.SETTLED, healed.getStatus());
        assertEquals(committedTx.getId(), healed.getTransactionId(), "Must store actual transaction ID from ledger");
    }

    @Test
    void postgreSqlFailureDuringSettlementRollsBackBalancesTransactionsAndIdempotencyRecord() {
        String hash = "66667777888899990000aaaabbbbccccddddeeeeffff11112222333344445555";
        ClaimResult claimResult = idempotencyService.tryClaim(hash, "pkt-rollback", "bridge-pg", 1);
        assertTrue(claimResult.isAcquired());
        UUID token = claimResult.claimToken();

        BigDecimal aliceBefore = accountRepository.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accountRepository.findById("bob@demo").orElseThrow().getBalance();

        // Valid instruction amount, but we trigger a failure AFTER debit/credit and tx creation
        // by wrapping settlement inside a transaction that experiences an injected failure
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("300.00"), "sig", "nonce-rb", System.currentTimeMillis());

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(RuntimeException.class, () -> {
            txTemplate.executeWithoutResult(status -> {
                // Execute settlement (which debits, credits, inserts transaction, and updates idempotency record)
                settlementService.settle(instruction, hash, token, "bridge-pg", 1);
                // Deliberately throw an unhandled RuntimeException before transaction commit
                throw new RuntimeException("Simulated unexpected crash right before transaction commit!");
            });
        });

        // 1. Assert Alice balance rolled back
        Account aliceAfter = accountRepository.findById("alice@demo").orElseThrow();
        assertEquals(aliceBefore, aliceAfter.getBalance(), "Sender balance must roll back completely");

        // 2. Assert Bob balance rolled back
        Account bobAfter = accountRepository.findById("bob@demo").orElseThrow();
        assertEquals(bobBefore, bobAfter.getBalance(), "Receiver balance must roll back completely");

        // 3. Assert no transaction inserted
        assertTrue(transactionRepository.findByPacketHash(hash).isEmpty(),
                "No transaction record must exist after rollback");

        // 4. Assert IdempotencyRecord rolled back to CLAIMED (not SETTLED, no tx id)
        IdempotencyRecord recAfter = idempotencyRepository.findById(hash).orElseThrow();
        assertEquals(IdempotencyStatus.CLAIMED, recAfter.getStatus(),
                "Idempotency record status must roll back to CLAIMED");
        assertNull(recAfter.getTransactionId(), "Transaction ID must remain null");
        assertNull(recAfter.getCompletedAt(), "CompletedAt must remain null");
    }

    @Test
    void postgreSqlLedgerReconciliationRacingWithConcurrentStateChange() throws Exception {
        String hash = "7777888899990000aaaabbbbccccddddeeeeffff111122223333444455556666";

        // Seed a committed transaction in the financial ledger
        Transaction tx = new Transaction();
        tx.setPacketHash(hash);
        tx.setSenderVpa("alice@demo");
        tx.setReceiverVpa("bob@demo");
        tx.setAmount(new BigDecimal("75.00"));
        tx.setSignedAt(Instant.now());
        tx.setSettledAt(Instant.now());
        tx.setBridgeNodeId("worker-orig");
        tx.setHopCount(1);
        tx.setStatus(Transaction.Status.SETTLED);
        tx = transactionRepository.saveAndFlush(tx);

        // Seed idempotency record as CLAIMED
        IdempotencyRecord rec = new IdempotencyRecord(
                hash, "pkt-race", IdempotencyStatus.CLAIMED, "worker-orig", 1, Instant.now());
        idempotencyRepository.saveAndFlush(rec);

        // Run concurrent reconciliation attempts from 5 threads
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(5);
        AtomicInteger terminalDups = new AtomicInteger(0);

        for (int i = 0; i < 5; i++) {
            final String node = "recon-node-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    ClaimResult res = idempotencyService.tryClaim(hash, "pkt-race", node, 1);
                    if (!res.isAcquired() && res.status() == ClaimResult.Status.TERMINAL_DUPLICATE) {
                        terminalDups.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(5, terminalDups.get(), "All concurrent callers must safely receive TERMINAL_DUPLICATE");
        IdempotencyRecord finalRec = idempotencyRepository.findById(hash).orElseThrow();
        assertEquals(IdempotencyStatus.SETTLED, finalRec.getStatus());
        assertEquals(tx.getId(), finalRec.getTransactionId());
    }

    // =========================================================================
    // Stage 5 Tests: Conditional Failure and Invalid Transitions
    // =========================================================================

    @Test
    void postgreSqlValidTokenTransitionsClaimedToFailed() {
        String hash = "888899990000aaaabbbbccccddddeeeeffff1111222233334444555566667777";
        ClaimResult claim = idempotencyService.tryClaim(hash, "pkt-fail-1", "bridge-pg", 1);
        assertTrue(claim.isAcquired());
        UUID token = claim.claimToken();

        boolean updated = idempotencyService.markFailedWithOwner(hash, token, "transient_network_timeout");
        assertTrue(updated, "Valid token must transition CLAIMED -> FAILED");

        IdempotencyRecord rec = idempotencyRepository.findById(hash).orElseThrow();
        assertEquals(IdempotencyStatus.FAILED, rec.getStatus());
        assertEquals("transient_network_timeout", rec.getFailureReason());
        assertNull(rec.getTransactionId());
    }

    @Test
    void postgreSqlValidTokenTransitionsClaimedToInvalid() {
        String hash = "99990000aaaabbbbccccddddeeeeffff11112222333344445555666677778888";
        ClaimResult claim = idempotencyService.tryClaim(hash, "pkt-inv-1", "bridge-pg", 1);
        assertTrue(claim.isAcquired());
        UUID token = claim.claimToken();

        boolean updated = idempotencyService.markInvalidWithOwner(hash, token, "decryption_failed");
        assertTrue(updated, "Valid token must transition CLAIMED -> INVALID");

        IdempotencyRecord rec = idempotencyRepository.findById(hash).orElseThrow();
        assertEquals(IdempotencyStatus.INVALID, rec.getStatus());
        assertEquals("decryption_failed", rec.getFailureReason());
        assertNotNull(rec.getCompletedAt());
    }

    @Test
    void postgreSqlOutdatedTokenCannotTransitionToFailedOrInvalid() {
        String hash = "aaaa0000111122223333444455556666777788889999bbbbaaaabbbbccccdddd";
        ClaimResult claim = idempotencyService.tryClaim(hash, "pkt-outdated", "bridge-pg", 1);
        assertTrue(claim.isAcquired());
        UUID genuineToken = claim.claimToken();
        UUID staleToken = UUID.randomUUID();

        // 1. Attempt markFailed with stale token
        boolean failAttempt = idempotencyService.markFailedWithOwner(hash, staleToken, "stale_attempt");
        assertFalse(failAttempt, "Outdated token must return false on markFailed");

        // Record must remain CLAIMED with genuineToken
        IdempotencyRecord afterFail = idempotencyRepository.findById(hash).orElseThrow();
        assertEquals(IdempotencyStatus.CLAIMED, afterFail.getStatus());
        assertEquals(genuineToken, afterFail.getClaimToken());

        // 2. Attempt markInvalid with stale token
        boolean invAttempt = idempotencyService.markInvalidWithOwner(hash, staleToken, "stale_attempt");
        assertFalse(invAttempt, "Outdated token must return false on markInvalid");

        // Record must still remain CLAIMED with genuineToken
        IdempotencyRecord afterInv = idempotencyRepository.findById(hash).orElseThrow();
        assertEquals(IdempotencyStatus.CLAIMED, afterInv.getStatus());
        assertEquals(genuineToken, afterInv.getClaimToken());
    }

    @Test
    void postgreSqlFailedRecordCannotBeOverwrittenByStaleWorker() {
        String hash = "bbbb1111222233334444555566667777888899990000ccccaaaabbbbccccdddd";
        ClaimResult claim1 = idempotencyService.tryClaim(hash, "pkt-failed-retry", "worker-1", 1);
        assertTrue(claim1.isAcquired());
        UUID token1 = claim1.claimToken();

        // Worker 1 marks failed
        assertTrue(idempotencyService.markFailedWithOwner(hash, token1, "worker1_failed"));

        // Worker 2 retries and claims
        ClaimResult claim2 = idempotencyService.tryClaim(hash, "pkt-failed-retry", "worker-2", 2);
        assertTrue(claim2.isAcquired());
        assertEquals(ClaimResult.Status.RETRIED, claim2.status());
        UUID token2 = claim2.claimToken();
        assertNotEquals(token1, token2);

        // Stale Worker 1 attempts to mark INVALID with old token1
        boolean staleAttempt = idempotencyService.markInvalidWithOwner(hash, token1, "worker1_zombie_update");
        assertFalse(staleAttempt, "Stale worker cannot mutate record after retry rotated token");

        // Record must remain CLAIMED under worker-2's token2
        IdempotencyRecord rec = idempotencyRepository.findById(hash).orElseThrow();
        assertEquals(IdempotencyStatus.CLAIMED, rec.getStatus());
        assertEquals(token2, rec.getClaimToken());
        assertEquals("worker-2", rec.getBridgeNodeId());
    }

    @Test
    void postgreSqlTerminalSettledOrRejectedCannotBeOverwrittenByFailureOrInvalid() {
        String hashSettled = "cccc2222333344445555666677778888999900001111ddddaaaabbbbccccdddd";
        ClaimResult claimSettled = idempotencyService.tryClaim(hashSettled, "pkt-settled-terminal", "bridge-pg", 1);
        assertTrue(claimSettled.isAcquired());
        UUID tokenSettled = claimSettled.claimToken();

        PaymentInstruction inst = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("10.00"), "sig", "nonce-term", System.currentTimeMillis());
        Transaction tx = settlementService.settle(inst, hashSettled, tokenSettled, "bridge-pg", 1);
        assertEquals(Transaction.Status.SETTLED, tx.getStatus());

        // Attempt markFailed on SETTLED record even with correct token
        boolean failSettled = idempotencyService.markFailedWithOwner(hashSettled, tokenSettled, "illegal_attempt");
        assertFalse(failSettled, "SETTLED record cannot be overwritten by markFailed");

        // Attempt markInvalid on SETTLED record
        boolean invSettled = idempotencyService.markInvalidWithOwner(hashSettled, tokenSettled, "illegal_attempt");
        assertFalse(invSettled, "SETTLED record cannot be overwritten by markInvalid");

        IdempotencyRecord finalRec = idempotencyRepository.findById(hashSettled).orElseThrow();
        assertEquals(IdempotencyStatus.SETTLED, finalRec.getStatus());
        assertEquals(tx.getId(), finalRec.getTransactionId());
    }

    @Test
    void postgreSqlConcurrentFailureAndInvalidWithDifferentTokensCannotBothWin() throws Exception {
        String hash = "dddd3333444455556666777788889999000011112222eeeeaaaabbbbccccdddd";
        ClaimResult claim = idempotencyService.tryClaim(hash, "pkt-race-tokens", "bridge-pg", 1);
        assertTrue(claim.isAcquired());
        UUID genuineToken = claim.claimToken();
        UUID fakeToken = UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicBoolean genuineWon = new AtomicBoolean(false);
        AtomicBoolean fakeWon = new AtomicBoolean(false);

        // Worker 1 uses genuine token to mark FAILED
        pool.submit(() -> {
            try {
                start.await();
                genuineWon.set(idempotencyService.markFailedWithOwner(hash, genuineToken, "genuine_failure"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        });

        // Worker 2 uses fake token to mark INVALID
        pool.submit(() -> {
            try {
                start.await();
                fakeWon.set(idempotencyService.markInvalidWithOwner(hash, fakeToken, "imposter_invalid"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        assertTrue(genuineWon.get(), "Genuine token must win transition");
        assertFalse(fakeWon.get(), "Fake/stale token must fail transition (0 rows updated)");

        IdempotencyRecord finalRec = idempotencyRepository.findById(hash).orElseThrow();
        assertEquals(IdempotencyStatus.FAILED, finalRec.getStatus());
        assertEquals("genuine_failure", finalRec.getFailureReason());
    }

    @Test
    void postgreSqlConcurrentInitialClaimsExactlyOneWinner() throws Exception {
        String hash = "ffff1111222233334444555566667777888899990000aaaabbbbccccddddeeee";
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<String> nodes = new ArrayList<>();
        List<Future<ClaimResult>> futures = new ArrayList<>();
        List<ClaimWorkerObservation> observations = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String node = "pg-node-" + i;
            nodes.add(node);
            futures.add(pool.submit(() -> {
                start.await();
                return idempotencyService.tryClaim(hash, "pkt-race", node, 1);
            }));
        }

        try {
            start.countDown();
            pool.shutdown();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    observations.add(new ClaimWorkerObservation(nodes.get(i),
                            futures.get(i).get(10, TimeUnit.SECONDS), null));
                } catch (java.util.concurrent.ExecutionException ex) {
                    observations.add(new ClaimWorkerObservation(nodes.get(i), null, ex.getCause()));
                } catch (java.util.concurrent.TimeoutException ex) {
                    observations.add(new ClaimWorkerObservation(nodes.get(i), null, ex));
                }
            }
        } finally {
            start.countDown();
            pool.shutdownNow();
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Fresh-claim executor did not terminate");
            }
        }

        Set<UUID> acquiredTokens = new HashSet<>();
        List<ClaimResult> acquiredResults = new ArrayList<>();
        Map<UUID, String> tokenLabels = new HashMap<>();
        List<ClaimWorkerObservation> workerErrors = new ArrayList<>();
        for (ClaimWorkerObservation observation : observations) {
            ClaimResult result = observation.result();
            String tokenLabel = result == null || result.claimToken() == null ? "none"
                    : tokenLabels.computeIfAbsent(result.claimToken(), ignored -> "token-" + (tokenLabels.size() + 1));
            System.out.printf("CLAIM_DIAG worker=%s status=%s acquired=%s token=%s exception=%s%n",
                    observation.node(), result == null ? "none" : result.status(),
                    result != null && result.isAcquired(), tokenLabel,
                    observation.error() == null ? "none" : observation.error().getClass().getName()
                            + ": " + observation.error().getMessage());
            if (observation.error() != null) workerErrors.add(observation);
            if (result != null && result.isAcquired()) {
                acquiredResults.add(result);
                if (result.claimToken() != null) acquiredTokens.add(result.claimToken());
            }
        }

        IdempotencyRecord rec = idempotencyRepository.findById(hash).orElse(null);
        String persistedTokenLabel = rec == null || rec.getClaimToken() == null ? "none"
                : tokenLabels.computeIfAbsent(rec.getClaimToken(), ignored -> "token-" + (tokenLabels.size() + 1));
        long rowCount = idempotencyRepository.count();
        System.out.printf("CLAIM_SUMMARY workers=%d acquired=%d distinctAcquiredTokens=%d rowCount=%d persistedStatus=%s persistedToken=%s%n",
                observations.size(), acquiredResults.size(), acquiredTokens.size(), rowCount,
                rec == null ? "ABSENT" : rec.getStatus(), persistedTokenLabel);

        if (!workerErrors.isEmpty()) {
            AssertionError failure = new AssertionError("Fresh-claim worker exceptions: " + workerErrors.size());
            workerErrors.forEach(observation -> failure.addSuppressed(observation.error()));
            throw failure;
        }

        assertEquals(threadCount, observations.size(), "Every fresh-claim worker must produce a result");
        assertEquals(1, acquiredResults.size(), "Exactly one worker must acquire ownership on fresh claim in PostgreSQL");
        assertEquals(1, acquiredTokens.size(), "Exactly one distinct claim token must be acquired");
        ClaimResult winner = acquiredResults.get(0);
        assertEquals(ClaimResult.Status.ACQUIRED, winner.status(), "Fresh hash winner must be an initial acquisition");
        assertNotNull(winner.claimToken(), "The acquired result must carry its claim token");
        assertEquals(1, rowCount, "Only one row must exist in PostgreSQL");
        assertNotNull(rec, "The winning claim must be persisted");
        assertEquals(IdempotencyStatus.CLAIMED, rec.getStatus());
        assertEquals(winner.claimToken(), rec.getClaimToken(), "Persisted claim token must match the sole acquired result");
    }

    private record ClaimWorkerObservation(String node, ClaimResult result, Throwable error) { }

    @Test
    void postgreSqlConcurrentStaleRecoveryExactlyOneWinnerAndRotatesToken() throws Exception {
        String hash = "eeee2222333344445555666677778888999900001111aaaabbbbccccddddeeee";
        UUID originalToken = UUID.randomUUID();
        Instant twoMinutesAgo = Instant.now().minusSeconds(120);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(s -> {
            IdempotencyRecord stale = new IdempotencyRecord(
                    hash, "pkt-stale", IdempotencyStatus.CLAIMED, "dead-node", 1, twoMinutesAgo, originalToken);
            idempotencyRepository.saveAndFlush(stale);
        });

        int recoveryWorkers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(recoveryWorkers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(recoveryWorkers);
        AtomicInteger recoveredCount = new AtomicInteger();

        for (int i = 0; i < recoveryWorkers; i++) {
            final String node = "rec-node-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    ClaimResult res = idempotencyService.tryClaim(hash, "pkt-stale", node, 1);
                    if (res.status() == ClaimResult.Status.RECOVERED) {
                        recoveredCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, recoveredCount.get(), "Exactly one recovery worker must succeed in PostgreSQL");
        IdempotencyRecord rec = idempotencyRepository.findById(hash).orElseThrow();
        assertNotEquals(originalToken, rec.getClaimToken(), "Winning recovery must rotate claim token");
        assertEquals(IdempotencyStatus.CLAIMED, rec.getStatus());

        // Prove old token cannot settle
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("50.00"), "sig", "nonce-old", System.currentTimeMillis());
        assertThrows(IllegalStateException.class, () ->
                settlementService.settle(instruction, hash, originalToken, "dead-node", 1));
    }

    @Test
    void postgreSqlConcurrentFailedRetriesExactlyOneWinnerAndRotatesToken() throws Exception {
        String hash = "cccc3333444455556666777788889999000011112222aaaabbbbccccddddeeee";
        UUID failedToken = UUID.randomUUID();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(s -> {
            IdempotencyRecord failedRec = new IdempotencyRecord(
                    hash, "pkt-fail", IdempotencyStatus.FAILED, "failed-node", 1, Instant.now(), failedToken);
            failedRec.setFailureReason("simulated_failure");
            idempotencyRepository.saveAndFlush(failedRec);
        });

        int retryWorkers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(retryWorkers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(retryWorkers);
        AtomicInteger retryCount = new AtomicInteger();

        for (int i = 0; i < retryWorkers; i++) {
            final String node = "retry-node-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    ClaimResult res = idempotencyService.tryClaim(hash, "pkt-fail", node, 1);
                    if (res.status() == ClaimResult.Status.RETRIED) {
                        retryCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, retryCount.get(), "Exactly one retry worker must succeed in PostgreSQL");
        IdempotencyRecord rec = idempotencyRepository.findById(hash).orElseThrow();
        assertNotEquals(failedToken, rec.getClaimToken(), "Retry must rotate claim token");
        assertEquals(IdempotencyStatus.CLAIMED, rec.getStatus());

        // Prove old token cannot settle
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("50.00"), "sig", "nonce-old-failed", System.currentTimeMillis());
        assertThrows(IllegalStateException.class, () ->
                settlementService.settle(instruction, hash, failedToken, "failed-node", 1));
    }

    @Test
    void postgreSqlConcurrentSettlementCannotDoubleDebitOrCredit() throws Exception {
        String hash = "bbbb4444555566667777888899990000111122223333aaaabbbbccccddddeeee";
        ClaimResult claim = idempotencyService.tryClaim(hash, "pkt-race-settle", "bridge-pg", 1);
        assertTrue(claim.isAcquired());
        UUID token = claim.claimToken();

        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.00"), "sig", "nonce-race-settle", System.currentTimeMillis());

        int threadCount = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final String node = "settler-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    Transaction t = settlementService.settle(instruction, hash, token, node, 1);
                    if (t.getStatus() == Transaction.Status.SETTLED) {
                        successCount.incrementAndGet();
                    }
                } catch (IllegalStateException e) {
                    // Expected for losing concurrent calls after status transitions from CLAIMED
                    rejectedCount.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, successCount.get(), "Exactly one concurrent settlement must succeed");
        assertEquals(threadCount - 1, rejectedCount.get(), "All other concurrent settlements must be rejected");

        // Verify balances moved exactly once (1000 - 100 = 900, 500 + 100 = 600)
        Account alice = accountRepository.findById("alice@demo").orElseThrow();
        Account bob = accountRepository.findById("bob@demo").orElseThrow();
        assertEquals(new BigDecimal("900.00"), alice.getBalance());
        assertEquals(new BigDecimal("600.00"), bob.getBalance());
    }
}
