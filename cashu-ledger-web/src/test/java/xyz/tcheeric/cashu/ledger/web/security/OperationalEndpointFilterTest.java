package xyz.tcheeric.cashu.ledger.web.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operational endpoints expose metrics and the API surface, so they require NIP-98 auth.
 *
 * <p>The filter matched against {@code getRequestURI()}, which includes the context path. Under
 * any non-root {@code server.servlet.context-path} the URI is {@code /ledger/actuator/prometheus},
 * which starts with none of the protected prefixes, so the filter permitted everything while
 * remaining registered and looking correct. A deployment mounted under a path had no protection at
 * all, and nothing about the configuration would have suggested it.
 */
@DisplayName("Operational endpoint authentication")
class OperationalEndpointFilterTest {

    private final OperationalEndpointFilter filter = new OperationalEndpointFilter();

    @Test
    @DisplayName("unauthenticated access to a protected endpoint is refused")
    void unauthenticatedIsRefused() throws Exception {
        MockHttpServletResponse response = run(request("", "/actuator/prometheus"), false);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).contains("Nostr");
    }

    @Test
    @DisplayName("an authenticated principal passes through")
    void authenticatedPasses() throws Exception {
        MockHttpServletResponse response = run(request("", "/actuator/prometheus"), true);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    /** The regression: identical request, application mounted under a context path. */
    @Test
    @DisplayName("a context path does not disable the filter")
    void contextPathDoesNotBypass() throws Exception {
        MockHttpServletResponse response = run(request("/ledger", "/actuator/prometheus"), false);

        assertThat(response.getStatus())
                .as("under a context path getRequestURI() is /ledger/actuator/prometheus, which "
                        + "matched no prefix and let every request through unauthenticated")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a context path does not disable the filter when servletPath is empty")
    void contextPathWithEmptyServletPathDoesNotBypass() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/ledger");
        request.setServletPath("");                                 // some dispatch types
        request.setRequestURI("/ledger/actuator/prometheus");

        assertThat(run(request, false).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("case variation does not bypass the filter")
    void caseVariationDoesNotBypass() throws Exception {
        assertThat(run(request("", "/ACTUATOR/prometheus"), false).getStatus())
                .as("the guard must not be weaker than the mapping it guards")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("unprotected endpoints stay open")
    void unprotectedEndpointsArePublic() throws Exception {
        assertThat(run(request("", "/actuator/health"), false).getStatus()).isEqualTo(200);
        assertThat(run(request("/ledger", "/"), false).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("every declared prefix is enforced")
    void allProtectedPrefixesAreEnforced() throws Exception {
        for (String path : new String[]{"/actuator/prometheus", "/actuator/metrics",
                "/v3/api-docs", "/swagger-ui/index.html"}) {
            assertThat(run(request("", path), false).getStatus())
                    .as("%s must require authentication", path)
                    .isEqualTo(401);
        }
    }

    private static MockHttpServletRequest request(String contextPath, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath(contextPath);
        request.setServletPath(path);
        request.setRequestURI(contextPath + path);
        return request;
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, boolean authenticated)
            throws Exception {
        if (authenticated) {
            request.setAttribute(TracePrincipal.ATTRIBUTE, new Object());
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }
}
