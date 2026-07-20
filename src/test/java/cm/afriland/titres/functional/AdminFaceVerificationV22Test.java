package cm.afriland.titres.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
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
 * Verification faciale back-office (V22) : un agent (CLIENT_MANAGE) relit la
 * capture (image + reperes) puis valide/rejette. Un client ne peut pas y acceder.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({ "unchecked", "rawtypes" })
class AdminFaceVerificationV22Test {

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
        r.add("app.upload-dir", () -> System.getProperty("java.io.tmpdir") + "/afb-test-uploads");
    }

    @LocalServerPort
    int port;

    final BasicCookieStore cookieStore = new BasicCookieStore();
    RestTemplate rest;

    String dossierId;
    String tokenAgent;
    private static final String IMG = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2Q==";

    @BeforeAll
    void setUp() {
        rest = client(cookieStore);
        // 1) Un prospect s'inscrit et depose sa capture faciale. Compte especes
        // UNIQUE : un numero ne peut appartenir qu'a un seul titulaire (sauf joint).
        String email = "adm+" + UUID.randomUUID() + "@example.cm";
        String compteEspeces = "10005" + String.format("%018d",
                Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000_000_000_000L));
        ResponseEntity<Map> reg = POST("/api/v1/registration/register", Map.of(
                "email", email, "password", "MotDePasse1", "nom", "ESSOMBA",
                "typePersonne", "PP", "compteEspeces", compteEspeces), null);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.OK);
        dossierId = String.valueOf(reg.getBody().get("dossierId"));
        String tokenProspect = (String) ((Map) reg.getBody().get("auth")).get("accessToken");

        ResponseEntity<Map> face = POST("/api/v1/registration/dossiers/" + dossierId + "/face", Map.of(
                "imageBase64", IMG, "landmarksJson", "[[0.1,0.2,0.3],[0.4,0.5,0.6]]",
                "livenessScore", 0.95, "livenessPassed", true, "challengeType", "BLINK",
                "width", 640, "height", 480), tokenProspect);
        assertThat(face.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2) Un agent back-office se connecte (CLIENT_MANAGE).
        tokenAgent = login("agent@afriland.cm");
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

    ResponseEntity<List> GETLIST(String p, String token) {
        return rest.exchange(url(p), HttpMethod.GET, body(null, token), List.class);
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
    void agent_recupere_l_image_et_les_reperes() {
        ResponseEntity<Map> r = GET("/api/v1/admin/registrations/" + dossierId + "/face", tokenAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(r.getBody().get("imageBase64"))).startsWith("data:image/jpeg;base64,");
        assertThat(String.valueOf(r.getBody().get("landmarksJson"))).contains("0.1");
        assertThat(r.getBody().get("verificationStatus")).isEqualTo("EN_ATTENTE");
    }

    @Test
    void la_capture_apparait_dans_la_file() {
        ResponseEntity<List> r = GETLIST("/api/v1/admin/registrations", tokenAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        boolean present = r.getBody().stream()
                .anyMatch(row -> dossierId.equals(String.valueOf(((Map) row).get("dossier_id"))));
        assertThat(present).isTrue();
    }

    @Test
    void agent_valide_la_capture_faciale() {
        ResponseEntity<Map> v = POST("/api/v1/admin/registrations/" + dossierId + "/face/verify",
                Map.of("status", "VALIDE"), tokenAgent);
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(v.getBody().get("verificationStatus")).isEqualTo("VALIDE");

        ResponseEntity<Map> f = GET("/api/v1/admin/registrations/" + dossierId + "/face", tokenAgent);
        assertThat(f.getBody().get("verificationStatus")).isEqualTo("VALIDE");
    }

    @Test
    void verify_refuse_un_statut_invalide() {
        ResponseEntity<Map> v = POST("/api/v1/admin/registrations/" + dossierId + "/face/verify",
                Map.of("status", "PEUT_ETRE"), tokenAgent);
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void un_client_ne_peut_pas_acceder_a_la_verification() {
        String tokenClient = login("jean.mballa@example.cm");
        ResponseEntity<Map> r = GET("/api/v1/admin/registrations/" + dossierId + "/face", tokenClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
