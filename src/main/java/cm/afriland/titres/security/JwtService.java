package cm.afriland.titres.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import cm.afriland.titres.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Emission et verification des jetons d'acces JWT (HS256).
 *
 * Le jeton d'acces est volontairement court ; le renouvellement passe par un
 * jeton de rafraichissement opaque, stocke hache en base et revocable.
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long accessTtl;

    public JwtService(AppProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        this.accessTtl = props.getAccessTokenTtl();
    }

    /** Genere un jeton d'acces signe (session complete) pour un utilisateur donne. */
    public String issue(UUID userId, String email, String role, boolean mustChangePassword) {
        return issue(userId, email, role, mustChangePassword, AuthUser.SCOPE_FULL, accessTtl);
    }

    /**
     * Genere un jeton d'acces signe avec une portee et une duree explicites.
     * Utilise pour le jeton d'inscription (portee restreinte, sans jeton de
     * rafraichissement : aucune session persistante n'est ouverte sans OTP).
     */
    public String issue(UUID userId, String email, String role, boolean mustChangePassword,
                        String scope, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role)
                .claim("mcp", mustChangePassword)
                .claim("scope", scope)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    /** Verifie la signature et la validite temporelle d'un jeton. */
    public AuthUser verify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clockSkewSeconds(5)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Boolean mcp = claims.get("mcp", Boolean.class);
        // Jetons anterieurs (sans claim scope) = session complete, par compatibilite.
        String scope = claims.get("scope", String.class);
        return new AuthUser(
                UUID.fromString(claims.getSubject()),
                claims.get("email", String.class),
                claims.get("role", String.class),
                Boolean.TRUE.equals(mcp),
                scope == null ? AuthUser.SCOPE_FULL : scope);
    }
}
