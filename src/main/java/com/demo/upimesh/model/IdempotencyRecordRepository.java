package com.demo.upimesh.model;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM IdempotencyRecord r WHERE r.packetHash = :hash")
    Optional<IdempotencyRecord> findByPacketHashForUpdate(@Param("hash") String hash);

    @Query("SELECT r FROM IdempotencyRecord r WHERE r.packetHash = :hash")
    Optional<IdempotencyRecord> findByPacketHash(@Param("hash") String hash);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE IdempotencyRecord r SET r.status = :status, r.transactionId = :txId, " +
            "r.completedAt = :completedAt, r.updatedAt = :now " +
            "WHERE r.packetHash = :hash AND r.claimToken = :claimToken AND r.status = :expectedStatus")
    int markSettledWithOwner(@Param("hash") String hash,
                             @Param("claimToken") UUID claimToken,
                             @Param("status") IdempotencyRecord.IdempotencyStatus status,
                             @Param("txId") Long txId,
                             @Param("completedAt") Instant completedAt,
                             @Param("now") Instant now,
                             @Param("expectedStatus") IdempotencyRecord.IdempotencyStatus expectedStatus);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE IdempotencyRecord r SET r.status = :status, r.transactionId = :txId, " +
            "r.completedAt = :completedAt, r.updatedAt = :now " +
            "WHERE r.packetHash = :hash AND r.status = :expectedStatus")
    int markSettled(@Param("hash") String hash,
                    @Param("status") IdempotencyRecord.IdempotencyStatus status,
                    @Param("txId") Long txId,
                    @Param("completedAt") Instant completedAt,
                    @Param("now") Instant now,
                    @Param("expectedStatus") IdempotencyRecord.IdempotencyStatus expectedStatus);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE IdempotencyRecord r SET r.status = :status, r.transactionId = :txId, " +
            "r.failureReason = :reason, r.completedAt = :completedAt, r.updatedAt = :now " +
            "WHERE r.packetHash = :hash AND r.claimToken = :claimToken AND r.status = :expectedStatus")
    int markCompletedWithOwner(@Param("hash") String hash,
                               @Param("claimToken") UUID claimToken,
                               @Param("status") IdempotencyRecord.IdempotencyStatus status,
                               @Param("txId") Long txId,
                               @Param("reason") String reason,
                               @Param("completedAt") Instant completedAt,
                               @Param("now") Instant now,
                               @Param("expectedStatus") IdempotencyRecord.IdempotencyStatus expectedStatus);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE IdempotencyRecord r SET r.status = :status, r.transactionId = :txId, " +
            "r.failureReason = :reason, r.completedAt = :completedAt, r.updatedAt = :now " +
            "WHERE r.packetHash = :hash AND r.status = :expectedStatus")
    int markCompletedWithReason(@Param("hash") String hash,
                                @Param("status") IdempotencyRecord.IdempotencyStatus status,
                                @Param("txId") Long txId,
                                @Param("reason") String reason,
                                @Param("completedAt") Instant completedAt,
                                @Param("now") Instant now,
                                @Param("expectedStatus") IdempotencyRecord.IdempotencyStatus expectedStatus);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE IdempotencyRecord r SET r.claimToken = :newToken, r.bridgeNodeId = :bridgeId, " +
            "r.hopCount = :hops, r.claimedAt = :now, r.updatedAt = :now " +
            "WHERE r.packetHash = :hash AND r.status = :status AND r.claimedAt < :staleCutoff")
    int tryRecoverStaleClaimWithToken(@Param("hash") String hash,
                                     @Param("status") IdempotencyRecord.IdempotencyStatus status,
                                     @Param("staleCutoff") Instant staleCutoff,
                                     @Param("bridgeId") String bridgeId,
                                     @Param("hops") int hops,
                                     @Param("now") Instant now,
                                     @Param("newToken") UUID newToken);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE IdempotencyRecord r SET r.bridgeNodeId = :bridgeId, r.hopCount = :hops, " +
            "r.claimedAt = :now, r.updatedAt = :now WHERE r.packetHash = :hash " +
            "AND r.status = :status AND r.claimedAt < :staleCutoff")
    int tryRecoverStaleClaim(@Param("hash") String hash,
                             @Param("status") IdempotencyRecord.IdempotencyStatus status,
                             @Param("staleCutoff") Instant staleCutoff,
                             @Param("bridgeId") String bridgeId,
                             @Param("hops") int hops,
                             @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE IdempotencyRecord r SET r.status = :newStatus, r.claimToken = :newToken, " +
            "r.bridgeNodeId = :bridgeId, r.hopCount = :hops, r.claimedAt = :now, r.updatedAt = :now, " +
            "r.failureReason = null " +
            "WHERE r.packetHash = :hash AND r.status = :expectedStatus")
    int tryRetryFailedClaimWithToken(@Param("hash") String hash,
                                     @Param("expectedStatus") IdempotencyRecord.IdempotencyStatus expectedStatus,
                                     @Param("newStatus") IdempotencyRecord.IdempotencyStatus newStatus,
                                     @Param("bridgeId") String bridgeId,
                                     @Param("hops") int hops,
                                     @Param("now") Instant now,
                                     @Param("newToken") UUID newToken);
}
