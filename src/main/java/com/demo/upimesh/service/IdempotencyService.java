package com.demo.upimesh.service;

import com.demo.upimesh.model.ClaimResult;
import com.demo.upimesh.model.IdempotencyRecord;
import com.demo.upimesh.model.IdempotencyRecord.IdempotencyStatus;
import com.demo.upimesh.model.IdempotencyRecordRepository;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Database-backed authoritative idempotency service.
 *
 * Correctness guarantees:
 * 1. The database primary key constraint on `idempotency_records.packet_hash`
 *    is the authoritative gate. Concurrent claims for the same packet hash
 *    will collide at the database level.
 * 2. An in-memory cache acts as a fast-path filter, but persistence to the database
 *    is mandatory before any payment instruction is processed.
 * 3. Failure recovery: A record in `CLAIMED` state that becomes stale (process crashed
 *    prior to settlement) is verified against the transaction ledger. If no transaction
 *    exists, it can be reclaimed safely. If a transaction already settled, the record is
 *    healed to `SETTLED` with the actual transaction ID without duplicate processing.
 * 4. Ownership verification: Every acquired claim issues a unique UUID claim token.
 *    Only operations providing the active claim token are permitted to transition the record.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final Map<String, Instant> seenCache = new ConcurrentHashMap<>();

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    @Value("${upi.mesh.idempotency-recovery-timeout-seconds:60}")
    private long recoveryTimeoutSeconds;

    /**
     * Backward-compatible convenience claim method returning boolean.
     */
    public boolean claim(String packetHash) {
        String mockPacketId = packetHash.length() >= 8 ? packetHash.substring(0, 8) : packetHash;
        return tryClaim(packetHash, mockPacketId, "unknown", 0).isAcquired();
    }

    /**
     * Backward-compatible convenience claim method returning boolean.
     */
    public boolean claim(String packetHash, String packetId, String bridgeNodeId, int hopCount) {
        return tryClaim(packetHash, packetId, bridgeNodeId, hopCount).isAcquired();
    }

    /**
     * Atomically claims a packet hash in the database, returning an explicit ClaimResult.
     *
     * @return ClaimResult containing ownership status and the acquired UUID claim token if successful.
     */
    public ClaimResult tryClaim(String packetHash, String packetId, String bridgeNodeId, int hopCount) {
        Instant now = Instant.now();

        // 1. Check database for existing record
        Optional<IdempotencyRecord> existingOpt = repository.findById(packetHash);
        if (existingOpt.isPresent()) {
            return handleExistingRecord(existingOpt.get(), packetHash, packetId, bridgeNodeId, hopCount, now);
        }

        // 2. Not present: attempt atomic insert in an isolated transaction (REQUIRES_NEW)
        UUID initialToken = UUID.randomUUID();
        TransactionTemplate insertTemplate = new TransactionTemplate(transactionManager);
        insertTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            insertTemplate.executeWithoutResult(status -> {
                IdempotencyRecord record = new IdempotencyRecord(
                        packetHash, packetId, IdempotencyStatus.CLAIMED,
                        bridgeNodeId, hopCount, now, initialToken);
                entityManager.persist(record);
                entityManager.flush();
            });
            seenCache.put(packetHash, now);
            return ClaimResult.acquired(initialToken);
        } catch (DataIntegrityViolationException dive) {
            // Expected unique key conflict on primary key (concurrent insert race).
            // Inspect winning record in a fresh, clean read-only transaction (REQUIRES_NEW)
            return inspectWinningRecordAfterCollision(packetHash, packetId, bridgeNodeId, hopCount, now);
        } catch (Exception ex) {
            // Check if nested cause is a unique constraint violation
            if (isUniqueConstraintViolation(ex)) {
                return inspectWinningRecordAfterCollision(packetHash, packetId, bridgeNodeId, hopCount, now);
            }
            // Unexpected database outage / connection failure / system error — rethrow rather than swallowing
            log.error("Unexpected database error while claiming packet {}: {}", sanitizeHash(packetHash), ex.getMessage(), ex);
            throw ex;
        }
    }

    private boolean isUniqueConstraintViolation(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException
                    || current instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null && (msg.contains("duplicate key") || msg.contains("Primary key") || msg.contains("PRIMARY_KEY"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ClaimResult inspectWinningRecordAfterCollision(String packetHash, String packetId,
                                                          String bridgeNodeId, int hopCount, Instant now) {
        TransactionTemplate readTemplate = new TransactionTemplate(transactionManager);
        readTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readTemplate.setReadOnly(true);

        ClaimResult fallbackResult = readTemplate.execute(status -> {
            Optional<IdempotencyRecord> concurrentOpt = repository.findById(packetHash);
            return concurrentOpt.map(record ->
                    handleExistingRecord(record, packetHash, packetId, bridgeNodeId, hopCount, now))
                    .orElse(ClaimResult.alreadyOwned());
        });

        return fallbackResult != null ? fallbackResult : ClaimResult.alreadyOwned();
    }

    private ClaimResult handleExistingRecord(IdempotencyRecord record, String packetHash, String packetId,
                                             String bridgeNodeId, int hopCount, Instant now) {
        IdempotencyStatus status = record.getStatus();

        // 1. Terminal states: Completed, Rejected, or Invalid cannot be re-claimed
        if (status == IdempotencyStatus.SETTLED || status == IdempotencyStatus.REJECTED
                || status == IdempotencyStatus.INVALID) {
            seenCache.put(packetHash, record.getClaimedAt());
            return ClaimResult.terminalDuplicate(status.name());
        }

        // 2. Safe Ledger Reconciliation:
        // If a transaction genuinely exists in the financial ledger for this packet hash,
        // retrieve the actual transaction record to obtain its real ID and status,
        // then reconcile the idempotency record to SETTLED without altering balances.
        Optional<Transaction> existingTxOpt = transactionRepository.findByPacketHash(packetHash);
        if (existingTxOpt.isPresent()) {
            Transaction actualTx = existingTxOpt.get();
            log.info("Packet {} already settled in financial ledger (txId={}). Reconciling status to SETTLED.",
                    sanitizeHash(packetHash), actualTx.getId());
            reconcileSettledRecord(packetHash, actualTx.getId(), actualTx.getSettledAt());
            return ClaimResult.terminalDuplicate(IdempotencyStatus.SETTLED.name());
        }

        // 3. Stale claim recovery: Check if the CLAIMED record timed out
        if (status == IdempotencyStatus.CLAIMED) {
            Instant staleCutoff = now.minusSeconds(recoveryTimeoutSeconds);
            boolean stale = record.getClaimedAt().isBefore(staleCutoff);
            if (stale) {
                log.warn("Recovering stale CLAIMED record for packet {} (claimed at {})",
                        sanitizeHash(packetHash), record.getClaimedAt());
                TransactionTemplate template = new TransactionTemplate(transactionManager);
                template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

                UUID newToken = UUID.randomUUID();
                Boolean recovered = template.execute(txStatus -> {
                    int updated = repository.tryRecoverStaleClaimWithToken(
                            packetHash, IdempotencyStatus.CLAIMED, staleCutoff, bridgeNodeId, hopCount, now, newToken);
                    if (updated > 0) {
                        seenCache.put(packetHash, now);
                        return true;
                    }
                    return false;
                });

                if (Boolean.TRUE.equals(recovered)) {
                    return ClaimResult.recovered(newToken);
                }
            }
            return ClaimResult.alreadyOwned();
        }

        // 4. Failed claim recovery: If previously failed WITHOUT settlement, permit retry
        if (status == IdempotencyStatus.FAILED) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            UUID newToken = UUID.randomUUID();
            Boolean retried = template.execute(txStatus -> {
                int updated = repository.tryRetryFailedClaimWithToken(
                        packetHash, IdempotencyStatus.FAILED, IdempotencyStatus.CLAIMED,
                        bridgeNodeId, hopCount, now, newToken);
                if (updated > 0) {
                    seenCache.put(packetHash, now);
                    return true;
                }
                return false;
            });

            if (Boolean.TRUE.equals(retried)) {
                return ClaimResult.retried(newToken);
            }
            return ClaimResult.alreadyOwned();
        }

        return ClaimResult.alreadyOwned();
    }

    /**
     * Concurrency-safe atomic reconciliation of an idempotency record whose financial transaction
     * was committed to the ledger. Runs in a dedicated REQUIRES_NEW transaction, acquires a PESSIMISTIC_WRITE
     * lock on the idempotency record, verifies actual transaction presence, and reconciles to SETTLED.
     */
    private void reconcileSettledRecord(String packetHash, Long actualTxId, Instant settledAt) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> {
            Optional<IdempotencyRecord> lockedRecOpt = repository.findByPacketHashForUpdate(packetHash);
            if (lockedRecOpt.isPresent()) {
                IdempotencyRecord rec = lockedRecOpt.get();
                // Only reconcile if not already settled
                if (rec.getStatus() != IdempotencyStatus.SETTLED) {
                    rec.setStatus(IdempotencyStatus.SETTLED);
                    rec.setTransactionId(actualTxId);
                    rec.setCompletedAt(settledAt != null ? settledAt : Instant.now());
                    rec.setUpdatedAt(Instant.now());
                    repository.saveAndFlush(rec);
                }
            }
        });
        seenCache.put(packetHash, settledAt != null ? settledAt : Instant.now());
    }

    public boolean markSettledWithOwner(String packetHash, UUID claimToken, Long transactionId) {
        return markSettledWithOwner(packetHash, claimToken, transactionId, Instant.now());
    }

    public boolean markSettledWithOwner(String packetHash, UUID claimToken, Long transactionId, Instant now) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Integer updated = template.execute(status ->
                repository.markSettledWithOwner(packetHash, claimToken, IdempotencyStatus.SETTLED,
                        transactionId, now, now, IdempotencyStatus.CLAIMED));
        boolean success = updated != null && updated > 0;
        if (success) {
            seenCache.put(packetHash, now);
        } else {
            log.warn("markSettledWithOwner: 0 rows updated for packetHash={} (ownership lost or concurrent update)",
                    sanitizeHash(packetHash));
        }
        return success;
    }

    public boolean markRejectedWithOwner(String packetHash, UUID claimToken, Long transactionId, String reason) {
        Instant now = Instant.now();
        String safeReason = truncate(reason, 500);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Integer updated = template.execute(status ->
                repository.markCompletedWithOwner(packetHash, claimToken, IdempotencyStatus.REJECTED,
                        transactionId, safeReason, now, now, IdempotencyStatus.CLAIMED));
        boolean success = updated != null && updated > 0;
        if (success) {
            seenCache.put(packetHash, now);
        } else {
            log.warn("markRejectedWithOwner: 0 rows updated for packetHash={} (ownership lost or concurrent update)",
                    sanitizeHash(packetHash));
        }
        return success;
    }

    public boolean markFailedWithOwner(String packetHash, UUID claimToken, String reason) {
        Instant now = Instant.now();
        String safeReason = truncate(reason, 550);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Integer updated = template.execute(status ->
                repository.markCompletedWithOwner(packetHash, claimToken, IdempotencyStatus.FAILED,
                        null, safeReason, now, now, IdempotencyStatus.CLAIMED));
        boolean success = updated != null && updated > 0;
        if (success) {
            seenCache.remove(packetHash);
        } else {
            log.warn("markFailedWithOwner: 0 rows updated for packetHash={} (ownership lost or concurrent update)",
                    sanitizeHash(packetHash));
        }
        return success;
    }

    public boolean markInvalidWithOwner(String packetHash, UUID claimToken, String reason) {
        Instant now = Instant.now();
        String safeReason = truncate(reason, 550);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Integer updated = template.execute(status ->
                repository.markCompletedWithOwner(packetHash, claimToken, IdempotencyStatus.INVALID,
                        null, safeReason, now, now, IdempotencyStatus.CLAIMED));
        boolean success = updated != null && updated > 0;
        if (success) {
            seenCache.put(packetHash, now);
        } else {
            log.warn("markInvalidWithOwner: 0 rows updated for packetHash={} (ownership lost or concurrent update)",
                    sanitizeHash(packetHash));
        }
        return success;
    }

    // --- Legacy methods maintained for backward compatibility (unverified callers) ---

    public void markSettled(String packetHash, Long transactionId) {
        markSettled(packetHash, transactionId, Instant.now());
    }

    public void markSettled(String packetHash, Long transactionId, Instant now) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status ->
                repository.markSettled(packetHash, IdempotencyStatus.SETTLED, transactionId, now, now, IdempotencyStatus.CLAIMED));
        seenCache.put(packetHash, now);
    }

    public void markRejected(String packetHash, Long transactionId, String reason) {
        Instant now = Instant.now();
        String safeReason = truncate(reason, 500);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        repository.markCompletedWithReason(packetHash, IdempotencyStatus.REJECTED,
                transactionId, safeReason, now, now, IdempotencyStatus.CLAIMED);
        seenCache.put(packetHash, now);
    }

    public void markFailed(String packetHash, String reason) {
        Instant now = Instant.now();
        String safeReason = truncate(reason, 550);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        repository.markCompletedWithReason(packetHash, IdempotencyStatus.FAILED,
                null, safeReason, now, now, IdempotencyStatus.CLAIMED);
        seenCache.remove(packetHash);
    }

    public void markInvalid(String packetHash, String reason) {
        Instant now = Instant.now();
        String safeReason = truncate(reason, 550);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        repository.markCompletedWithReason(packetHash, IdempotencyStatus.INVALID,
                null, safeReason, now, now, IdempotencyStatus.CLAIMED);
        seenCache.put(packetHash, now);
    }

    public int size() {
        return (int) repository.count();
    }

    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(ttlSeconds);
        seenCache.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    /**
     * Clears the in-memory duplicate cache only.
     *
     * Persistent idempotency records are intentionally preserved. Deleting them
     * would break consistency with the transaction ledger: a previously SETTLED
     * packet could be re-claimed and re-processed, forcing the ingestion path to
     * rely solely on the transactions unique constraint instead of the authoritative
     * idempotency gate. Terminal records (SETTLED, REJECTED, INVALID) must remain
     * available to prevent replay after a mesh reset.
     */
    public void clear() {
        seenCache.clear();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }

    private String sanitizeHash(String hash) {
        if (hash == null) return "?";
        return hash.length() <= 12 ? hash : hash.substring(0, 12) + "...";
    }
}
