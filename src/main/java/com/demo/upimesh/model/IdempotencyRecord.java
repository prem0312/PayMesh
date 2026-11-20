package com.demo.upimesh.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Authoritative, persistent idempotency record.
 * Primary key on packet_hash guarantees that even under extreme concurrency
 * across multiple threads or bridge nodes, duplicate processing is prevented
 * at the database level.
 */
@Entity
@Table(name = "idempotency_records",
        indexes = {
                @Index(name = "idx_idempotency_claimed_at", columnList = "claimed_at"),
                @Index(name = "idx_idempotency_status", columnList = "status")
        })
public class IdempotencyRecord {

    @Id
    @Column(name = "packet_hash", length = 64, nullable = false)
    private String packetHash;

    @Column(name = "claim_token", nullable = false)
    private UUID claimToken;

    @Column(name = "packet_id", length = 64, nullable = false)
    private String packetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private IdempotencyStatus status;

    @Column(name = "bridge_node_id", length = 100)
    private String bridgeNodeId;

    @Column(name = "hop_count", nullable = false)
    private int hopCount;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public enum IdempotencyStatus {
        CLAIMED,
        SETTLED,
        REJECTED,
        FAILED,
        INVALID
    }

    public IdempotencyRecord() {
        this.claimToken = UUID.randomUUID();
    }

    public IdempotencyRecord(String packetHash, String packetId, IdempotencyStatus status,
                             String bridgeNodeId, int hopCount, Instant now) {
        this(packetHash, packetId, status, bridgeNodeId, hopCount, now, UUID.randomUUID());
    }

    public IdempotencyRecord(String packetHash, String packetId, IdempotencyStatus status,
                             String bridgeNodeId, int hopCount, Instant now, UUID claimToken) {
        this.packetHash = packetHash;
        this.packetId = packetId;
        this.status = status;
        this.bridgeNodeId = bridgeNodeId;
        this.hopCount = hopCount;
        this.claimedAt = now;
        this.updatedAt = now;
        this.claimToken = (claimToken != null) ? claimToken : UUID.randomUUID();
    }

    @PrePersist
    protected void onPrePersist() {
        if (this.claimToken == null) {
            this.claimToken = UUID.randomUUID();
        }
    }

    /**
     * Rotates the claim token with a newly generated UUID.
     * To be called only upon atomic re-claims, stale recoveries, or retries.
     */
    public void rotateClaimToken() {
        this.claimToken = UUID.randomUUID();
    }

    public String getPacketHash() { return packetHash; }
    public void setPacketHash(String packetHash) { this.packetHash = packetHash; }

    public UUID getClaimToken() { return claimToken; }
    public void setClaimToken(UUID claimToken) { this.claimToken = claimToken; }

    public String getPacketId() { return packetId; }
    public void setPacketId(String packetId) { this.packetId = packetId; }

    public IdempotencyStatus getStatus() { return status; }
    public void setStatus(IdempotencyStatus status) { this.status = status; }

    public String getBridgeNodeId() { return bridgeNodeId; }
    public void setBridgeNodeId(String bridgeNodeId) { this.bridgeNodeId = bridgeNodeId; }

    public int getHopCount() { return hopCount; }
    public void setHopCount(int hopCount) { this.hopCount = hopCount; }

    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
