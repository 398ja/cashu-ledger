package xyz.tcheeric.cashu.ledger.integration;

import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import xyz.tcheeric.cashu.ledger.core.model.BackingStrategy;
import xyz.tcheeric.cashu.ledger.core.model.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStateMetadata;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.web.CashuLedgerWebApplication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the inspect endpoint returns a voucher payload when the voucher exists.
 */
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
