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
        if (!isProtected(pathWithinApplication(request))) {
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

    /**
     * The request path with the context path removed.
     *
     * <p>This filter previously matched against {@code getRequestURI()}, which includes the
     * context path. Deployed under any non-root {@code server.servlet.context-path} the URI reads
     * {@code /ledger/actuator/prometheus}, which does not start with {@code /actuator/prometheus},
     * so every prefix missed and the filter permitted everything: authentication silently absent,
     * with the filter still registered and looking like it worked.
     *
     * <p>{@code getServletPath()} is already context-relative, so the comparison holds wherever
     * the application is mounted. It is empty for some dispatch types, hence the fallback that
     * strips the context path by hand rather than matching against nothing.
     */
    private static String pathWithinApplication(final HttpServletRequest request) {
        final String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            return servletPath;
        }
        final String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        final String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private static boolean isProtected(final String path) {
        if (path == null) {
            return false;
        }
        // Case-insensitive: the filter must not be weaker than the mapping it guards, and a
        // container that routes /ACTUATOR/... to the same handler would otherwise bypass it.
        final String normalised = path.toLowerCase(java.util.Locale.ROOT);
        return PROTECTED_PREFIXES.stream().anyMatch(normalised::startsWith);
    }
}
