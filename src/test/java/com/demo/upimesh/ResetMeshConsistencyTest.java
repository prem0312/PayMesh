package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.MeshSimulatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the "Reset Mesh + Cache" behavior fix.
 * Proves that resetting the mesh does NOT delete persistent idempotency records
 * and that replaying a settled packet after reset is correctly handled as a duplicate.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class ResetMeshConsistencyTest {

    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private IdempotencyService idempotency;
    @Autowired private MeshSimulatorService mesh;
    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;
    @Autowired private HybridCryptoService crypto;
    @Autowired private TestFixtures fixtures;

    @BeforeEach
    void setup() {
        fixtures.resetAll();
    }

    @Test
    void settledPaymentRemainsProtectedAfterReset() throws Exception {
        // 1. Create and settle a packet
        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("500.00"), "1234", 5);
        String hash = crypto.hashCiphertext(packet.getCiphertext());

        BridgeIngestionService.IngestResult r1 = bridge.ingest(packet, "bridge-1", 1);
        assertEquals("SETTLED", r1.outcome());

        BigDecimal aliceBalanceAfterSettle = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBalanceAfterSettle = accounts.findById("bob@demo").orElseThrow().getBalance();
        long txCountAfterSettle = transactions.count();

        // 2. Reset mesh consistency (clears in-memory state but preserves DB)
        mesh.resetMesh();

        // 3. Attempt to settle the same packet again after reset
        BridgeIngestionService.IngestResult r2 = bridge.ingest(packet, "bridge-2", 1);
        assertEquals("DUPLICATE_DROPPED", r2.outcome(), "Replaying settled packet after reset must be DUPLICATE_DROPPED");

        // 4. Balances must remain unchanged
        BigDecimal aliceBalanceAfterReset = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBalanceAfterReset = accounts.findById("bob@demo").orElseThrow().getBalance();
        assertEquals(aliceBalanceAfterSettle, aliceBalanceAfterReset, "Alice balance must be unchanged after reset");
        assertEquals(bobBalanceAfterSettle, bobBalanceAfterReset, "Bob balance must be unchanged after reset");

        // 5. Only one transaction must exist despite the reset + retry
        long txCountAfterReset = transactions.count();
        assertEquals(txCountAfterSettle, txCountAfterReset, "Transaction count must not increase after reset");
    }
}