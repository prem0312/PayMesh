package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.ClaimResult;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.validation.Validator;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates the full server-side pipeline for one inbound packet from a
 * bridge node:
 *
 *   1. Hash the ciphertext (SHA-256).
 *   2. Authoritative Database Idempotency Gate (idempotency_records table).
 *      - If not acquired: drop it (duplicate or owned by another worker).
 *   3. Decrypt the ciphertext with the server's private key.
 *      - If decryption fails: mark INVALID with claim token and reject.
 *   4. Check freshness — reject if signedAt is too old (replay protection).
 *   5. Hand off to SettlementService for transactional settlement (which updates
 *      accounts, inserts transaction, and marks idempotency record atomically).
 */
@Service
public class BridgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BridgeIngestionService.class);

    @Autowired private HybridCryptoService crypto;
    @Autowired private IdempotencyService idempotency;
    @Autowired private SettlementService settlement;
    @Autowired private Validator validator;

    @Value("${upi.mesh.packet-max-age-seconds:86400}")
    private long maxAgeSeconds;

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        String packetHash = "?";
        UUID claimToken = null;
        try {
            packetHash = crypto.hashCiphertext(packet.getCiphertext());

            // ---- Authoritative Database Idempotency gate ----
            ClaimResult claimResult = idempotency.tryClaim(packetHash, packet.getPacketId(), bridgeNodeId, hopCount);
            if (!claimResult.isAcquired()) {
                log.info("DUPLICATE packet {} from bridge {} (status={}) — dropped",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...",
                        bridgeNodeId, claimResult.status());
                return IngestResult.duplicate(packetHash);
            }

            claimToken = claimResult.claimToken();

            // ---- Decrypt ----
            PaymentInstruction instruction;
            try {
                instruction = crypto.decrypt(packet.getCiphertext());
            } catch (Exception e) {
                log.warn("Decryption failed for packet {}",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...");
                idempotency.markInvalidWithOwner(packetHash, claimToken, "decryption_failed");
                return IngestResult.invalid(packetHash, "decryption_failed");
            }

            if (instruction == null || !validator.validate(instruction).isEmpty()
                    || instruction.getSenderVpa().equalsIgnoreCase(instruction.getReceiverVpa())) {
                idempotency.markInvalidWithOwner(packetHash, claimToken, "invalid_instruction");
                return IngestResult.invalid(packetHash, "invalid_instruction");
            }

            // ---- Freshness check (replay protection) ----
            long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
            if (ageSeconds > maxAgeSeconds) {
                log.warn("Packet {} too old ({}s), rejected",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...", ageSeconds);
                idempotency.markInvalidWithOwner(packetHash, claimToken, "stale_packet");
                return IngestResult.invalid(packetHash, "stale_packet");
            }
            if (ageSeconds < -300) { // small clock-skew tolerance
                idempotency.markInvalidWithOwner(packetHash, claimToken, "future_dated");
                return IngestResult.invalid(packetHash, "future_dated");
            }

            // ---- Settle (Atomically updates balances, transaction row, and idempotency status) ----
            Transaction tx = settlement.settle(instruction, packetHash, claimToken, bridgeNodeId, hopCount);
            if (tx.getStatus() == Transaction.Status.SETTLED) {
                return IngestResult.settled(packetHash, tx);
            } else {
                return new IngestResult("REJECTED", packetHash, "settlement_rejected", tx.getId());
            }

        } catch (Exception e) {
            log.error("Unexpected packet ingestion failure (packetHash={})", safeHash(packetHash), e);
            if (!"?".equals(packetHash) && claimToken != null) {
                try {
                    idempotency.markFailedWithOwner(packetHash, claimToken, "ingestion_failed");
                } catch (Exception markFailure) {
                    log.error("Could not record failed packet state (packetHash={})",
                            safeHash(packetHash), markFailure);
                }
            }
            return IngestResult.invalid(packetHash, "internal_error");
        }
    }

    private String safeHash(String hash) {
        if (hash == null || "?".equals(hash)) return "unavailable";
        return hash.substring(0, Math.min(12, hash.length())) + "...";
    }

    public record IngestResult(String outcome, String packetHash, String reason, Long transactionId) {
        public static IngestResult settled(String hash, Transaction tx) {
            return new IngestResult("SETTLED", hash, null, tx.getId());
        }
        public static IngestResult duplicate(String hash) {
            return new IngestResult("DUPLICATE_DROPPED", hash, null, null);
        }
        public static IngestResult invalid(String hash, String reason) {
            return new IngestResult("INVALID", hash, reason, null);
        }
    }
}
