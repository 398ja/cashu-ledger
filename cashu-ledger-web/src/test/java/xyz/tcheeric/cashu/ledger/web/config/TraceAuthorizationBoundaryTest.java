package xyz.tcheeric.cashu.ledger.web.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import xyz.tcheeric.cashu.ledger.web.CashuLedgerWebApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The trace boundary must be stated in the filter chain, not only inside handlers
 * (AppSec finding M-1, issue #8).
 *
 * <h2>What was wrong, and what was not</h2>
 *
 * <p>The chain was {@code anyRequest().permitAll()}, so every access decision lived inside a
 * handler: {@code TraceAdminController} calls {@code requireAdmin}, {@code TraceController}
 * throws from its {@code principal()} helper when the request attribute is missing. Both fail
 * closed, and {@code Nip98AuthenticationFilter} already rejects unauthenticated requests to
 * {@code /api/v1/trace} before a controller sees them — so this was never an open door, and the
 * finding was downgraded to Low once that was measured.
 *
 * <p>What was missing is that the boundary was not written where authorization is configured. It
 * lived in a filter's path prefix and in per-handler throws, so an admin route added under a
 * different prefix — or the prefix being renamed — would be unauthenticated with nothing failing
 * to compile or to test.
 *
 * <p>These tests are that missing check. They assert the outcome a caller observes, so they hold
 * whether the decision is made by the chain, the filter, or a handler.
 */
@SpringBootTest(classes = CashuLedgerWebApplication.class)
@AutoConfigureMockMvc
class TraceAuthorizationBoundaryTest {

    @Autowired
    private MockMvc mockMvc;

    /** An unauthenticated caller must not reach the trace admin surface. */
    @Test
    void traceAdminRejectsAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(get("/api/v1/trace/admin/redaction-keys"))
                .andExpect(status().is4xxClientError());
    }

    /** Nor the trace read surface. */
    @Test
    void traceReadRejectsAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(get("/api/v1/trace/events"))
                .andExpect(status().is4xxClientError());
    }

    /**
     * A route nobody has classified must be denied rather than served.
     *
     * <p>This is the property {@code anyRequest().permitAll()} could not give: the previous
     * configuration's answer to "an endpoint we forgot about" was to serve it.
     */
    @Test
    void anUnclassifiedRouteIsDeniedRatherThanServed() throws Exception {
        // Asserted as a specific authorization refusal, not merely 4xx. An unmapped path returns
        // 404 under either configuration, so `is4xxClientError` would pass just as happily
        // against anyRequest().permitAll() -- a test that cannot fail, which is the exact defect
        // this review kept finding. 401/403 is the answer only a deny-by-default chain gives:
        // measured, this chain answers 403, because the request reaches authorization with an
        // anonymous principal rather than being rejected earlier for missing credentials.
        int status = mockMvc.perform(get("/api/v1/some-future-admin-surface"))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("an unclassified route must be refused by authorization, not merely unmapped; "
                        + "a 401/403 here is what distinguishes deny-by-default from permitAll, "
                        + "which would let the request through to a 404")
                .isIn(401, 403);
    }

    /**
     * Voucher inspection stays public — that is the ledger's reason to exist, and a
     * deny-by-default chain must not quietly close it.
     *
     * <p>Asserted as "not 401/403" rather than "200": the lookup may legitimately 404 for an
     * unknown voucher id. What matters is that authorization did not refuse it.
     */
    @Test
    void voucherInspectionRemainsPublic() throws Exception {
        int status = mockMvc.perform(get("/api/v1/vouchers/unknown-voucher-id"))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("voucher inspection is public; a deny-by-default chain must not close it")
                .isNotIn(401, 403);
    }

    /** The login page must stay reachable, or nobody can authenticate in the first place. */
    @Test
    void theLoginPageRemainsPublic() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }

    /** Health probes must stay reachable for orchestrators. */
    @Test
    void healthRemainsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
