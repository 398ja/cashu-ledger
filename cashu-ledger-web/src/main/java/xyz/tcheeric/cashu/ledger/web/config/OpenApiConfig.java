package xyz.tcheeric.cashu.ledger.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata for the REST API (design §5.5, T076). springdoc generates the spec
 * at {@code /v3/api-docs} and a UI at {@code /swagger-ui.html} from the controllers; this bean
 * supplies the title, description, and version shown there.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cashuLedgerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Cashu Ledger API")
                .description("Voucher inspection and transaction-traceability read API. "
                        + "Trace endpoints under /api/v1/trace require NIP-98 authentication and "
                        + "shape responses to the caller's access level.")
                .version("v1")
                .license(new License().name("MIT")));
    }
}
