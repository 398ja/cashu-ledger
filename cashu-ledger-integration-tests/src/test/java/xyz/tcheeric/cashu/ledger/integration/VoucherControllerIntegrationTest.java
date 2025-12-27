package xyz.tcheeric.cashu.ledger.integration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import xyz.tcheeric.cashu.ledger.core.model.BackingStrategy;
import xyz.tcheeric.cashu.ledger.core.model.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.core.model.TransitionActor;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStateMetadata;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.state.HistoryResult;
import xyz.tcheeric.cashu.ledger.core.state.StatusChange;
import xyz.tcheeric.cashu.ledger.core.state.VoucherSearchCriteria;
import xyz.tcheeric.cashu.ledger.core.state.VerificationReport;
import xyz.tcheeric.cashu.ledger.web.CashuLedgerWebApplication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CashuLedgerWebApplication.class)
@AutoConfigureMockMvc
class VoucherControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VoucherLedgerService ledgerService;

    @Test
    void shouldReturnVoucherPayloadWhenInspectingExistingVoucher() throws Exception {
        VoucherNode sample = sampleVoucher();
        BDDMockito.given(ledgerService.fetchVoucher("v-123")).willReturn(Optional.of(sample));

        mockMvc.perform(get("/api/v1/vouchers/{id}", "v-123").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherId").value("v-123"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.unit").value("sat"));
    }

    @Test
    void shouldReturnNotFoundWhenInspectingMissingVoucher() throws Exception {
        BDDMockito.given(ledgerService.fetchVoucher("missing")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/vouchers/{id}", "missing").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnHistoryWithEvents() throws Exception {
        HistoryResult history = new HistoryResult(
                List.of(new StatusChange(
                        "v-123",
                        VoucherStatus.CLAIMED,
                        VoucherStatus.ISSUED,
                        1L,
                        Instant.parse("2024-01-02T00:00:00Z"),
                        Instant.parse("2024-01-02T00:00:01Z"),
                        "wss://relay.imani.casa",
                        "event-2",
                        TransitionActor.RECIPIENT
                )),
                List.of("warn")
        );
        BDDMockito.given(ledgerService.fetchHistory("v-123", null, null, 50)).willReturn(history);

        mockMvc.perform(get("/api/v1/vouchers/{id}/history", "v-123").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].voucherId").value("v-123"))
                .andExpect(jsonPath("$.events[0].status").value("CLAIMED"))
                .andExpect(jsonPath("$.warnings[0]").value("warn"));
    }

    @Test
    void shouldReturnVerificationReportOr404() throws Exception {
        VerificationReport found = new VerificationReport("v-123", true, true, true, true, true, "ok", List.of());
        BDDMockito.given(ledgerService.verify("v-123")).willReturn(found);
        BDDMockito.given(ledgerService.verify("missing")).willReturn(VerificationReport.notFound("missing"));

        mockMvc.perform(get("/api/v1/vouchers/{id}/verify", "v-123").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true));

        mockMvc.perform(get("/api/v1/vouchers/{id}/verify", "missing").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnDiffNotFoundWhenEitherSideMissing() throws Exception {
        BDDMockito.given(ledgerService.fetchVoucher("left")).willReturn(Optional.empty());
        BDDMockito.given(ledgerService.fetchVoucher("right")).willReturn(Optional.of(sampleVoucher()));

        mockMvc.perform(get("/api/v1/vouchers/{id}/diff/{other}", "left", "right").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldPropagateSearchFiltersToService() throws Exception {
        BDDMockito.given(ledgerService.search(BDDMockito.any())).willReturn(List.of(sampleVoucher()));

        mockMvc.perform(get("/api/v1/vouchers")
                        .param("issuer", "merchant-001")
                        .param("status", "issued")
                        .param("unclaimed", "true")
                        .param("limit", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].voucherId").value("v-123"));

        ArgumentCaptor<VoucherSearchCriteria> captor = ArgumentCaptor.forClass(VoucherSearchCriteria.class);
        BDDMockito.then(ledgerService).should().search(captor.capture());
        VoucherSearchCriteria criteria = captor.getValue();
        assertThat(criteria.issuerId()).isEqualTo("merchant-001");
        assertThat(criteria.status()).isEqualTo(VoucherStatus.ISSUED);
        assertThat(criteria.unclaimed()).isTrue();
        assertThat(criteria.limit()).isEqualTo(10);
    }

    private VoucherNode sampleVoucher() {
        return new VoucherNode(
                "v-123",
                "issuer-001",
                "pk-issuer",
                1000L,
                0,
                1000L,
                1000L,
                1000L,
                "sat",
                BackingStrategy.PROPORTIONAL,
                BigDecimal.ONE,
                VoucherStatus.ISSUED,
                VoucherStateMetadata.empty(),
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-12-31T00:00:00Z"),
                "sample voucher",
                Map.of(),
                List.of(),
                new NostrEventMetadata(
                        "event-1",
                        "pk-issuer",
                        Instant.parse("2024-01-01T00:00:00Z"),
                        "wss://relay.imani.casa",
                        30078,
                        List.of(),
                        "sig-hex"
                )
        );
    }
}
