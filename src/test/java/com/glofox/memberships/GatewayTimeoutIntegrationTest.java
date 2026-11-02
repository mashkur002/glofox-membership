package com.glofox.memberships;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glofox.memberships.payment.PaymentGateway;
import com.glofox.memberships.payment.PaymentGateway.PaymentResult;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the timeout is actually wired between the service and the gateway
 * bean: a hanging gateway becomes a prompt 502, and the promo slot comes back.
 */
@SpringBootTest(properties = "memberships.payment.timeout=200ms")
@AutoConfigureMockMvc
@WithMockUser(roles = "USER")
class GatewayTimeoutIntegrationTest {

    private static final String PROMO_ID = "33333333-3333-3333-3333-333333333333";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentGateway gateway;

    @Test
    @DisplayName("a hanging gateway yields 502 payment_gateway_error within the timeout, and the slot is restored")
    void hangingGatewayTimesOut() throws Exception {
        given(gateway.charge(anyInt(), anyString())).willAnswer(invocation -> {
            Thread.sleep(3_000);
            return new PaymentResult("txn-too-late");
        });

        long start = System.nanoTime();
        mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json")
                        .content("""
                                {"member_name":"Slow Sam","plan_id":"%s","start_date":"%s"}
                                """.formatted(PROMO_ID, LocalDate.now().plusDays(30))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("payment_gateway_error"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).as("bounded by the 200ms timeout, not the 3s hang")
                .isLessThan(2_000);
        mvc.perform(get("/v1/plans"))
                .andExpect(jsonPath("$[?(@.id == '%s')].remaining_slots".formatted(PROMO_ID))
                        .value(5));
    }
}
