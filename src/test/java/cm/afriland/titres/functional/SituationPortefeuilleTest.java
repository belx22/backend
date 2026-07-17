package cm.afriland.titres.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
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
 * Situation de portefeuille titres : l'agent l'edite pour un client et la lui
 * transmet (livrable). Le PDF est genere serveur a partir des positions en base.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({ "unchecked", "rawtypes" })
class SituationPortefeuilleTest {

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
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
    }

    @LocalServerPort
    int port;

    String tokAgent, tokClient;
    String clientId;

    final BasicCookieStore cookieStore = new BasicCookieStore();
    RestTemplate rest;

    @BeforeAll
    void setUp() {
        CloseableHttpClient http = HttpClients.custom().setDefaultCookieStore(cookieStore).build();
        rest = new RestTemplate(new HttpComponentsClientHttpRequestFactory(http));
        rest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
        });
        tokAgent = login("agent@afriland.cm");
        tokClient = login("jean.mballa@example.cm");

        // Un client existant (referentiel) sert de destinataire du releve.
        ResponseEntity<Map> clients = GET("/api/v1/clients?size=1", tokAgent);
        assertThat(clients.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> data = (List<Map<String, Object>>) clients.getBody().get("data");
        assertThat(data).isNotEmpty();
        clientId = (String) data.get(0).get("id");
    }

    String url(String path) { return "http://localhost:" + port + path; }

    HttpEntity<Object> body(Object payload, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return new HttpEntity<>(payload, h);
    }

    HttpEntity<Void> noBody(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }

    ResponseEntity<Map> POST(String path, Object payload, String token) {
        return rest.exchange(url(path), HttpMethod.POST, body(payload, token), Map.class);
    }

    ResponseEntity<Map> GET(String path, String token) {
        return rest.exchange(url(path), HttpMethod.GET, noBody(token), Map.class);
    }

    String login(String email) {
        ResponseEntity<Map> step1 = POST("/api/v1/auth/login",
                Map.of("email", email, "password", "Demo1234"), null);
        assertThat(step1.getStatusCode()).as("login %s", email).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> step2 = POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", step1.getBody().get("challengeId"), "code", "123456"), null);
        assertThat(step2.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) step2.getBody().get("accessToken");
    }

    // ═══════════════════════════════════════════════════════════════════════════

    /** L'agent edite le releve : cree un livrable PDF pour le client. */
    @Test
    void l_agent_edite_et_transmet_la_situation_de_portefeuille() {
        ResponseEntity<Map> r = POST("/api/v1/documents/situation-portefeuille",
                Map.of("clientId", clientId, "periode", "2026-06-16", "service", "TRESORERIE"), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("type")).isEqualTo("SITUATION_PORTEFEUILLE");
        assertThat(r.getBody().get("mimeType")).isEqualTo("application/pdf");
        assertThat(String.valueOf(r.getBody().get("titre"))).contains("Situation de portefeuille");
        assertThat(r.getBody().get("clientId")).isEqualTo(clientId);

        // Le contenu est un vrai PDF, genere serveur.
        String docId = (String) r.getBody().get("id");
        ResponseEntity<Map> detail = GET("/api/v1/documents/" + docId, tokAgent);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(detail.getBody().get("contenu"))).startsWith("data:application/pdf");
    }

    /** Sans période, le relevé est édité à la date du jour (période facultative). */
    @Test
    void la_periode_est_facultative() {
        ResponseEntity<Map> r = POST("/api/v1/documents/situation-portefeuille",
                Map.of("clientId", clientId), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("type")).isEqualTo("SITUATION_PORTEFEUILLE");
    }

    /** Une période mal formée est rejetée. */
    @Test
    void une_periode_invalide_est_rejetee() {
        ResponseEntity<Map> r = POST("/api/v1/documents/situation-portefeuille",
                Map.of("clientId", clientId, "periode", "16/06/2026"), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Un destinataire inexistant est rejeté (le relevé n'a pas de client). */
    @Test
    void un_destinataire_inexistant_est_rejete() {
        ResponseEntity<Map> r = POST("/api/v1/documents/situation-portefeuille",
                Map.of("clientId", UUID.randomUUID().toString()), tokAgent);

        assertThat(r.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND);
    }

    /** Un client ne peut pas éditer de relevé (réservé au back-office). */
    @Test
    void un_client_ne_peut_pas_editer_de_releve() {
        ResponseEntity<Map> r = POST("/api/v1/documents/situation-portefeuille",
                Map.of("clientId", clientId), tokClient);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** clientId manquant : requête invalide. */
    @Test
    void le_client_est_obligatoire() {
        ResponseEntity<Map> r = POST("/api/v1/documents/situation-portefeuille",
                Map.of("periode", "2026-06-16"), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ═══════════ Attestation de propriété pour un titre détenu ═════════════════

    private static String isinUnique() {
        return ("CM" + UUID.randomUUID().toString().replace("-", "")).substring(0, 12).toUpperCase();
    }

    /** Une émission existante (le seed en publie deux) : id + ISIN. */
    Map<String, String> uneEmission() {
        ResponseEntity<Map> ems = GET("/api/v1/emissions?size=1", tokAgent);
        assertThat(ems.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> data = (List<Map<String, Object>>) ems.getBody().get("data");
        assertThat(data).isNotEmpty();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("id", (String) data.get(0).get("id"));
        out.put("isin", (String) data.get(0).get("isin"));
        return out;
    }

    /** Donne au client une position sur ce titre via l'import de portefeuille. */
    void importerPosition(String emId, String isin) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("emissionId", emId);
        row.put("isin", isin);
        row.put("clientId", clientId);
        row.put("volume", 5);
        row.put("montantAdjuge", 50_000_000L);
        row.put("tauxNominal", 6.0);
        ResponseEntity<Map> r = POST("/api/v1/portfolio/import",
                Map.of("rows", List.of(row)), tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("imported")).isEqualTo(1);
    }

    /** L'agent édite l'attestation d'un titre effectivement détenu par le client. */
    @Test
    void l_agent_edite_l_attestation_pour_un_titre_detenu() {
        Map<String, String> em = uneEmission();
        String isin = em.get("isin");
        importerPosition(em.get("id"), isin);

        ResponseEntity<Map> r = POST("/api/v1/documents/attestation-propriete/client",
                Map.of("clientId", clientId, "isin", isin), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("type")).isEqualTo("ATTESTATION_PROPRIETE");
        assertThat(r.getBody().get("mimeType")).isEqualTo("application/pdf");
        assertThat(String.valueOf(r.getBody().get("titre"))).contains(isin);
        assertThat(r.getBody().get("clientId")).isEqualTo(clientId);

        String docId = (String) r.getBody().get("id");
        ResponseEntity<Map> detail = GET("/api/v1/documents/" + docId, tokAgent);
        assertThat(String.valueOf(detail.getBody().get("contenu"))).startsWith("data:application/pdf");
    }

    /** Un titre que le client ne détient pas : attestation sans objet (400). */
    @Test
    void l_attestation_d_un_titre_non_detenu_est_refusee() {
        ResponseEntity<Map> r = POST("/api/v1/documents/attestation-propriete/client",
                Map.of("clientId", clientId, "isin", isinUnique()), tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** L'ISIN du titre est obligatoire. */
    @Test
    void l_attestation_exige_l_isin() {
        ResponseEntity<Map> r = POST("/api/v1/documents/attestation-propriete/client",
                Map.of("clientId", clientId), tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Un client ne peut pas éditer d'attestation (réservé au back-office). */
    @Test
    void l_attestation_est_reservee_au_back_office() {
        ResponseEntity<Map> r = POST("/api/v1/documents/attestation-propriete/client",
                Map.of("clientId", clientId, "isin", isinUnique()), tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
