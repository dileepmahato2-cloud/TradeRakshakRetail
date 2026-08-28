package com.traderakshak.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Validates JWT Bearer tokens on every request.
 * Stateless — no server-side session (BRD §17 L2).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");
        final String queryToken = request.getRequestURI().endsWith("/live-prices/stream")
                ? request.getParameter("token")
                : null;
        if ((authHeader == null || !authHeader.startsWith("Bearer ")) &&
                (queryToken == null || queryToken.isBlank())) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring(7)
                : queryToken;
        try {
            final String userEmail = jwtService.extractUsername(jwt);
            if (userEmail != null &&
                    SecurityContextHolder.getContext().getAuthentication() == null) {

                UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);
                if (jwtService.isTokenValid(jwt, userDetails)) {
                    var authToken = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            // Don't abort — let Spring Security handle the unauthenticated state
        }

        filterChain.doFilter(request, response);
    }
}
