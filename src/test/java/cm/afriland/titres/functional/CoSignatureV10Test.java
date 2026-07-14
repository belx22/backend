package cm.afriland.titres.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
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
 * Double signature des comptes joints : un ordre soumis par le titulaire reste
 * {@code EN_ATTENTE_SIGNATURES} tant que chaque autre signataire n'a pas valide.
 * Une validation de tous les signataires transmet l'ordre ({@code SOUMIS}) ;
 * un seul refus l'annule.
 *
 * <p>S'appuie sur le jeu de demonstration : {@code joint.titulaire@example.cm}
 * (compte JOINT) et {@code joint.cosignataire@example.cm} (rattache au titulaire
 * par {@code account_holder_id}, sans {@code type_compte} propre).</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({ "unchecked", "rawtypes" })
class CoSignatureV10Test {

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

    String tokAgent, tokSup, tokTitulaire, tokCosignataire, tokClientSimple;
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
        tokTitulaire = login("joint.titulaire@example.cm");
        tokCosignataire = login("joint.cosignataire@example.cm");
        tokClientSimple = login("jean.mballa@example.cm");

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

    ResponseEntity<List> GET_LIST(String path, String token) {
        return rest.exchange(url(path), HttpMethod.GET, noBody(token), List.class);
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
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", "BTA-COS-" + suffix);
        m.put("isin", ("CJ" + suffix + "0000000").substring(0, 12).toUpperCase());
        m.put("libelle", "BTA Co-signature 13 semaines");
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

    /** Ordre soumis par le TITULAIRE du compte joint : il attend la signature du co-titulaire. */
    String ordreJoint(double taux) {
        ResponseEntity<Map> r = POST("/api/v1/orders",
                Map.of("emissionId", emId, "volume", 2, "tauxSoumis", taux), tokTitulaire);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("EN_ATTENTE_SIGNATURES");
        return (String) r.getBody().get("id");
    }

    ResponseEntity<Map> cosign(String ordre, boolean approve, String token) {
        return POST("/api/v1/orders/" + ordre + "/cosign", Map.of("approve", approve), token);
    }

    // ═══════════════════ Soumission depuis un compte joint ════════════════════

    /** Le titulaire soumet : l'ordre n'est PAS transmis, il attend les signatures. */
    @Test
    void un_ordre_de_compte_joint_attend_les_signatures() {
        String ordre = ordreJoint(5.10);

        assertThat(GET("/api/v1/orders/" + ordre, tokTitulaire).getBody().get("status"))
                .isEqualTo("EN_ATTENTE_SIGNATURES");
    }

    /** Un compte individuel n'est jamais mis en attente de signatures. */
    @Test
    void un_ordre_de_compte_individuel_est_soumis_directement() {
        ResponseEntity<Map> r = POST("/api/v1/orders",
                Map.of("emissionId", emId, "volume", 1, "tauxSoumis", 5.11), tokClientSimple);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("SOUMIS");
    }

    // ═════════════════ Liste des validations en attente ═══════════════════════

    /** Le co-signataire voit l'operation dans ses validations en attente. */
    @Test
    void le_cosignataire_voit_l_operation_en_attente() {
        String ordre = ordreJoint(5.12);

        ResponseEntity<List> pending = GET_LIST("/api/v1/orders/cosignatures/pending", tokCosignataire);

        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> lignes = pending.getBody();
        assertThat(lignes).anySatisfy(l -> assertThat(l.get("orderId")).isEqualTo(ordre));
        Map<String, Object> ligne = lignes.stream()
                .filter(l -> ordre.equals(l.get("orderId"))).findFirst().orElseThrow();
        assertThat(ligne.get("reference")).asString().isNotBlank();
        assertThat(ligne.get("emissionCode")).asString().isNotBlank();
        assertThat(ligne.get("expiresAt")).isNotNull();
    }

    /** L'emetteur ne se voit pas demander sa propre signature : il consent en soumettant. */
    @Test
    void le_titulaire_emetteur_n_a_pas_a_signer_sa_propre_operation() {
        String ordre = ordreJoint(5.13);

        List<Map<String, Object>> pending = GET_LIST("/api/v1/orders/cosignatures/pending", tokTitulaire)
                .getBody();

        assertThat(pending).noneSatisfy(l -> assertThat(l.get("orderId")).isEqualTo(ordre));
    }

    /** Un acteur back-office n'a pas de validations en attente : liste vide. */
    @Test
    void un_agent_n_a_aucune_validation_en_attente() {
        assertThat(GET_LIST("/api/v1/orders/cosignatures/pending", tokAgent).getBody()).isEmpty();
    }

    // ══════════════════════════ Validation (approve) ══════════════════════════

    /**
     * Le compte joint ne compte que deux signataires : la validation du
     * co-signataire est la derniere attendue, l'ordre part donc en SOUMIS.
     */
    @Test
    void la_derniere_signature_transmet_l_ordre() {
        String ordre = ordreJoint(5.14);

        ResponseEntity<Map> r = cosign(ordre, true, tokCosignataire);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("SOUMIS");
    }

    /** Un refus annule l'ordre et en donne le motif. */
    @Test
    void un_refus_annule_l_ordre() {
        String ordre = ordreJoint(5.15);

        ResponseEntity<Map> r = cosign(ordre, false, tokCosignataire);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("ANNULE");
        assertThat((String) r.getBody().get("motifAnnulation")).contains("co-signataire");
    }

    /** La reponse renvoyee au signataire est nettoyee : pas de notes internes. */
    @Test
    void la_reponse_de_cosignature_masque_les_donnees_internes() {
        String ordre = ordreJoint(5.16);

        ResponseEntity<Map> r = cosign(ordre, true, tokCosignataire);

        assertThat(r.getBody().get("notes")).isNull();
        assertThat(r.getBody().get("ipSoumission")).isNull();
    }

    // ══════════════════════════════ Gardes ════════════════════════════════════

    /** On ne repond qu'une fois : la seconde reponse est refusee. */
    @Test
    void on_ne_peut_pas_repondre_deux_fois() {
        String ordre = ordreJoint(5.17);
        assertThat(cosign(ordre, true, tokCosignataire).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> seconde = cosign(ordre, true, tokCosignataire);

        assertThat(seconde.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Un client etranger au compte n'a aucune demande en attente sur cet ordre. */
    @Test
    void un_client_etranger_au_compte_ne_peut_pas_cosigner() {
        String ordre = ordreJoint(5.18);

        ResponseEntity<Map> r = cosign(ordre, true, tokClientSimple);

        assertThat(r.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN);
    }

    /** Le back-office ne signe jamais a la place d'un client. */
    @Test
    void un_agent_ne_peut_pas_cosigner() {
        String ordre = ordreJoint(5.19);

        ResponseEntity<Map> r = cosign(ordre, true, tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** L'emetteur lui-meme n'a pas de demande de signature : il ne peut pas cosigner. */
    @Test
    void le_titulaire_emetteur_ne_peut_pas_cosigner_son_ordre() {
        String ordre = ordreJoint(5.20);

        ResponseEntity<Map> r = cosign(ordre, true, tokTitulaire);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Un ordre deja transmis n'est plus en attente de signatures. */
    @Test
    void on_ne_cosigne_pas_un_ordre_deja_transmis() {
        String ordre = ordreJoint(5.21);
        assertThat(cosign(ordre, true, tokCosignataire).getStatusCode()).isEqualTo(HttpStatus.OK);

        // L'ordre est SOUMIS : plus aucune demande de signature n'est active.
        assertThat(cosign(ordre, false, tokCosignataire).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void cosigner_un_ordre_inexistant_renvoie_404() {
        ResponseEntity<Map> r = cosign("00000000-0000-0000-0000-000000000000", true, tokCosignataire);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Le drapeau `approve` est obligatoire. */
    @Test
    void cosigner_sans_decision_est_rejete() {
        String ordre = ordreJoint(5.22);

        ResponseEntity<Map> r = POST("/api/v1/orders/" + ordre + "/cosign",
                new LinkedHashMap<>(), tokCosignataire);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Apres validation, l'operation disparait des validations en attente. */
    @Test
    void l_operation_signee_quitte_la_liste_des_validations_en_attente() {
        String ordre = ordreJoint(5.23);
        cosign(ordre, true, tokCosignataire);

        List<Map<String, Object>> pending = GET_LIST("/api/v1/orders/cosignatures/pending",
                tokCosignataire).getBody();

        assertThat(pending).noneSatisfy(l -> assertThat(l.get("orderId")).isEqualTo(ordre));
    }
}
