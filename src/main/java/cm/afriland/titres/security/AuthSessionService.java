package cm.afriland.titres.security;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import cm.afriland.titres.config.AppProperties;
import cm.afriland.titres.dto.AuthResponse;
import cm.afriland.titres.dto.UserProfile;
import cm.afriland.titres.dto.UserRow;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Emission de session authentifiee : jeton d'acces (corps) + jeton de
 * rafraichissement (cookie HttpOnly). Partage entre la connexion classique
 * (AuthController) et l'auto-inscription (RegistrationController) pour garantir
 * des cookies et une duree de vie strictement identiques.
 */
@Service
public class AuthSessionService {

    /** Nom du cookie portant le jeton de rafraichissement (HttpOnly). */
    public static final String RT_COOKIE = "afb_rt";
    /** Chemin du cookie : envoye uniquement aux routes d'authentification. */
    public static final String RT_COOKIE_PATH = "/api/v1/auth";

    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final AppProperties props;

    public AuthSessionService(JdbcTemplate jdbc, JwtService jwt, AppProperties props) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.props = props;
    }

    /**
     * Emet les jetons : le jeton d'acces est renvoye dans le corps, le jeton de
     * rafraichissement est depose dans un cookie HttpOnly (jamais expose au JS).
     */
    public AuthResponse issueTokens(UserRow user, HttpServletResponse resp) {
        String accessToken = jwt.issue(user.id(), user.email(), user.role(), user.mustChangePassword());
        String refreshToken = Tokens.generateRefreshToken();
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
                user.id(), Tokens.sha256Hex(refreshToken),
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(props.getRefreshTokenTtl()));
        setRefreshCookie(resp, refreshToken);
        UserProfile profile = user.toProfile();
        // Masque le solde ET le compte de depot dans la reponse destinee aux clients.
        if (Rbac.isClient(user.role())) {
            profile = profile.pourClient();
        }
        // refreshToken = null dans le corps : il vit desormais dans le cookie.
        return new AuthResponse(accessToken, null, "Bearer", props.getAccessTokenTtl(), profile);
    }

    /**
     * Pose le jeton de rafraichissement dans un cookie HttpOnly : inaccessible au
     * JavaScript (immunise contre le vol par XSS). {@code SameSite=Strict} pour
     * limiter le CSRF. {@code Secure} a activer derriere TLS (cf. FORCE_HTTPS).
     */
    public void setRefreshCookie(HttpServletResponse resp, String token) {
        ResponseCookie cookie = ResponseCookie.from(RT_COOKIE, token)
                .httpOnly(true)
                .sameSite("Strict")
                .path(RT_COOKIE_PATH)
                .maxAge(props.getRefreshTokenTtl())
                .secure("true".equalsIgnoreCase(System.getenv("FORCE_HTTPS")))
                .build();
        resp.addHeader("Set-Cookie", cookie.toString());
    }
}
