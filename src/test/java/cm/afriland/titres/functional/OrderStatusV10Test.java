package cm.afriland.titres.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.Map;

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
 * Transitions de statut d'un ordre via {@code POST /orders/:id/status} — la
 * branche « traitement » de {@code OrderController.changeStatus} (verification,
 * transmission a l'adjudication, annulation motivee), ainsi que les gardes de
 * validation et le rejet de signature.
 *
 * <p>Complete {@code OrderWorkflowV10Test}, qui n'exerce que l'adjudication
 * directe et le raccourci {@code /validate}. Meme base PostgreSQL externe.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({ "unchecked", "rawtypes" })
class OrderStatusV10Test {

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

    String login(String email) {
        ResponseEntity<Map> step1 = POST("/api/v1/auth/login",
                Map.of("email", email, "password", "Demo1234"), null);
        assertThat(step1.getStatusCode()).as("login %s", email).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> step2 = POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", step1.getBody().get("challengeId"), "code", "123456"), null);
        assertThat(step2.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) step2.getBody().get("accessToken");
    }

    Map<String, Object> emissionBody() {
        // Suffixe tire d'un UUID (10 hex ~ 10^12 valeurs) et non de nanoTime tronque :
        // `code` et `isin` sont UNIQUE et la base de test n'est jamais purgee entre
        // les executions, or nanoTime tronque ne laissait que ~10^4 valeurs distinctes.
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String isin = ("CS" + suffix + "0000000").substring(0, 12).toUpperCase();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", "BTA-STA-" + suffix);
        m.put("isin", isin);
        m.put("libelle", "BTA Statut Test 13 semaines");
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

    String submitOrder(double taux) {
        ResponseEntity<Map> r = POST("/api/v1/orders",
                Map.of("emissionId", emId, "volume", 2, "tauxSoumis", taux), tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    ResponseEntity<Map> changeStatus(String ord, Map<String, Object> payload, String token) {
        return POST("/api/v1/orders/" + ord + "/status", payload, token);
    }

    // ═════════════════ Branche « traitement » de changeStatus ═════════════════

    /** EN_VERIFICATION : transition de traitement, sans effet sur l'adjudication. */
    @Test
    void agent_passe_un_ordre_en_verification() {
        String ord = submitOrder(5.4);

        ResponseEntity<Map> r = changeStatus(ord, Map.of("status", "EN_VERIFICATION"), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("EN_VERIFICATION");
    }

    /**
     * EN_ATTENTE_ADJUDICATION via /status : le premier passage doit horodater la
     * validation agent (validated_by_agent renseigne).
     */
    @Test
    void transmission_a_l_adjudication_horodate_la_validation_agent() {
        String ord = submitOrder(5.45);

        ResponseEntity<Map> r = changeStatus(ord, Map.of("status", "EN_ATTENTE_ADJUDICATION"), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("EN_ATTENTE_ADJUDICATION");
        assertThat(r.getBody().get("dateValidationAgent")).isNotNull();
    }

    /** ANNULE avec motif : le motif fourni est enregistre. */
    @Test
    void annulation_par_l_agent_conserve_le_motif() {
        String ord = submitOrder(5.46);

        ResponseEntity<Map> r = changeStatus(ord,
                Map.of("status", "ANNULE", "motif", "  Provision insuffisante  "), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("ANNULE");
        assertThat(r.getBody().get("motifAnnulation")).isEqualTo("Provision insuffisante");
    }

    /** Un ordre clôturé (etat final) ne peut plus changer de statut. */
    @Test
    void un_ordre_cloture_ne_change_plus_de_statut() {
        String ord = submitOrder(5.47);
        changeStatus(ord, Map.of("status", "ANNULE", "motif", "clôture"), tokAgent);

        ResponseEntity<Map> r = changeStatus(ord, Map.of("status", "EN_VERIFICATION"), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void un_statut_inconnu_est_refuse() {
        String ord = submitOrder(5.48);

        ResponseEntity<Map> r = changeStatus(ord, Map.of("status", "PAS_UN_STATUT"), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Un client ne pilote jamais le statut d'un ordre. */
    @Test
    void un_client_ne_peut_pas_changer_le_statut() {
        String ord = submitOrder(5.49);

        ResponseEntity<Map> r = changeStatus(ord, Map.of("status", "EN_VERIFICATION"), tokClient);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ═══════════════════════ Adjudication : gardes ════════════════════════════

    /** Le resultat ne peut etre saisi que sur un ordre transmis a l'adjudication. */
    @Test
    void adjudication_refusee_si_l_ordre_n_est_pas_en_attente() {
        String ord = submitOrder(5.5);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "TOTALEMENT_RETENU");
        result.put("montantAdjuge", 2_000_000);
        ResponseEntity<Map> r = changeStatus(ord, result, tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Champs d'adjudication omis : les valeurs deja portees par l'ordre sont
     * conservees (branche de repli {@code req.x() != null ? … : order.x()}).
     */
    @Test
    void adjudication_sans_montant_conserve_les_valeurs_existantes() {
        String ord = submitOrder(5.51);
        assertThat(changeStatus(ord, Map.of("status", "EN_ATTENTE_ADJUDICATION"), tokAgent)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // NON_RETENU sans montant/volume/taux : aucun NPE, l'ordre est finalise.
        ResponseEntity<Map> r = changeStatus(ord, Map.of("status", "NON_RETENU"), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("NON_RETENU");
        assertThat(r.getBody().get("montantAdjuge")).isNull();
    }

    /**
     * Le superviseur saisit l'adjudication, au meme titre que l'agent.
     *
     * <p>Ce test defendait la regle INVERSE, heritee du modele a deux niveaux ou
     * l'agent proposait et le superviseur validait. L'adjudication est passee a un
     * seul niveau : le resultat prend effet immediatement (ORDER_RESULT). Le
     * superviseur, prive de cette permission, restait bloque — il saisissait un
     * resultat qui n'etait jamais applique.</p>
     */
    @Test
    void le_superviseur_saisit_l_adjudication_comme_l_agent() {
        String ord = submitOrder(5.52);
        changeStatus(ord, Map.of("status", "EN_ATTENTE_ADJUDICATION"), tokAgent);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "TOTALEMENT_RETENU");
        result.put("montantAdjuge", 2_000_000);
        result.put("volumeAlloue", 2);
        result.put("tauxAdjuge", 5.52);
        ResponseEntity<Map> r = changeStatus(ord, result, tokSup);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("TOTALEMENT_RETENU");
    }

    /** Le commentaire de resultat est normalise (espaces de bord retires). */
    @Test
    void adjudication_normalise_le_commentaire() {
        String ord = submitOrder(5.53);
        changeStatus(ord, Map.of("status", "EN_ATTENTE_ADJUDICATION"), tokAgent);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PARTIELLEMENT_RETENU");
        result.put("montantAdjuge", 1_000_000);
        result.put("volumeAlloue", 1);
        result.put("tauxAdjuge", 5.53);
        result.put("commentaire", "  servi à 50 %  ");
        ResponseEntity<Map> r = changeStatus(ord, result, tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("commentaireResultat")).isEqualTo("servi à 50 %");
    }

    // ═════════════════════════ Rejet de signature ═════════════════════════════

    /** Signature rejetee : le client est notifie (branche {@code !verified}). */
    @Test
    void le_rejet_de_signature_notifie_le_client() {
        String ord = submitOrder(5.54);

        ResponseEntity<Map> r = POST("/api/v1/orders/" + ord + "/verify-signature",
                Map.of("verified", false), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("signatureVerified")).isEqualTo(false);
    }

    // ══════════════════════ Ordre inexistant / acces ══════════════════════════

    @Test
    void changement_de_statut_sur_un_ordre_inexistant_renvoie_404() {
        ResponseEntity<Map> r = changeStatus("00000000-0000-0000-0000-000000000000",
                Map.of("status", "EN_VERIFICATION"), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Les notes internes ne sont jamais exposees au client proprietaire. */
    @Test
    void les_notes_internes_sont_masquees_au_client() {
        String ord = submitOrder(5.55);
        assertThat(POST("/api/v1/orders/" + ord + "/notes",
                Map.of("notes", "Dossier a surveiller"), tokAgent).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> vueClient = GET("/api/v1/orders/" + ord, tokClient);
        assertThat(vueClient.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(vueClient.getBody().get("notes")).isNull();
        assertThat(vueClient.getBody().get("ipSoumission")).isNull();

        ResponseEntity<Map> vueAgent = GET("/api/v1/orders/" + ord, tokAgent);
        assertThat(vueAgent.getBody().get("notes")).isEqualTo("Dossier a surveiller");
    }

    // ─── Le superviseur applique le resultat d'adjudication ───────────────────

    /**
     * Un superviseur doit pouvoir APPLIQUER un resultat d'adjudication.
     *
     * <p>C'etait impossible : l'adjudication est passee a un seul niveau (le
     * resultat prend effet immediatement, permission ORDER_RESULT) mais le
     * superviseur ne detenait plus que ORDER_RESULT_VALIDATE — la permission
     * d'une etape disparue. Il saisissait donc son resultat, se voyait refuser
     * l'application (403), et l'ordre restait indefiniment en attente.</p>
     */
    @Test
    void le_superviseur_applique_le_resultat_et_l_ordre_sort_de_l_attente() {
        String ord = submitOrder(5.61);
        changeStatus(ord, Map.of("status", "EN_ATTENTE_ADJUDICATION"), tokAgent);

        ResponseEntity<Map> r = changeStatus(ord, Map.of(
                "status", "TOTALEMENT_RETENU", "tauxAdjuge", 5.61), tokSup);

        assertThat(r.getStatusCode())
                .as("le superviseur doit pouvoir appliquer le resultat — %s", r.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("TOTALEMENT_RETENU");
        assertThat(r.getBody().get("resultatValideAt"))
                .as("le resultat est applique, pas seulement propose").isNotNull();
    }

    /** Le client, lui, ne peut evidemment pas s'auto-adjuger son ordre. */
    @Test
    void un_client_ne_peut_pas_appliquer_de_resultat() {
        String ord = submitOrder(5.62);
        changeStatus(ord, Map.of("status", "EN_ATTENTE_ADJUDICATION"), tokAgent);

        ResponseEntity<Map> r = changeStatus(ord, Map.of("status", "TOTALEMENT_RETENU"), tokClient);

        assertThat(r.getStatusCode()).as("reponse = %s", r.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
