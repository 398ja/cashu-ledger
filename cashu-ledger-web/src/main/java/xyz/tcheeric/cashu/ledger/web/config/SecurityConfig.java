package xyz.tcheeric.cashu.ledger.web.config;

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
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(nip98Filter, UsernamePasswordAuthenticationFilter.class)
                // After the NIP-98 filter, so the principal it resolves is visible here.
                .addFilterAfter(new OperationalEndpointFilter(), Nip98AuthenticationFilter.class);
        return http.build();
    }
}
