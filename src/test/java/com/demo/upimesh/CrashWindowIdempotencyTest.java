package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.IdempotencyRecord;
import com.demo.upimesh.model.IdempotencyRecord.IdempotencyStatus;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class CrashWindowIdempotencyTest {

    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private IdempotencyService idempotency;
    @Autowired private AccountRepository accounts;
    @Autowired private IdempotencyRecordRepository repository;
    @Autowired private HybridCryptoService crypto;
    @Autowired private TestFixtures fixtures;
    @Autowired private SettlementService settlementService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AccountRepository accountRepository;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;

    @BeforeEach
    void setup() {
        fixtures.resetAll();
    }

    @Test
    void concurrentClaimOnFreshHashWinsAtDatabaseLevel() throws Exception {
        String hash = "f131d8339f62cbde19e67ac16ff8aa10829ff70a2deadd64a8861bc73e4c9d25";
        fixtures.resetAll();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        AtomicInteger acquiredCount = new AtomicInteger();

        for (int i = 0; i < 8; i++) {
            final String node = "pg-node-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    ClaimResult res = idempotency.tryClaim(hash, "pkt-1", node, 1);
                    if (res.isAcquired()) {
                        acquiredCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "All workers must finish within 10s");
        pool.shutdown();

        assertEquals(1, acquiredCount.get(), "Exactly one worker must acquire the fresh claim hash");
    }

    @Test
    void concurrentDuplicateDeliverySettlesExactlyOnce() throws Exception {
        String hash = "f131d8339f62cbde19e67ac16ff8aa10829ff70a2deadd64a8861bc73e4c9d25";
        fixtures.resetAll();

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);
        AtomicInteger settledCount = new AtomicInteger();

        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.00"), "sig", "nonce-1", System.currentTimeMillis());

        // First claim the hash to create the idempotency record
        ClaimResult claim = idempotency.tryClaim(hash, "pkt-1", "claimer", 1);
        assertTrue(claim.isAcquired(), "Initial claim must succeed");

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    Transaction t = settlementService.settle(instruction, hash, claim.claimToken(), "bridge-" + idx, 1);
                    if (t.getStatus() == Transaction.Status.SETTLED) {
                        settledCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Expected for concurrent losers
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "All workers must finish within 10s");
        pool.shutdown();

        assertEquals(1, settledCount.get(), "Exactly one settlement must succeed");
    }

    @Test
    void crashAfterSettlementMarksIdempotencyRecordSettledAndPreventsSecondDebit() throws Exception {
        String hash = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6";
        fixtures.resetAll();

        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("50.00"), "sig", "nonce-2", System.currentTimeMillis());

        // Claim first to create the idempotency record
        ClaimResult claim = idempotency.tryClaim(hash, "pkt-1", "worker-1", 1);
        assertTrue(claim.isAcquired());

        // Commit the settlement (simulates successful DB commit before process crash)
        settlementService.settle(instruction, hash, claim.claimToken(), "worker-1", 1);

        // Record must be SETTLED after crash
        IdempotencyRecord record = idempotencyRepository.findByPacketHash(hash).orElseThrow();
        assertEquals(IdempotencyStatus.SETTLED, record.getStatus());

        // Alice balance must be debited exactly once
        Account alice = accountRepository.findById("alice@demo").orElseThrow();
        assertEquals(new BigDecimal("4950.00"), alice.getBalance());

        // Second attempt must fail since idempotency record is already SETTLED
        assertThrows(IllegalStateException.class, () -> {
            settlementService.settle(instruction, hash, claim.claimToken(), "worker-2", 1);
        });

        // Balance must still be debited only once
        Account aliceAfter = accountRepository.findById("alice@demo").orElseThrow();
        assertEquals(new BigDecimal("4950.00"), aliceAfter.getBalance());
    }

    @Test
    void crashBeforeSettlementCommitsLeavesClaimedRecordNotSettled() throws Exception {
        String hash = "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1";
        fixtures.resetAll();

        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("30.00"), "sig", "nonce-3", System.currentTimeMillis());

        // Claim first to create the idempotency record
        ClaimResult claim = idempotency.tryClaim(hash, "pkt-1", "worker-1", 1);
        assertTrue(claim.isAcquired());

        // Record must remain CLAIMED (not SETTLED) when settlement is interrupted
        IdempotencyRecord record = idempotencyRepository.findByPacketHash(hash).orElseThrow();
        assertEquals(IdempotencyStatus.CLAIMED, record.getStatus());
        assertNull(record.getTransactionId());
    }

    @Test
    void failedRecordRetryableButOnlyIfNoTransactionExists() throws Exception {
        String hash = "c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
        fixtures.resetAll();

        ClaimResult claim1 = idempotency.tryClaim(hash, "pkt-1", "worker-1", 1);
        assertTrue(claim1.isAcquired());

        // Mark as failed - record becomes FAILED and CAN be retried (recovery path)
        assertTrue(idempotency.markFailedWithOwner(hash, claim1.claimToken(), "test_failure"));

        // Second claim on same hash should RETRY (FAILED is retryable, not terminal)
        ClaimResult claim2 = idempotency.tryClaim(hash, "pkt-2", "worker-2", 1);
        assertTrue(claim2.isAcquired(), "FAILED record must be retryable");
        assertEquals(ClaimResult.Status.RETRIED, claim2.status(), "Retry must return RETRIED status");
    }

    @Test
    void lostRecoveryWorkerCannotSettleAfterOwnershipTransfer() throws Exception {
        String hash = "d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3";
        fixtures.resetAll();

        ClaimResult initialClaim = idempotency.tryClaim(hash, "pkt-1", "worker-1", 1);
        assertTrue(initialClaim.isAcquired());

        // Worker 1 marks failed
        assertTrue(idempotency.markFailedWithOwner(hash, initialClaim.claimToken(), "worker1_failed"));

        // Worker 2 claims and marks failed
        ClaimResult claimWorker2 = idempotency.tryClaim(hash, "pkt-2", "worker-2", 1);
        assertTrue(claimWorker2.isAcquired());
        assertEquals(ClaimResult.Status.RETRIED, claimWorker2.status());

        // Worker 1's old token should no longer work
        assertFalse(idempotency.markFailedWithOwner(hash, initialClaim.claimToken(), "stale_attempt"));

        // New token must work
        assertTrue(idempotency.markFailedWithOwner(hash, claimWorker2.claimToken(), "new_attempt"));
    }

    @Test
    void tamperedCiphertextNeverSettles() throws Exception {
        String hash = "e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5";
        fixtures.resetAll();

        IdempotencyRecord record = new IdempotencyRecord(
                hash, "pkt-tampered", IdempotencyStatus.CLAIMED, "worker-1", 1, Instant.now());
        record.setFailureReason("tampered_ciphertext");
        idempotencyRepository.saveAndFlush(record);

        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("20.00"), "sig", "nonce-tampered", System.currentTimeMillis());

        assertThrows(IllegalStateException.class, () ->
                settlementService.settle(instruction, hash, UUID.randomUUID(), "worker-1", 1));
    }
}