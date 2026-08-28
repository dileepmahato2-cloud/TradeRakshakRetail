package com.traderakshak.security;

// =============================================================================
// JWT token generation and validation (replaces Python itsdangerous sessions)
// BRD §17 L2 — 30-minute token expiry, RS256-compatible via HS512
// =============================================================================

import com.traderakshak.config.AppProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {

    private final AppProperties props;

    /** Claim marking a token as a refresh token rather than an access token. */
    public static final String TOKEN_TYPE_CLAIM = "typ";
    public static final String REFRESH_TOKEN_TYPE = "refresh";

    public JwtService(AppProperties props) {
        this.props = props;
    }

    // ── Token generation ──────────────────────────────────────────────────────

    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return buildToken(extraClaims, userDetails, props.getJwt().getExpirationMs());
    }

    public String generateRefreshToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(TOKEN_TYPE_CLAIM, REFRESH_TOKEN_TYPE);
        return buildToken(claims, userDetails, props.getJwt().getRefreshExpirationMs());
    }

    /**
     * True only for tokens minted by {@link #generateRefreshToken(UserDetails)}. Access and
     * refresh tokens are otherwise identical apart from their expiry, so without this marker
     * a stolen 30-minute access token could be replayed against /auth/refresh to mint new
     * ones indefinitely — which would defeat the short access-token lifetime entirely.
     */
    public boolean isRefreshToken(String token) {
        try {
            return REFRESH_TOKEN_TYPE.equals(extractClaim(token, c -> c.get(TOKEN_TYPE_CLAIM, String.class)));
        } catch (Exception e) {
            return false;
        }
    }

    private String buildToken(Map<String, Object> extraClaims,
                               UserDetails userDetails,
                               long expirationMs) {
        return Jwts.builder()
            .claims(extraClaims)
            .subject(userDetails.getUsername())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(getSigningKey(), Jwts.SIG.HS512)
            .compact();
    }

    // ── Token validation ──────────────────────────────────────────────────────

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    private SecretKey getSigningKey() {
        try {
            byte[] keyBytes = java.security.MessageDigest.getInstance("SHA-512")
                .digest(props.getJwt().getSecret()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is not available", e);
        }
    }
}
