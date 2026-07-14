package cm.afriland.titres.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
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
 * Parcours d'auto-inscription (V22) : creation de compte + dossier, capture
 * faciale (vivacite re-controlee serveur), cloisonnement par proprietaire,
 * lecture de la convention.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({ "unchecked", "rawtypes" })
class SelfRegistrationV22Test {

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

    final BasicCookieStore cookieStore = new BasicCookieStore();
    RestTemplate rest;

    // Dossier cree une fois pour la classe.
    String emailPrincipal;
    String tokenPrincipal;
    String dossierId;

    private static final String IMG = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2Q==";

    @BeforeAll
    void setUp() {
        rest = client(cookieStore);
        emailPrincipal = "auto+" + UUID.randomUUID() + "@example.cm";
        ResponseEntity<Map> r = POST("/api/v1/registration/register", Map.of(
                "email", emailPrincipal, "password", "MotDePasse1", "nom", "NGONO",
                "prenom", "Alice", "telephone", "+237690000000",
                "typePersonne", "PP", "compteEspeces", "1000500010000004320768"), null);
        assertThat(r.getStatusCode()).as("register").isEqualTo(HttpStatus.OK);
        dossierId = String.valueOf(r.getBody().get("dossierId"));
        Map auth = (Map) r.getBody().get("auth");
        tokenPrincipal = (String) auth.get("accessToken");
        assertThat(dossierId).isNotBlank();
        assertThat(tokenPrincipal).isNotBlank();
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

    String login(String email) {
        ResponseEntity<Map> s1 = POST("/api/v1/auth/login",
                Map.of("email", email, "password", "Demo1234"), null);
        assertThat(s1.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> s2 = POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", s1.getBody().get("challengeId"), "code", "123456"), null);
        return (String) s2.getBody().get("accessToken");
    }

    // ═══════════════════════════════ Tests ════════════════════════════════════

    @Test
    void register_cree_un_dossier_et_ouvre_une_session() {
        // Fait dans setUp ; on verifie la reprise du dossier.
        ResponseEntity<Map> d = GET("/api/v1/registration/dossiers/" + dossierId, tokenPrincipal);
        assertThat(d.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(d.getBody().get("statut")).isEqualTo("BROUILLON");
        assertThat(d.getBody().get("faceCaptured")).isEqualTo(false);
    }

    @Test
    void register_refuse_un_email_deja_utilise() {
        ResponseEntity<Map> r = POST("/api/v1/registration/register", Map.of(
                "email", emailPrincipal, "password", "MotDePasse1", "nom", "DUP",
                "typePersonne", "PP", "compteEspeces", "1000500010000004320768"), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void register_refuse_un_compte_especes_de_mauvaise_longueur() {
        ResponseEntity<Map> r = POST("/api/v1/registration/register", Map.of(
                "email", "x" + UUID.randomUUID() + "@example.cm", "password", "MotDePasse1",
                "nom", "COURT", "typePersonne", "PP", "compteEspeces", "123"), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void face_refusee_si_vivacite_insuffisante() {
        ResponseEntity<Map> r = POST("/api/v1/registration/dossiers/" + dossierId + "/face", Map.of(
                "imageBase64", IMG, "livenessScore", 0.2, "livenessPassed", false,
                "challengeType", "BLINK"), tokenPrincipal);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void face_acceptee_quand_vivacite_ok_puis_marquee_capturee() {
        ResponseEntity<Map> r = POST("/api/v1/registration/dossiers/" + dossierId + "/face", Map.of(
                "imageBase64", IMG, "landmarksJson", "[[0.1,0.2,0.3]]",
                "livenessScore", 0.92, "livenessPassed", true, "challengeType", "BLINK",
                "width", 640, "height", 480), tokenPrincipal);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("faceId")).isNotNull();
        assertThat(r.getBody().get("verificationStatus")).isEqualTo("EN_ATTENTE");

        ResponseEntity<Map> d = GET("/api/v1/registration/dossiers/" + dossierId, tokenPrincipal);
        assertThat(d.getBody().get("faceCaptured")).isEqualTo(true);
    }

    @Test
    void un_autre_utilisateur_ne_peut_pas_lire_le_dossier() {
        String autre = login("jean.mballa@example.cm");
        ResponseEntity<Map> d = GET("/api/v1/registration/dossiers/" + dossierId, autre);
        assertThat(d.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void dossier_inexistant_renvoie_404() {
        ResponseEntity<Map> d = GET(
                "/api/v1/registration/dossiers/" + UUID.randomUUID(), tokenPrincipal);
        assertThat(d.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void convention_courante_est_publique() {
        ResponseEntity<Map> r = GET("/api/v1/registration/convention?langue=FR", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("version")).isEqualTo("2026-01");
        assertThat(String.valueOf(r.getBody().get("contenuHtml"))).contains("Article 1");
    }
}
