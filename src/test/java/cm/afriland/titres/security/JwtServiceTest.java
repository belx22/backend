package cm.afriland.titres.security;

import cm.afriland.titres.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-at-least-32-chars-long!";

    private JwtService jwtService;

    @BeforeEach
    void setup() {
        AppProperties props = new AppProperties();
        props.setJwtSecret(SECRET);
        props.setAccessTokenTtl(900);
        jwtService = new JwtService(props);
    }

    @Test
    void issue_retourne_token_non_nul() {
        String token = jwtService.issue(UUID.randomUUID(), "agent@afb.cm", "AGENT", false);
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void verify_restitue_id_email_role() {
        UUID id = UUID.randomUUID();
        String token = jwtService.issue(id, "superviseur@afb.cm", "SUPERVISEUR", false);

        AuthUser user = jwtService.verify(token);

        assertEquals(id, user.id());
        assertEquals("superviseur@afb.cm", user.email());
        assertEquals("SUPERVISEUR", user.role());
        assertFalse(user.mustChangePassword());
    }

    @Test
    void verify_mustChangePassword_vrai() {
        UUID id = UUID.randomUUID();
        String token = jwtService.issue(id, "admin@afb.cm", "ADMIN", true);

        assertTrue(jwtService.verify(token).mustChangePassword());
    }

    @Test
    void tokens_distincts_pour_meme_utilisateur() {
        UUID id = UUID.randomUUID();
        String t1 = jwtService.issue(id, "agent@afb.cm", "AGENT", false);
        String t2 = jwtService.issue(id, "agent@afb.cm", "AGENT", false);
        // Le JTI est aléatoire → tokens toujours différents
        assertNotEquals(t1, t2);
    }

    @Test
    void verify_token_altere_leve_exception() {
        String token = jwtService.issue(UUID.randomUUID(), "agent@afb.cm", "AGENT", false);
        // On altère un caractère du PAYLOAD (entre les deux points), pas de la
        // signature : le dernier caractere base64url de la signature n'encode que
        // quelques bits utiles, donc plusieurs valeurs decodent vers les memes
        // octets → l'alteration serait parfois sans effet (flaky). Modifier le
        // payload change les donnees signees et invalide la signature a coup sur.
        int p1 = token.indexOf('.');
        int p2 = token.indexOf('.', p1 + 1);
        int idx = (p1 + p2) / 2; // au cœur du payload
        char c = token.charAt(idx);
        char repl = (c == 'A') ? 'B' : 'A';
        String tampered = token.substring(0, idx) + repl + token.substring(idx + 1);

        assertNotEquals(token, tampered);
        assertThrows(Exception.class, () -> jwtService.verify(tampered));
    }

    @Test
    void verify_token_autre_cle_leve_exception() {
        AppProperties other = new AppProperties();
        other.setJwtSecret("other-secret-key-at-least-32-chars-long!");
        other.setAccessTokenTtl(900);
        String token = new JwtService(other).issue(UUID.randomUUID(), "agent@afb.cm", "AGENT", false);

        assertThrows(Exception.class, () -> jwtService.verify(token));
    }

    @Test
    void verify_token_expire_leve_exception() {
        AppProperties shortProps = new AppProperties();
        shortProps.setJwtSecret(SECRET);
        shortProps.setAccessTokenTtl(-10); // TTL négatif → expiré immédiatement
        String token = new JwtService(shortProps).issue(UUID.randomUUID(), "client@test.cm", "CLIENT_PP", false);

        assertThrows(Exception.class, () -> jwtService.verify(token));
    }

    @Test
    void verify_token_vide_leve_exception() {
        assertThrows(Exception.class, () -> jwtService.verify(""));
    }

    @Test
    void verify_token_malformed_leve_exception() {
        assertThrows(Exception.class, () -> jwtService.verify("pas.un.jwt"));
    }
}
