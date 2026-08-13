package cm.afriland.titres.security;

import java.security.Key;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import cm.afriland.titres.config.AppProperties;
import cm.afriland.titres.error.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;

/**
 * Acceptation des jetons d'acces <b>Keycloak</b> (OIDC, RS256), en complement des
 * jetons HS256 maison. Sert la migration vers Keycloak : le frontend Next.js
 * s'authentifie via Keycloak, et l'API valide le jeton RS256 par sa signature
 * (cle publique du realm, JWKS), son issuer et son expiration.
 *
 * <p>L'identite applicative reste portee par la table {@code users} : on rapproche
 * le jeton (par e-mail) d'un compte existant pour reutiliser le RBAC en place. Le
 * jeton n'est jamais une preuve d'autorisation en soi cote metier.</p>
 *
 * <p>Actif uniquement si {@code app.keycloak-issuer} est renseigne.</p>
 */
@Component
public class KeycloakTokenService {

    private final AppProperties props;
    private final JdbcTemplate jdbc;
    private final RestTemplate rest = new RestTemplate();

    /** Cache des cles publiques du realm, indexees par {@code kid}. */
    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile long lastFetchMs = 0L;

    public KeycloakTokenService(AppProperties props, JdbcTemplate jdbc) {
        this.props = props;
        this.jdbc = jdbc;
    }

    /**
     * Verifie un jeton Keycloak et le rapproche d'un compte {@code users}.
     *
     * @throws RuntimeException si le jeton n'est pas un jeton Keycloak valide
     *                          (signature/issuer/expiration) ; l'appelant retombe
     *                          alors sur le message d'erreur d'authentification.
     */
    public AuthUser verify(String token) {
        String issuer = props.getKeycloakIssuer();
        Claims claims = Jwts.parser()
                .keyLocator(keyLocator())
                .requireIssuer(issuer)
                .clockSkewSeconds(30)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        // Identifiant de rapprochement : e-mail en priorite, a defaut le
        // preferred_username (souvent egal a l'e-mail selon la configuration du realm).
        String identifiant = claims.get("email", String.class);
        if (identifiant == null || identifiant.isBlank()) {
            identifiant = claims.get("preferred_username", String.class);
        }
        if (identifiant == null || identifiant.isBlank()) {
            throw ApiException.unauthorized("Jeton Keycloak sans e-mail : compte introuvable.");
        }
        return resoudreUtilisateur(identifiant);
    }

    /** Rapproche l'e-mail du jeton d'un compte ACTIF ; en fait un AuthUser. */
    private AuthUser resoudreUtilisateur(String email) {
        return jdbc.query(
                "SELECT id, role, statut FROM users WHERE lower(email) = lower(?)",
                rs -> {
                    if (!rs.next()) {
                        throw ApiException.unauthorized(
                                "Aucun compte de la plateforme ne correspond a cet utilisateur.");
                    }
                    String statut = rs.getString("statut");
                    if (statut != null && !"ACTIF".equalsIgnoreCase(statut)) {
                        throw ApiException.unauthorized("Compte non actif.");
                    }
                    return new AuthUser(
                            UUID.fromString(rs.getString("id")),
                            email,
                            rs.getString("role"),
                            false,
                            AuthUser.SCOPE_FULL);
                },
                email);
    }

    private Locator<Key> keyLocator() {
        return new Locator<Key>() {
            @Override
            public Key locate(Header header) {
                if (!(header instanceof ProtectedHeader ph)) {
                    return null;
                }
                String kid = ph.getKeyId();
                PublicKey key = keys.get(kid);
                if (key == null) {
                    rafraichirJwks();
                    key = keys.get(kid);
                }
                return key;
            }
        };
    }

    /** Recharge le JWKS du realm (throttle : au plus une fois toutes les 5 s). */
    private synchronized void rafraichirJwks() {
        long now = System.currentTimeMillis();
        if (!keys.isEmpty() && now - lastFetchMs < 5_000L) {
            return;
        }
        String certsUrl = props.getKeycloakIssuer() + "/protocol/openid-connect/certs";
        String json = rest.getForObject(certsUrl, String.class);
        if (json == null) {
            return;
        }
        JwkSet set = Jwks.setParser().build().parse(json);
        Map<String, PublicKey> fresh = new HashMap<>();
        for (Jwk<?> jwk : set.getKeys()) {
            Key key = jwk.toKey();
            if (key instanceof PublicKey pk && jwk.getId() != null) {
                fresh.put(jwk.getId(), pk);
            }
        }
        this.keys = Map.copyOf(fresh);
        this.lastFetchMs = now;
    }
}
