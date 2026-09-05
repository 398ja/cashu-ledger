package xyz.tcheeric.cashu.ledger.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Requires an authenticated operator for the operational endpoints.
 *
 * <h2>What was exposed</h2>
 *
 * <p>{@code /actuator/prometheus} and the springdoc endpoints were anonymous (audit L-28). The
 * metrics carry ledger volumes, error rates and event counts; springdoc enumerates the whole API
 * surface, admin routes included, which is a map for anyone deciding what to probe next. Neither
 * is catastrophic on its own, and neither has any reason to be public.
 *
 * <p>Health stays anonymous: container probes cannot present a NIP-98 event, and it reports only
 * UP or DOWN.
 *
 * <h2>Why a filter rather than authorizeHttpRequests</h2>
 *
 * <p>{@link Nip98AuthenticationFilter} puts a {@link TracePrincipal} on the request rather than
 * into Spring's {@code SecurityContext}, so {@code hasAuthority(...)} in the filter chain would
 * match nothing and permit everything. Reading the same attribute the rest of the application
 * reads keeps one notion of "who is calling" instead of two that can disagree.
 */
public final class OperationalEndpointFilter extends OncePerRequestFilter {

    private static final List<String> PROTECTED_PREFIXES = List.of(
            "/actuator/prometheus",
            "/actuator/metrics",
            "/v3/api-docs",
            "/swagger-ui");

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        if (!isProtected(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        final Object principal = request.getAttribute(TracePrincipal.ATTRIBUTE);
        if (principal == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Nostr realm=\"cashu-ledger\"");
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"unauthorized\",\"message\":\"operational endpoints require "
                            + "NIP-98 authentication\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isProtected(final String uri) {
        if (uri == null) {
            return false;
        }
        return PROTECTED_PREFIXES.stream().anyMatch(uri::startsWith);
    }
}
