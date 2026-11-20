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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paymesh.demo.username=test-demo",
        "paymesh.demo.password=test-demo-password"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class WebSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void dashboardAndAccountAndTransactionDataRequireAuthentication() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/").with(httpBasic("test-demo", "test-demo-password")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/accounts").with(httpBasic("test-demo", "test-demo-password")))
                .andExpect(status().isOk());
    }

    @Test
    void stateChangingApiCallsRequireAuthenticationAndSameOrigin() throws Exception {
        mockMvc.perform(post("/api/transactions")
                .with(httpBasic("test-demo", "test-demo-password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"senderVpa\":\"alice@demo\",\"receiverVpa\":\"bob@demo\",\"amount\":{\"amount\":100.00},\"signature\":\"sig\",\"nonce\":\"nonce1\"}"))
                .andExpect(status().isForbidden());
    }
}
