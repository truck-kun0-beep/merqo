package com.merqo.demo;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.merqo.common.web.CsrfTestSupport;

/** Verifies no demo contributors or simulator endpoints run when {@code merqo.demo-mode=false}. */
@SpringBootTest(properties = "merqo.demo-mode=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoDisabledIntegrationTest {

    @Autowired private ObjectProvider<DemoOrdersContributor> demoOrdersContributorProvider;

    @Autowired
    private ObjectProvider<DemoOrdersPaymentsContributor> demoPaymentsContributorProvider;

    @Autowired private ObjectProvider<DemoSeeder> demoSeederProvider;
    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("DEMO_MODE=false disables DemoContributors, DemoSeeder, and demo endpoints")
    void demoDisabledSeedsNothing() throws Exception {
        assertNull(demoOrdersContributorProvider.getIfAvailable());
        assertNull(demoPaymentsContributorProvider.getIfAvailable());
        assertNull(demoSeederProvider.getIfAvailable());
        mockMvc.perform(get("/api/v1/demo/accounts")).andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/api/v1/demo/simulate-payment")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"method\":\"BKASH\",\"reference\":\"SIMTEST1\",\"amount\":100}"))
                .andExpect(status().isUnauthorized());
    }
}
