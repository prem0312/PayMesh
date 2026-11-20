package com.demo.upimesh;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paymesh.demo.username=test-demo",
        "paymesh.demo.password=test-demo-password"
})
class ApiValidationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsMissingFieldsNonPositiveAmountsAndExcessPrecision() throws Exception {
        assertInvalidDemoSend("""
                {"senderVpa":"alice@demo","receiverVpa":"bob@demo","amount":0,"pin":"1234"}
                """);
        assertInvalidDemoSend("""
                {"senderVpa":"alice@demo","receiverVpa":"bob@demo","amount":1.001,"pin":"1234"}
                """);
        assertInvalidDemoSend("""
                {"senderVpa":"alice@demo","amount":1.00,"pin":"1234"}
                """);
        assertInvalidDemoSend("""
                {"senderVpa":"alice@demo","receiverVpa":"bob@demo","amount":1.00,"pin":"not-a-pin"}
                """);
    }

    @Test
    void rejectsOutOfRangeMeshPacketsWithoutExposingFrameworkDetails() throws Exception {
        mockMvc.perform(post("/api/bridge/ingest")
                        .with(httpBasic("test-demo", "test-demo-password"))
                        .header("Origin", "http://localhost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"packetId":"packet-1","ttl":11,"createdAt":1700000000000,"ciphertext":"not-ciphertext"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    private void assertInvalidDemoSend(String body) throws Exception {
        mockMvc.perform(post("/api/demo/send")
                        .with(httpBasic("test-demo", "test-demo-password"))
                        .header("Origin", "http://localhost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }
}
