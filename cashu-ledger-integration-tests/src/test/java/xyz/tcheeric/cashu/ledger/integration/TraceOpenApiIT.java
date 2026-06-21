package xyz.tcheeric.cashu.ledger.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.web.CashuLedgerWebApplication;

/**
 * Verifies the OpenAPI document (T076) is generated and documents the trace endpoints. The spec
 * path is public (not behind the NIP-98 filter), so no authentication is required.
 */
@Tag("integration")
@SpringBootTest(classes = CashuLedgerWebApplication.class)
@AutoConfigureMockMvc
class TraceOpenApiIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VoucherLedgerService voucherLedgerService;

    /** The generated spec is served and includes the trace events path and API title. */
    @Test
    void shouldGenerateOpenApiSpecWithTracePaths() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Cashu Ledger API"))
                .andExpect(jsonPath("$.paths['/api/v1/trace/events']").exists());
    }
}
