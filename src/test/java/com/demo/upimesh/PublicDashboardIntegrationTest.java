package com.demo.upimesh;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "paymesh.rsa.require-persistent-key=false",
        "paymesh.demo.writes-enabled=true",
        "paymesh.demo.username=test-demo",
        "paymesh.demo.password=test-demo-password"
})
@ActiveProfiles({"production", "test"})
@AutoConfigureMockMvc
class PublicDashboardIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;

    @Test
    void anonymousRecruitersCanOpenTheReadOnlyDashboard() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("simulated payment and settlement platform")))
                .andExpect(content().string(containsString("id=\"read-only-notice\"")))
                .andExpect(content().string(containsString("disabled=\"disabled\"")))
                .andExpect(content().string(containsString("https://github.com/prem0312/PayMesh")));
    }

    @Test
    @Transactional
    void anonymousSnapshotContainsOnlyApprovedDemoRecordsAndFields() throws Exception {
        accounts.save(new Account("outside@demo", "Outside account", BigDecimal.ONE));
        transactions.save(settlement("1".repeat(64), "alice@demo", "phone-bridge"));
        transactions.save(settlement("2".repeat(64), "outside@demo", "phone-bridge"));
        transactions.save(settlement("3".repeat(64), "alice@demo", "outside-bridge"));

        mockMvc.perform(get("/api/demo/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices.length()").value(5))
                .andExpect(jsonPath("$.devices[0].packetIds").doesNotExist())
                .andExpect(jsonPath("$.accounts.length()").value(4))
                .andExpect(jsonPath("$.accounts[0].version").doesNotExist())
                .andExpect(jsonPath("$.transactions.length()").value(1))
                .andExpect(jsonPath("$.transactions[0].senderVpa").value("alice@demo"))
                .andExpect(jsonPath("$.transactions[0].packetHash").doesNotExist())
                .andExpect(jsonPath("$.idempotencyCacheSize").doesNotExist());
    }

    @Test
    void detailedDataAndDocumentationStillRequireAuthentication() throws Exception {
        for (String path : new String[]{"/api/accounts", "/api/transactions", "/api/mesh/state",
                "/api/server-key", "/v3/api-docs", "/swagger-ui/index.html"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/accounts").with(httpBasic("test-demo", "test-demo-password")))
                .andExpect(status().isOk());
    }

    @Test
    void publicModeBlocksAllWriteRoutesEvenWithCredentialsAndSameOrigin() throws Exception {
        for (String path : new String[]{"/api/demo/send", "/api/bridge/ingest", "/api/mesh/gossip",
                "/api/mesh/flush", "/api/mesh/reset", "/api/demo/dashboard"}) {
            mockMvc.perform(post(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(post(path).header("Origin", "http://localhost")
                            .with(httpBasic("test-demo", "test-demo-password")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("demo_writes_disabled"));
        }
    }

    private Transaction settlement(String hash, String sender, String bridge) {
        Transaction transaction = new Transaction();
        transaction.setPacketHash(hash);
        transaction.setSenderVpa(sender);
        transaction.setReceiverVpa("bob@demo");
        transaction.setAmount(BigDecimal.ONE);
        transaction.setSignedAt(Instant.EPOCH);
        transaction.setSettledAt(Instant.EPOCH);
        transaction.setBridgeNodeId(bridge);
        transaction.setHopCount(1);
        transaction.setStatus(Transaction.Status.SETTLED);
        return transaction;
    }
}
