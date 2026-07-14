package cm.afriland.titres.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Expiration de session par INACTIVITE (V27).
 *
 * <p>Defaut corrige : le cookie de rafraichissement vivait 30 jours. Fermer
 * l'onglet, revenir une heure plus tard et saisir l'adresse du back-office
 * reconnectait automatiquement. Le garde-fou d'inactivite etait cote navigateur
 * (IdleService) : onglet ferme, aucun minuteur, donc aucune expiration.</p>
 *
 * <p>Desormais le SERVEUR verifie la derniere activite a chaque refresh.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({ "unchecked", "rawtypes" })
class SessionInactiviteV27Test {

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? def : v;
    }

    @DynamicPropertySource
    static void appProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",
                () -> env("TEST_DB_URL", "jdbc:postgresql://localhost:5433/afb_titres_test"));
        r.add("spring.datasource.username", () -> env("TEST_DB_USER", "afb_app"));
        r.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "change_me_db"));
        r.add("app.jwt-secret", () -> "test-jwt-secret-au-moins-32-caracteres-long!!");
        r.add("app.mfa-dev-code", () -> "123456");
        r.add("app.seed-on-start", () -> "true");
        // Fenetre d'inactivite courte pour le test.
        r.add("app.session-idle-timeout", () -> "900");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    BasicCookieStore cookieStore;
    RestTemplate rest;

    @BeforeAll
    void setUp() {
        cookieStore = new BasicCookieStore();
        rest = client(cookieStore);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    RestTemplate client(BasicCookieStore store) {
        CloseableHttpClient http = HttpClients.custom().setDefaultCookieStore(store).build();
        RestTemplate t = new RestTemplate(new HttpComponentsClientHttpRequestFactory(http));
        t.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) throws IOException { return false; }
        });
        return t;
    }

    String url(String p) { return "http://localhost:" + port + p; }

    ResponseEntity<Map> POST(String p, Object payload) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(p), HttpMethod.POST, new HttpEntity<>(payload, h), Map.class);
    }

    /** Connexion complete : le cookie de rafraichissement est pose dans le store. */
    void connecter(String email) {
        ResponseEntity<Map> s1 = POST("/api/v1/auth/login",
                Map.of("email", email, "password", "Demo1234"));
        assertThat(s1.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> s2 = POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", s1.getBody().get("challengeId"), "code", "123456"));
        assertThat(s2.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Simule une absence : recule la derniere activite de la session de l'utilisateur. */
    void simulerInactivite(String email, int minutes) {
        jdbc.update(
                "UPDATE refresh_tokens SET last_used_at = now() - (? || ' minutes')::interval "
                        + "WHERE revoked = FALSE AND user_id = (SELECT id FROM users WHERE email = ?)",
                String.valueOf(minutes), email);
    }

    // ═══════════════════════════════ Tests ════════════════════════════════════

    @Test
    void une_session_active_se_rafraichit_normalement() {
        connecter("agent@afriland.cm");

        ResponseEntity<Map> r = POST("/api/v1/auth/refresh", Map.of());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("accessToken")).isNotNull();
    }

    @Test
    void apres_UNE_HEURE_d_inactivite_le_retour_ne_reconnecte_PLUS() {
        connecter("superviseur@afriland.cm");
        simulerInactivite("superviseur@afriland.cm", 60);   // l'utilisateur s'absente 1 h

        // Il revient et saisit l'adresse du back-office : l'application tente un refresh.
        ResponseEntity<Map> r = POST("/api/v1/auth/refresh", Map.of());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void l_inactivite_revoque_TOUTE_la_session_pas_seulement_le_jeton_presente() {
        connecter("admin@afriland.cm");
        simulerInactivite("admin@afriland.cm", 60);

        POST("/api/v1/auth/refresh", Map.of());   // declenche la revocation

        Long actifs = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE revoked = FALSE "
                        + "AND user_id = (SELECT id FROM users WHERE email = ?)",
                Long.class, "admin@afriland.cm");
        assertThat(actifs).isZero();
    }

    @Test
    void une_absence_plus_courte_que_la_fenetre_reste_acceptee() {
        connecter("jean.mballa@example.cm");
        simulerInactivite("jean.mballa@example.cm", 5);     // 5 min < 15 min

        ResponseEntity<Map> r = POST("/api/v1/auth/refresh", Map.of());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void le_rafraichissement_reporte_la_fenetre_d_inactivite() {
        connecter("joint.titulaire@example.cm");
        simulerInactivite("joint.titulaire@example.cm", 10);  // encore dans la fenetre

        // Un refresh reussi vaut activite : la session repart pour 15 min.
        assertThat(POST("/api/v1/auth/refresh", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Le jeton neuf porte une activite fraiche : un nouveau refresh passe.
        assertThat(POST("/api/v1/auth/refresh", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ─── Le second facteur ne doit pas etre contournable ──────────────────────

    /**
     * Rester sur l'ecran OTP sans rien saisir ne doit JAMAIS reconnecter.
     *
     * <p>C'etait la faille : engager une nouvelle connexion laissait intact le
     * cookie de rafraichissement de la session precedente (valable 30 jours). Le
     * defi MFA ne protegeait donc rien — il suffisait d'attendre que le jeton
     * d'acces expire (5 min) pour qu'un rafraichissement rouvre une session, sans
     * que le code n'ait jamais ete valide.</p>
     */
    @Test
    void engager_une_connexion_revoque_la_session_du_navigateur_AVANT_l_OTP() {
        connecter("superviseur@afriland.cm");
        // La session est bien active a ce stade.
        assertThat(POST("/api/v1/auth/refresh", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Nouvelle connexion : on s'arrete a l'ecran OTP, sans saisir le code.
        ResponseEntity<Map> s1 = POST("/api/v1/auth/login",
                Map.of("email", "superviseur@afriland.cm", "password", "Demo1234"));
        assertThat(s1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(s1.getBody().get("mfaRequired")).isEqualTo(true);

        // Le rafraichissement ne doit plus rien rouvrir : sans OTP, pas de session.
        assertThat(POST("/api/v1/auth/refresh", Map.of()).getStatusCode())
                .as("un refresh sans OTP valide rouvrirait une session : le 2e facteur serait contournable")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Une fois l'OTP saisi, la connexion aboutit normalement. */
    @Test
    void apres_l_OTP_la_session_est_bien_ouverte() {
        connecter("superviseur@afriland.cm");
        POST("/api/v1/auth/login",
                Map.of("email", "superviseur@afriland.cm", "password", "Demo1234"));
        // Refaire une connexion COMPLETE (avec OTP) redonne une session valide.
        connecter("superviseur@afriland.cm");

        assertThat(POST("/api/v1/auth/refresh", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
