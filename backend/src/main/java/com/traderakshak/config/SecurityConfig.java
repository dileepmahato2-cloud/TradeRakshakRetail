package com.traderakshak.config;

// =============================================================================
// SecurityConfig — TradeRakshak™ v11.5 (No-Docker Edition)
//
// Key change from Docker edition:
//   • Spring Boot now serves Angular SPA from /static/ (embedded in JAR)
//   • Static resources (/, /index.html, *.js, *.css, assets/) are permit-all
//   • All /api/** routes require JWT authentication
//   • SPA fallback: any unknown URL → index.html (Angular router handles it)
// =============================================================================

import com.traderakshak.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Configuration @EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final UserDetailsService      userDetailsService;
    private final AppProperties           props;

    @Bean public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(props.getSecurity().getBcryptStrength());
    }

    @Bean public AuthenticationProvider authenticationProvider() {
        var p = new DaoAuthenticationProvider();
        p.setUserDetailsService(userDetailsService);
        p.setPasswordEncoder(passwordEncoder());
        return p;
    }

    @Bean public AuthenticationManager authenticationManager(AuthenticationConfiguration c) throws Exception {
        return c.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .headers(h -> h
                .frameOptions(f -> f.deny())
                .contentTypeOptions(c -> {})
                .referrerPolicy(r -> r.policy(
                    ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .permissionsPolicy(p -> p.policy("geolocation=(), camera=(), microphone=()")))
            .cors(c -> c.configurationSource(corsSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, authException) ->
                    writeApiAuthError(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "Login session expired. Please log in again, then connect Dhan."))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                    writeApiAuthError(response, HttpServletResponse.SC_FORBIDDEN,
                        "Your login does not have permission for this action. Please log in again with the correct account.")))
            .authorizeHttpRequests(a -> a
                // ── Angular SPA & static assets — always public ────────────────
                .requestMatchers(HttpMethod.GET,
                    "/", "/index.html", "/favicon.ico",
                    "/*.js", "/*.css", "/*.map",
                    "/assets/**", "/fonts/**", "/icons/**",
                    "/*.js.br", "/*.css.br",
                    "/manifest.webmanifest", "/ngsw.json", "/ngsw-worker.js")
                    .permitAll()
                // Public Angular deep-links — serve index.html for SPA routing
                .requestMatchers(HttpMethod.GET,
                    "/dashboard", "/login", "/register",
                    "/stock-ranking", "/screener", "/strategy",
                    "/backtesting", "/paper", "/trading", "/journal", "/watchlist",
                    "/options", "/checklist", "/stocks", "/data", "/events",
                    "/broker", "/competitions", "/reports",
                    "/sector-intelligence", "/subscription", "/billing",
                    "/pricing", "/profile", "/issues", "/feedback",
                    "/user-hierarchy", "/agents", "/teaching/**",
                    "/strategy-builder/**", "/portfolio",
                    "/institution-mgmt",
                    "/project-health",
                    "/notifications", "/admin/**",
                    "/users", "/institutions", "/ml-engine",
                    "/communication",
                    "/system-settings")
                    .permitAll()
                // ── API public endpoints ────────────────────────────────────────
                .requestMatchers("/api/auth/**",
                    "/api/actuator/health",
                    "/api/swagger-ui/**",
                    "/api/v3/api-docs/**",
                    "/api/swagger-ui.html").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/broker/list").permitAll()
                // Live-price SSE stream authenticates via its own ?token= query param
                // (LivePriceController.validateToken). Leaving it under /api/** →
                // authenticated() caused AccessDeniedException on the async re-dispatch
                // when the stream ends ("response already committed"). Permit it here; the
                // controller still rejects invalid/expired tokens.
                .requestMatchers(HttpMethod.GET, "/api/live-prices/stream", "/live-prices/stream").permitAll()
                // ── API protected endpoints ─────────────────────────────────────
                .requestMatchers("/api/super/**").hasAnyRole("SUPER_ADMIN","PLATFORM_ADMIN")
                .requestMatchers("/api/admin/**").hasAnyRole(
                    "SUPER_ADMIN","PLATFORM_ADMIN","ADMIN","INSTITUTION_ADMIN")
                .requestMatchers("/api/**").authenticated()
                // All other requests — permit (covers Angular hash/history routing)
                .anyRequest().permitAll())
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeApiAuthError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        if (response.isCommitted()) return;
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\",\"errorCode\":\"AUTH-" + status + "\"}");
    }

    @Bean public CorsConfigurationSource corsSource() {
        var cfg = new CorsConfiguration();
        // Dev: allow localhost:4200 and same-origin (port 8080)
        String origins = props.getCors().getAllowedOrigins();
        cfg.setAllowedOrigins(Arrays.asList(
            (origins + ",http://localhost:8080,http://127.0.0.1:8080").split(",")));
        cfg.setAllowedMethods(List.of("GET","POST","PUT","DELETE","PATCH","OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization","Content-Type","X-Requested-With","Accept","Origin"));
        cfg.setExposedHeaders(List.of("Authorization"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        var src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
