package cm.afriland.titres.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Interrupteur GLOBAL de la capture faciale (V35).
 *
 * <p>Verifie que : l'etat est lisible sans authentification par le wizard
 * ({@code /registration/parametres}), que seul un administrateur peut le
 * modifier, et que le basculement se reflete dans l'etat renvoye.</p>
 *
 * <p>Chaque test remet le reglage a ACTIVE (defaut) pour ne pas perturber les
 * autres tests qui partagent la meme base non purgee.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({ "unchecked", "rawtypes" })
class FaceScanSettingsV35Test {

    private static String env(String key, String def) {
        String v = System.getenv(key);
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
        r.add("app.upload-dir", () -> System.getProperty("java.io.tmpdir") + "/afb-test-uploads");
    }

    @LocalServerPort
    int port;

    RestTemplate rest;
    String admin;
    String agent;

    @BeforeAll
    void setUp() {
        rest = client();
        admin = login("admin@afriland.cm");
        agent = login("agent@afriland.cm");
    }

    /** Filet de securite : quoi qu'il arrive, on rend la capture faciale ACTIVE. */
    @AfterEach
    void reactiver() {
        PUT("/api/v1/admin/face-scan-settings", Map.of("enabled", true), admin);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    RestTemplate client() {
        CloseableHttpClient http = HttpClients.custom()
                .setDefaultCookieStore(new BasicCookieStore()).build();
        RestTemplate t = new RestTemplate(new HttpComponentsClientHttpRequestFactory(http));
        t.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) throws IOException { return false; }
        });
        return t;
    }

    String url(String p) { return "http://localhost:" + port + p; }

    HttpEntity<Object> body(Object payload, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return new HttpEntity<>(payload, h);
    }

    ResponseEntity<Map> POST(String p, Object payload, String token) {
        return rest.exchange(url(p), HttpMethod.POST, body(payload, token), Map.class);
    }

    ResponseEntity<Map> GET(String p, String token) {
        return rest.exchange(url(p), HttpMethod.GET, body(null, token), Map.class);
    }

    ResponseEntity<Map> PUT(String p, Object payload, String token) {
        return rest.exchange(url(p), HttpMethod.PUT, body(payload, token), Map.class);
    }

    String login(String email) {
        ResponseEntity<Map> s1 = POST("/api/v1/auth/login",
                Map.of("email", email, "password", "Demo1234"), null);
        ResponseEntity<Map> s2 = POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", s1.getBody().get("challengeId"), "code", "123456"), null);
        return (String) s2.getBody().get("accessToken");
    }

    // ═══════════════════════════════ Tests ════════════════════════════════════

    @Test
    void par_defaut_la_capture_faciale_est_activee() {
        ResponseEntity<Map> r = GET("/api/v1/admin/face-scan-settings", admin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("enabled")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void le_wizard_lit_le_reglage_sans_authentification() {
        // /registration/parametres est public : le prospect n'est pas encore connecte.
        ResponseEntity<Map> r = GET("/api/v1/registration/parametres", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("faceScanEnabled")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void un_admin_desactive_puis_le_wizard_voit_le_changement() {
        ResponseEntity<Map> maj = PUT("/api/v1/admin/face-scan-settings",
                Map.of("enabled", false), admin);
        assertThat(maj.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(maj.getBody().get("enabled")).isEqualTo(Boolean.FALSE);

        ResponseEntity<Map> vu = GET("/api/v1/registration/parametres", null);
        assertThat(vu.getBody().get("faceScanEnabled")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void un_agent_ne_peut_pas_modifier_le_reglage() {
        ResponseEntity<Map> r = PUT("/api/v1/admin/face-scan-settings",
                Map.of("enabled", false), agent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Le reglage n'a pas bouge.
        ResponseEntity<Map> etat = GET("/api/v1/admin/face-scan-settings", admin);
        assertThat(etat.getBody().get("enabled")).isEqualTo(Boolean.TRUE);
    }
}
