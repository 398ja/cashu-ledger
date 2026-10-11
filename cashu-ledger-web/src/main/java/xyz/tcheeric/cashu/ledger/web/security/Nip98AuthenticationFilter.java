package xyz.tcheeric.cashu.ledger.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates trace API requests via NIP-98 and gates them by authority
 * (design §7.3 / FR-028). On success it stashes a {@link TracePrincipal} request
 * attribute for controllers/the response mapper; invalid auth yields 401 and a
 * recognised caller with no authority yields 403. Only applies to {@code /api/v1/trace/**}.
 */
public final class Nip98AuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(Nip98AuthenticationFilter.class);
    private static final String PATH_PREFIX = "/api/v1/trace";

    private final Nip98Validator validator;
    private final AuthorityResolver authorityResolver;

    public Nip98AuthenticationFilter(Nip98Validator validator, AuthorityResolver authorityResolver) {
        this.validator = validator;
        this.authorityResolver = authorityResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String pubkey;
        try {
            pubkey = validator.authenticate(
                    request.getHeader(HttpHeaders.AUTHORIZATION),
                    request.getMethod(),
                    fullUrl(request));
        } catch (Nip98Exception e) {
            LOGGER.warn("trace_auth_failed path={} reason={}", request.getRequestURI(), e.getMessage());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "TRACE_UNAUTHENTICATED");
            return;
        }

        Set<TraceAuthority> authorities = authorityResolver.resolve(pubkey);
        if (authorities.isEmpty()) {
            LOGGER.warn("trace_auth_forbidden pubkey={} path={}", pubkey, request.getRequestURI());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "TRACE_FORBIDDEN");
            return;
        }

        request.setAttribute(TracePrincipal.ATTRIBUTE, new TracePrincipal(pubkey, authorities));
        // Also publish the principal to Spring Security, so the filter chain's authorization
        // rules can see it (AppSec finding M-1, issue #8). Before this the principal existed
        // only as a request attribute, which meant `.authenticated()` in the chain would have
        // rejected even a validly signed request -- authorization had nowhere to live but inside
        // the handlers. Authorities are exposed as ROLE-less strings matching the enum
        // (`trace:admin`, `trace:read_full`, ...) so a rule can name one directly.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(pubkey, null, grantedAuthorities(authorities)));
        chain.doFilter(request, response);
    }

    /** Maps trace authorities onto Spring's authority strings, lower-cased and namespaced. */
    private static List<SimpleGrantedAuthority> grantedAuthorities(Set<TraceAuthority> authorities) {
        return authorities.stream()
                .map(authority -> new SimpleGrantedAuthority(
                        "trace:" + authority.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static String fullUrl(HttpServletRequest request) {
        StringBuffer url = request.getRequestURL();
        String query = request.getQueryString();
        return query == null ? url.toString() : url.append('?').append(query).toString();
    }
}
