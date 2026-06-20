package xyz.tcheeric.cashu.ledger.web.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import xyz.tcheeric.cashu.ledger.web.security.AuthorityResolver;
import xyz.tcheeric.cashu.ledger.web.security.Nip98AuthenticationFilter;
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
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(nip98Filter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
