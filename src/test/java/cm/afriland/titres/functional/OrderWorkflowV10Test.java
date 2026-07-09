package cm.afriland.titres.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
 * Tests fonctionnels end-to-end du cycle de vie des ordres et des chemins
 * restants (import portefeuille, rejet/archivage/publication groupee des
 * emissions, OTP d'action, consultation de solde detaillee). Complete
 * {@link WorkflowV10Test} et {@link AdminWorkflowV10Test} pour maximiser la
 * couverture des controleurs.
 *
 * <p>Meme base PostgreSQL externe dediee que {@link WorkflowV10Test}.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings("unchecked")
class OrderWorkflowV10Test {

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

    String tokAgent, tokSup, tokClient;
    String emId;

    final BasicCookieStore cookieStore = new BasicCookieStore();
    RestTemplate rest;

    @BeforeAll
    void setUp() {
        CloseableHttpClient http = HttpClients.custom().setDefaultCookieStore(cookieStore).build();
        rest = new RestTemplate(new HttpComponentsClientHttpRequestFactory(http));
        rest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) throws IOException { return false; }
        });
        tokAgent = login("agent@afriland.cm");
        tokSup = login("superviseur@afriland.cm");
        tokClient = login("jean.mballa@example.cm");

        // Emission publiee dediee a ce parcours d'ordres.
        ResponseEntity<Map> create = POST("/api/v1/emissions", emissionBody(), tokAgent);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        emId = (String) create.getBody().get("id");
        POST("/api/v1/emissions/" + emId + "/publish", null, tokSup);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

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

    ResponseEntity<Map> PATCH(String path, Object payload, String token) {
        return rest.exchange(url(path), HttpMethod.PATCH, body(payload, token), Map.class);
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

    String submitOrder(double taux) {
        ResponseEntity<Map> r = POST("/api/v1/orders",
                Map.of("emissionId", emId, "volume", 2, "tauxSoumis", taux), tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    // ══════════════════════ Cycle de vie d'un ordre ═══════════════════════════

    @Test @Order(1)
    void client_soumet_consulte_modifie_annule_ordre() {
        String ord = submitOrder(5.0);

        ResponseEntity<Map> get = GET("/api/v1/orders/" + ord, tokClient);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> list = GET("/api/v1/orders?page=1&size=20", tokClient);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).containsKey("data");

        // Modification avant prise en charge par un agent.
        ResponseEntity<Map> patch = PATCH("/api/v1/orders/" + ord,
                Map.of("volume", 3, "tauxSoumis", 5.25), tokClient);
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Annulation d'un ordre SOUMIS.
        ResponseEntity<Map> cancel = POST("/api/v1/orders/" + ord + "/cancel", null, tokClient);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancel.getBody().get("status")).isEqualTo("ANNULE");
    }

    @Test @Order(2)
    void agent_annote_verifie_signature_puis_rejette_ordre() {
        String ord = submitOrder(5.5);

        ResponseEntity<Map> notes = POST("/api/v1/orders/" + ord + "/notes",
                Map.of("notes", "Verification KYC OK"), tokAgent);
        assertThat(notes.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> sig = POST("/api/v1/orders/" + ord + "/verify-signature",
                Map.of("verified", true), tokAgent);
        assertThat(sig.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> reject = POST("/api/v1/orders/" + ord + "/reject",
                Map.of("motif", "Documents incomplets, ordre rejete"), tokAgent);
        assertThat(reject.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reject.getBody().get("status")).isEqualTo("ANNULE");
    }

    @Test @Order(3)
    void staff_liste_ordres_avec_filtres() {
        submitOrder(5.1);
        ResponseEntity<Map> byStatus = GET("/api/v1/orders?status=SOUMIS&page=1&size=20", tokAgent);
        assertThat(byStatus.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> byEmission = GET("/api/v1/orders?emissionId=" + emId, tokAgent);
        assertThat(byEmission.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> pending = GET("/api/v1/orders?pendingValidation=true", tokSup);
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test @Order(4)
    void adjudication_partiellement_retenu_puis_reject_result_400() {
        String ord = submitOrder(5.2);

        ResponseEntity<Map> validate = POST("/api/v1/orders/" + ord + "/validate", null, tokAgent);
        assertThat(validate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(validate.getBody().get("status")).isEqualTo("EN_ATTENTE_ADJUDICATION");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PARTIELLEMENT_RETENU");
        result.put("montantAdjuge", 1_000_000);
        result.put("volumeAlloue", 1);
        result.put("tauxAdjuge", 5.2);
        ResponseEntity<Map> adj = POST("/api/v1/orders/" + ord + "/status", result, tokAgent);
        assertThat(adj.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adj.getBody().get("status")).isEqualTo("PARTIELLEMENT_RETENU");

        // Adjudication directe : plus rien « en attente » → validate-result refuse.
        ResponseEntity<Map> rr = POST("/api/v1/orders/" + ord + "/reject-result", null, tokSup);
        assertThat(rr.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test @Order(5)
    void client_liste_cosignatures_en_attente() {
        ResponseEntity<?> r = rest.exchange(url("/api/v1/orders/cosignatures/pending"),
                HttpMethod.GET, noBody(tokClient), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test @Order(6)
    void modification_ordre_par_non_proprietaire_refusee() {
        String ord = submitOrder(5.3);
        // Un agent (non client) ne peut pas modifier un ordre via PATCH.
        ResponseEntity<Map> r = PATCH("/api/v1/orders/" + ord,
                Map.of("volume", 4, "tauxSoumis", 5.3), tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test @Order(7)
    void ordre_inexistant_renvoie_404() {
        ResponseEntity<Map> r = GET(
                "/api/v1/orders/00000000-0000-0000-0000-000000000000", tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ══════════════════════ Import de portefeuille ════════════════════════════

    @Test @Order(10)
    void agent_importe_positions_portefeuille() {
        // Reference l'emission seed (ISIN CM0000DEMO01) et le compte-titres du
        // client seed jean.mballa (resolution par numeroCompte).
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("isin", "CM0000DEMO01");
        row.put("designation", "BTA Cameroun démo");
        row.put("numeroCompte", "037 10001 00012345678");
        row.put("clientNom", "MBALLA Jean Paul");
        row.put("volume", 5);
        row.put("montantAdjuge", 5_000_000);
        row.put("tauxNominal", 5.25);

        ResponseEntity<Map> r = POST("/api/v1/portfolio/import",
                Map.of("rows", List.of(row)), tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) r.getBody().get("imported")).intValue()).isGreaterThanOrEqualTo(1);
    }

    // ══════════════════════ Emissions — rejet / archivage / groupe ════════════

    @Test @Order(20)
    void superviseur_rejette_emission_brouillon() {
        ResponseEntity<Map> create = POST("/api/v1/emissions", emissionBody(), tokAgent);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) create.getBody().get("id");

        ResponseEntity<Map> reject = POST("/api/v1/emissions/" + id + "/reject",
                Map.of("motif", "Parametres incorrects, a revoir"), tokSup);
        assertThat(reject.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test @Order(21)
    void cycle_publie_cloture_archive() {
        ResponseEntity<Map> create = POST("/api/v1/emissions", emissionBody(), tokAgent);
        String id = (String) create.getBody().get("id");

        assertThat(POST("/api/v1/emissions/" + id + "/publish", null, tokSup).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(POST("/api/v1/emissions/" + id + "/close", null, tokSup).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> archive = POST("/api/v1/emissions/" + id + "/archive", null, tokSup);
        assertThat(archive.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(archive.getBody().get("status")).isEqualTo("ARCHIVE");
    }

    @Test @Order(22)
    void superviseur_publie_tous_les_brouillons() {
        // Cree un brouillon supplementaire puis publie en masse.
        POST("/api/v1/emissions", emissionBody(), tokAgent);
        ResponseEntity<Map> r = POST("/api/v1/emissions/publish-all", null, tokSup);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("published");
    }

    @Test @Order(23)
    void staff_diffuse_catalogue_emissions() {
        ResponseEntity<Map> recipients = rest.exchange(
                url("/api/v1/emissions/broadcast/recipients"),
                HttpMethod.GET, noBody(tokSup), Map.class);
        assertThat(recipients.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> broadcast = POST("/api/v1/emissions/broadcast",
                Map.of("recipients", List.of("jean.mballa@example.cm")), tokSup);
        // Messagerie desactivee en test → l'envoi est simule ; la reponse reste 200.
        assertThat(broadcast.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ══════════════════════ OTP d'action (utilisateur connecte) ═══════════════

    @Test @Order(30)
    void client_demande_et_verifie_otp_action() {
        ResponseEntity<Map> send = POST("/api/v1/auth/otp/send", null, tokClient);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.OK);
        String challengeId = (String) send.getBody().get("challengeId");
        assertThat(challengeId).isNotBlank();

        ResponseEntity<Map> verify = POST("/api/v1/auth/otp/verify",
                Map.of("challengeId", challengeId, "code", "123456"), tokClient);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ══════════════════════ Auth — echecs de connexion ═══════════════════════

    @Test @Order(31)
    void login_mauvais_mot_de_passe_401() {
        ResponseEntity<Map> r = POST("/api/v1/auth/login",
                Map.of("email", "agent@afriland.cm", "password", "MauvaisMotDePasse"), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test @Order(32)
    void login_email_inconnu_401() {
        ResponseEntity<Map> r = POST("/api/v1/auth/login",
                Map.of("email", "inconnu@nulle.part", "password", "Demo1234"), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ══════════════════════ Consultation de solde — detail ════════════════════

    @Test @Order(40)
    void staff_consulte_detail_solde_AIF_indisponible() {
        // AIF non configure → degradation gracieuse (200, champs nuls).
        ResponseEntity<Map> r = GET("/api/v1/account-balance/037-XAF-1234567-01", tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("accountId");
    }

    // ─── Helper de payload ──────────────────────────────────────────────────

    Map<String, Object> emissionBody() {
        String suffix = String.valueOf(System.nanoTime()).substring(8);
        String isin = ("CO" + suffix + "0000000").substring(0, 12).toUpperCase();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", "BTA-ORD-" + suffix);
        m.put("isin", isin);
        m.put("libelle", "BTA Order Test 13 semaines");
        m.put("nature", "BTA");
        m.put("paysCode", "CMR");
        m.put("dateEmission", LocalDate.now().toString());
        m.put("ouvertureSouscription", LocalDate.now().plusDays(1) + "T08:00:00Z");
        m.put("fermetureSouscription", LocalDate.now().plusDays(8) + "T09:00:00Z");
        m.put("dateEcheance", LocalDate.now().plusDays(94).toString());
        m.put("dateReglement", LocalDate.now().plusDays(9).toString());
        m.put("valeurNominaleUnitaire", 1_000_000);
        m.put("montantGlobal", 5_000_000_000L);
        m.put("tauxNominal", 0.0);
        m.put("montantMinimum", 1_000_000);
        m.put("modeAdjudication", "TAUX");
        return m;
    }
}
