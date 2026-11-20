package com.demo.upimesh.service;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.IdempotencyRecord;
import com.demo.upimesh.model.IdempotencyRecord.IdempotencyStatus;
import com.demo.upimesh.model.IdempotencyRecordRepository;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Executes atomic ledger updates and idempotency completion.
 *
 * Atomicity Guarantees:
 * Both the financial update (account balances, transaction row) and the idempotency
 * terminal transition (status, completion timestamp, transaction ID) execute inside
 * a single database transaction under a PESSIMISTIC_WRITE row lock on the idempotency record.
 *
 * If the worker's claimToken has expired or was recovered by another worker, the settlement
 * transaction fails fast with IllegalStateException, ensuring zero balance movements or transactions.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;

    @Transactional
    public Transaction settle(PaymentInstruction instruction, String packetHash,
                              UUID claimToken, String bridgeNodeId, int hopCount) {

        validateSettlementFields(instruction);

        Instant now = Instant.now();
        String safeHash = packetHash.substring(0, Math.min(12, packetHash.length())) + "...";

        // 1. Acquire pessimistic write lock on the idempotency record
        IdempotencyRecord record = idempotencyRepository.findByPacketHashForUpdate(packetHash)
                .orElseThrow(() -> new IllegalStateException("Idempotency record not found for packet: " + safeHash));

        // 2. Validate claim ownership and active state while holding the lock
        if (record.getStatus() != IdempotencyStatus.CLAIMED) {
            throw new IllegalStateException("Cannot settle packet " + safeHash +
                    ": expected status CLAIMED but found " + record.getStatus());
        }
        if (claimToken == null || !claimToken.equals(record.getClaimToken())) {
            log.warn("Ownership mismatch for packet {}: invalid claim token", safeHash);
            throw new IllegalStateException("Ownership mismatch for packet " + safeHash + ": claim token unauthorized");
        }

        // 3. Validate accounts and payment amount
        Account sender = accounts.findById(instruction.getSenderVpa())
                .orElseThrow(() -> new IllegalArgumentException("Unknown sender VPA: " + instruction.getSenderVpa()));

        Account receiver = accounts.findById(instruction.getReceiverVpa())
                .orElseThrow(() -> new IllegalArgumentException("Unknown receiver VPA: " + instruction.getReceiverVpa()));

        BigDecimal amount = instruction.getAmount();

        // 4. Insufficient balance: record REJECTED atomically
        if (sender.getBalance().compareTo(amount) < 0) {
            log.warn("Insufficient balance: {} has ₹{}, tried to send ₹{}",
                    sender.getVpa(), sender.getBalance(), amount);

            Transaction rejectedTx = new Transaction();
            rejectedTx.setPacketHash(packetHash);
            rejectedTx.setSenderVpa(instruction.getSenderVpa());
            rejectedTx.setReceiverVpa(instruction.getReceiverVpa());
            rejectedTx.setAmount(amount);
            rejectedTx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
            rejectedTx.setSettledAt(now);
            rejectedTx.setBridgeNodeId(bridgeNodeId);
            rejectedTx.setHopCount(hopCount);
            rejectedTx.setStatus(Transaction.Status.REJECTED);
            rejectedTx = transactions.save(rejectedTx);

            record.setStatus(IdempotencyStatus.REJECTED);
            record.setTransactionId(rejectedTx.getId());
            record.setFailureReason("insufficient_funds");
            record.setCompletedAt(now);
            record.setUpdatedAt(now);
            idempotencyRepository.save(record);

            return rejectedTx;
        }

        // 5. Sufficient balance: update accounts and record SETTLED atomically
        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));
        accounts.save(sender);
        accounts.save(receiver);

        Transaction settledTx = new Transaction();
        settledTx.setPacketHash(packetHash);
        settledTx.setSenderVpa(instruction.getSenderVpa());
        settledTx.setReceiverVpa(instruction.getReceiverVpa());
        settledTx.setAmount(amount);
        settledTx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
        settledTx.setSettledAt(now);
        settledTx.setBridgeNodeId(bridgeNodeId);
        settledTx.setHopCount(hopCount);
        settledTx.setStatus(Transaction.Status.SETTLED);
        settledTx = transactions.save(settledTx);

        record.setStatus(IdempotencyStatus.SETTLED);
        record.setTransactionId(settledTx.getId());
        record.setCompletedAt(now);
        record.setUpdatedAt(now);
        idempotencyRepository.save(record);

        log.info("SETTLED ₹{} from {} to {} (packetHash={}, bridge={}, hops={}, txId={})",
                amount, sender.getVpa(), receiver.getVpa(),
                safeHash, bridgeNodeId, hopCount, settledTx.getId());

        return settledTx;
    }

    private void validateSettlementFields(PaymentInstruction instruction) {
        if (instruction == null
                || instruction.getSenderVpa() == null || instruction.getSenderVpa().isBlank()
                || instruction.getReceiverVpa() == null || instruction.getReceiverVpa().isBlank()
                || instruction.getSignedAt() == null || instruction.getSignedAt() <= 0) {
            throw new IllegalArgumentException("Payment instruction is invalid");
        }
        if (instruction.getSenderVpa().equalsIgnoreCase(instruction.getReceiverVpa())) {
            throw new IllegalArgumentException("Sender and receiver must differ");
        }

        BigDecimal amount = instruction.getAmount();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2
                || amount.precision() - amount.scale() > 17) {
            throw new IllegalArgumentException("Amount is outside the supported range");
        }
    }
}
