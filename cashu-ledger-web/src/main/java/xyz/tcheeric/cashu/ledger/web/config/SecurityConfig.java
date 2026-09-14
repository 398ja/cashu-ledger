package xyz.tcheeric.cashu.ledger.web.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import xyz.tcheeric.cashu.ledger.web.security.AuthorityResolver;
import xyz.tcheeric.cashu.ledger.web.security.Nip98AuthenticationFilter;
import xyz.tcheeric.cashu.ledger.web.security.OperationalEndpointFilter;
import xyz.tcheeric.cashu.ledger.web.security.Nip98Validator;
import xyz.tcheeric.cashu.ledger.web.security.TraceSecurityProperties;

/**
 * Web security. Public read endpoints (voucher inspection, health) remain open;
 * the trace API enforces NIP-98 authentication and authority gating via
 * {@link Nip98AuthenticationFilter} (design §7.3).
 */
@Configuration
@EnableConfigurationProperties(TraceSecurityProperties.class)
public class SecurityConfig {

    @Bean
    public Nip98Validator nip98Validator(TraceSecurityProperties properties) {
        return new Nip98Validator(properties.getAuthSkewSeconds(), System::currentTimeMillis);
    }

    @Bean
    public AuthorityResolver authorityResolver(TraceSecurityProperties properties) {
        return new AuthorityResolver(properties);
    }

    @Bean
    public Nip98AuthenticationFilter nip98AuthenticationFilter(
            Nip98Validator validator, AuthorityResolver authorityResolver) {
        return new Nip98AuthenticationFilter(validator, authorityResolver);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, Nip98AuthenticationFilter nip98Filter) throws Exception {
        http.csrf(csrf -> csrf.disable())
                // Security headers on every response (audit M-28). The index page holds a
                // decrypted nsec in browser memory after login, so an injected script there
                // steals a private key rather than defacing a page. The CSP allows inline
                // styles and scripts because the page is a single self-contained template that
                // uses both; tightening that means extracting them to files, which is worth
                // doing but is a change to the page rather than to its policy.
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; "
                                        + "script-src 'self' 'unsafe-inline'; "
                                        + "style-src 'self' 'unsafe-inline'; "
                                        + "img-src 'self' data:; "
                                        + "connect-src 'self'; "
                                        + "object-src 'none'; "
                                        + "base-uri 'self'; "
                                        + "form-action 'self'; "
                                        + "frame-ancestors 'none'"))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers
                                        .ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                // Authorisation is not expressed here: Nip98AuthenticationFilter resolves a
                // TracePrincipal into a request attribute rather than into Spring's
                // SecurityContext, so hasAuthority() would match nothing and silently permit
                // everything. The operational endpoints are gated by
                // OperationalEndpointFilter instead, which reads the same principal the rest of
                // this application does (audit L-28).
                // AppSec finding M-1 (issue #8). This used to be anyRequest().permitAll(), with
                // every access decision made inside a handler: TraceAdminController calls
                // requireAdmin, TraceController throws from its principal() helper when the
                // request attribute is absent. Both fail closed today, and Nip98AuthenticationFilter
                // already rejects unauthenticated requests to /api/v1/trace before they reach a
                // controller, so this was never an open door.
                //
                // What was missing is that the boundary was not stated where authorization is
                // configured. It lived in a filter's path prefix and in per-handler throws, so an
                // admin route added under a different prefix -- or the prefix being renamed --
                // would be unauthenticated with nothing failing to compile or to test. Stating it
                // here makes the intent reviewable in one place, and keeps the filter and the
                // in-handler checks as defence in depth rather than as the only defence.
                .authorizeHttpRequests(auth -> auth
                        // The trace surface. The filter already gates this prefix; saying so again
                        // means a future change to shouldNotFilter cannot silently open it.
                        .requestMatchers("/api/v1/trace/admin/**").hasAuthority("trace:admin")
                        .requestMatchers("/api/v1/trace/**").authenticated()
                        // Deliberately public: voucher inspection is the ledger's reason to exist,
                        // and ProxyController despite its name is not a forwarder -- it delegates
                        // to the same local VoucherLedgerService as /api/v1/vouchers/*.
                        .requestMatchers("/api/v1/vouchers/**", "/api/v1/unclaimed/**",
                                "/api/v1/watch/**", "/proxy/**").permitAll()
                        // The login page and its static assets, plus health probes.
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                        .requestMatchers(EndpointRequest.to("health", "info")).permitAll()
                        // Anything not named above is a route nobody has classified. Denying it is
                        // the safe reading, and it is what turns "we forgot" into a visible 401
                        // rather than an open endpoint.
                        .anyRequest().authenticated())
                .addFilterBefore(nip98Filter, UsernamePasswordAuthenticationFilter.class)
                // After the NIP-98 filter, so the principal it resolves is visible here.
                .addFilterAfter(new OperationalEndpointFilter(), Nip98AuthenticationFilter.class);
        return http.build();
    }
}
