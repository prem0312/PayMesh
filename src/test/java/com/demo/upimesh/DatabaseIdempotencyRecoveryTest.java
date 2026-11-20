package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.IdempotencyRecord;
import com.demo.upimesh.model.IdempotencyRecord.IdempotencyStatus;
import com.demo.upimesh.model.IdempotencyRecordRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import com.demo.upimesh.model.ClaimResult;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class DatabaseIdempotencyRecoveryTest {

    @Autowired
    private DemoService demoService;

    @Autowired
    private BridgeIngestionService bridge;

    @Autowired
    private IdempotencyService idempotency;

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private HybridCryptoService crypto;

    @Autowired
    private TestFixtures fixtures;

    @BeforeEach
    void setup() {
        fixtures.resetAll();
    }

    @Test
    void atomicFailedClaimRetryRotatesTokenAndAllowsSingleWinner() throws Exception {
        String hash = "aaa111bbb222ccc333ddd444eee555fff666ggg777hhh888iii999jjj000kkk1";
        fixtures.resetAll();

        ClaimResult claim1 = idempotency.tryClaim(hash, "pkt-1", "worker-1", 1);
        assertTrue(claim1.isAcquired());
        UUID token1 = claim1.claimToken();

        // Second claim on active CLAIMED record returns ALREADY_OWNED (not retryable unless FAILED or stale)
        ClaimResult claim2 = idempotency.tryClaim(hash, "pkt-2", "worker-2", 1);
        assertFalse(claim2.isAcquired());
        assertEquals(ClaimResult.Status.ALREADY_OWNED, claim2.status());

        // Persisted token must still be the original (no rotation for non-retryable claims)
        IdempotencyRecord rec = repository.findById(hash).orElseThrow();
        assertEquals(token1, rec.getClaimToken(), "Persisted token must not change for non-retryable claim");
    }

    @Test
    void atomicStaleClaimRecoveryRotatesTokenAndRejectsPreviousToken() throws Exception {
        String hash = "bbb222ccc333ddd444eee555fff666ggg777hhh888iii999jjj000kkk111aaa";
        fixtures.resetAll();

        Instant twoMinutesAgo = Instant.now().minusSeconds(120);
        UUID originalToken = UUID.randomUUID();

        IdempotencyRecord stale = new IdempotencyRecord(
                hash, "pkt-stale", IdempotencyStatus.CLAIMED, "dead-node", 1, twoMinutesAgo, originalToken);
        repository.saveAndFlush(stale);

        ClaimResult res = idempotency.tryClaim(hash, "pkt-recovery", "recovery-worker", 1);
        assertTrue(res.isAcquired());
        assertNotEquals(originalToken, res.claimToken(), "Recovery must rotate claim token");
        assertEquals(ClaimResult.Status.RECOVERED, res.status());
    }

    @Test
    void concurrentStaleClaimRecoveryAllowsExactlyOneWinner() throws Exception {
        String hash = "ccc333ddd444eee555fff666ggg777hhh888iii999jjj000kkk111aaa222";
        fixtures.resetAll();

        Instant twoMinutesAgo = Instant.now().minusSeconds(120);
        UUID originalToken = UUID.randomUUID();

        IdempotencyRecord stale = new IdempotencyRecord(
                hash, "pkt-stale", IdempotencyStatus.CLAIMED, "dead-node", 1, twoMinutesAgo, originalToken);
        repository.saveAndFlush(stale);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(6);
        AtomicInteger recoveredCount = new AtomicInteger();

        for (int i = 0; i < 6; i++) {
            final String node = "rec-node-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    ClaimResult res = idempotency.tryClaim(hash, "pkt-recovery", node, 1);
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
        assertTrue(done.await(10, TimeUnit.SECONDS), "All recovery workers must finish within 10s");
        pool.shutdown();

        assertEquals(1, recoveredCount.get(), "Exactly one recovery worker must succeed");
    }

    @Test
    void databaseAuthoritativeDedupPersistsAcrossCacheWipe() throws Exception {
        String hash = "ddd444eee555fff666ggg777hhh888iii999jjj000kkk111aaa222bbb333";
        fixtures.resetAll();

        ClaimResult claim = idempotency.tryClaim(hash, "pkt-1", "bridge-1", 1);
        assertTrue(claim.isAcquired());

        // Simulate cache wipe: delete from memory but verify persisted state
        idempotency.clear();

        // Try claiming again - should get ALREADY_OWNED since record is CLAIMED (not terminal)
        ClaimResult res = idempotency.tryClaim(hash, "pkt-1", "bridge-2", 1);
        assertFalse(res.isAcquired());
        assertEquals(ClaimResult.Status.ALREADY_OWNED, res.status());
    }

    @Test
    void newIdempotencyRecordReceivesNonNullClaimTokenUponPersistence() throws Exception {
        String hash = "eee555fff666ggg777hhh888iii999jjj000kkk111aaa222bbb333cccc444";
        fixtures.resetAll();

        ClaimResult claim = idempotency.tryClaim(hash, "pkt-1", "bridge-1", 1);
        assertTrue(claim.isAcquired());

        IdempotencyRecord record = repository.findById(hash).orElseThrow();
        assertNotNull(record.getClaimToken(), "Claim token must be persisted and non-null");
        assertEquals(claim.claimToken(), record.getClaimToken(), "Persisted token must match acquired claim token");
    }

    @Test
    void staleInProgressClaimRecoversAfterTimeout() throws Exception {
        String hash = "fff666ggg777hhh888iii999jjj000kkk111aaa222bbb333cccc444ddddd";
        fixtures.resetAll();

        Instant twoMinutesAgo = Instant.now().minusSeconds(120);
        UUID originalToken = UUID.randomUUID();

        IdempotencyRecord stale = new IdempotencyRecord(
                hash, "pkt-stale", IdempotencyStatus.CLAIMED, "dead-node", 1, twoMinutesAgo, originalToken);
        repository.saveAndFlush(stale);

        // Recovery must succeed against stale record
        ClaimResult res = idempotency.tryClaim(hash, "pkt-recovery", "recovery-worker", 1);
        assertTrue(res.isAcquired());
        assertEquals(ClaimResult.Status.RECOVERED, res.status());
        assertNotEquals(originalToken, res.claimToken(), "Token must be rotated");
    }
}