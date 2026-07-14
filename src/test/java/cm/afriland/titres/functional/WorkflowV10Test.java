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
 * Tests fonctionnels end-to-end miroir de test_api_v10.py.
 *
 * Chaque test est ordonne et partage l'etat (tokens, identifiants) via des
 * champs d'instance (@TestInstance PER_CLASS). Le cookie HttpOnly afb_rt est
 * gere par Apache HttpClient 5 avec un CookieStore persistant.
 *
 * <p><b>Base de donnees.</b> Le test cible un PostgreSQL <em>externe</em> (au
 * lieu de Testcontainers) via {@code TEST_DB_URL/USER/PASSWORD} — par defaut la
 * base dediee {@code afb_titres_test} du serveur local (port 5433). Elle doit
 * etre <b>vide</b> au demarrage : Flyway cree le schema et {@code DemoSeedRunner}
 * insere les comptes + emissions de demonstration (seed uniquement si vide).
 * Recreation propre :</p>
 * <pre>
 * docker exec afb_titres_db psql -U afb_app -d postgres \
 *   -c "DROP DATABASE IF EXISTS afb_titres_test;" \
 *   -c "CREATE DATABASE afb_titres_test OWNER afb_app;"
 * </pre>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings("unchecked")
class WorkflowV10Test {

    // ─── Infrastructure ───────────────────────────────────────────────────────

    /** Lit une variable d'environnement avec repli sur la valeur locale par defaut. */
    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    @DynamicPropertySource
    static void appProps(DynamicPropertyRegistry r) {
        // Base PostgreSQL externe dediee aux tests (surchargeable par l'environnement).
        r.add("spring.datasource.url",
                () -> env("TEST_DB_URL", "jdbc:postgresql://localhost:5433/afb_titres_test"));
        r.add("spring.datasource.username", () -> env("TEST_DB_USER", "afb_app"));
        r.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "change_me_db"));
        // Secret JWT valide (>= 32 chars)
        r.add("app.jwt-secret", () -> "test-jwt-secret-au-moins-32-caracteres-long!!");
        // Code OTP fixe pour la demo (aucun SMTP ne sera contacte)
        r.add("app.mfa-dev-code", () -> "123456");
        // Donnees de demo (jean.mballa, agent@afriland.cm, superviseur, admin…)
        r.add("app.seed-on-start", () -> "true");
    }

    @LocalServerPort
    int port;

    // ─── Etat partage entre tests ──────────────────────────────────────────────

    String tokClient, tokAgent, tokSup, tokAdmin;
    String emId, ordId;

    // Cookie store persistant — conserve afb_rt apres /mfa/verify
    final BasicCookieStore cookieStore = new BasicCookieStore();
    RestTemplate rest;

    @BeforeAll
    void setUpRestTemplate() {
        CloseableHttpClient http = HttpClients.custom()
                .setDefaultCookieStore(cookieStore)
                .build();
        rest = new RestTemplate(new HttpComponentsClientHttpRequestFactory(http));
        // Ne jamais lever d'exception sur 4xx/5xx — on veut asserter le code soi-meme
        rest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse r) throws IOException { return false; }
        });
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
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }

    ResponseEntity<Map> POST(String path, Object payload, String token) {
        return rest.exchange(url(path), HttpMethod.POST, body(payload, token), Map.class);
    }

    ResponseEntity<Map> GET(String path, String token) {
        return rest.exchange(url(path), HttpMethod.GET, noBody(token), Map.class);
    }

    ResponseEntity<Map> PUT(String path, Object payload, String token) {
        return rest.exchange(url(path), HttpMethod.PUT, body(payload, token), Map.class);
    }

    ResponseEntity<Void> DELETE(String path, String token) {
        return rest.exchange(url(path), HttpMethod.DELETE, noBody(token), Void.class);
    }

    /** Connexion complete : /login + /mfa/verify ; renvoie l'access token. */
    String login(String email) {
        ResponseEntity<Map> step1 = POST("/api/v1/auth/login",
                Map.of("email", email, "password", "Demo1234"), null);
        assertThat(step1.getStatusCode()).as("login %s", email).isEqualTo(HttpStatus.OK);
        String challengeId = (String) step1.getBody().get("challengeId");

        ResponseEntity<Map> step2 = POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", challengeId, "code", "123456"), null);
        assertThat(step2.getStatusCode()).as("mfa %s", email).isEqualTo(HttpStatus.OK);
        return (String) step2.getBody().get("accessToken");
    }

    /** Construit le corps d'une nouvelle emission avec des dates dynamiques. */
    Map<String, Object> emissionBody(String codePrefix, String isinPrefix) {
        String suffix = String.valueOf(System.currentTimeMillis()).substring(7);
        String isin   = (isinPrefix + suffix + "0000000").substring(0, 12).toUpperCase();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", codePrefix + suffix);
        m.put("isin", isin);
        m.put("libelle", "BTA Test 13 semaines");
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

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test @Order(1)
    void sante_database_up() {
        ResponseEntity<Map> r = GET("/health", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("database")).isEqualTo("up");
    }

    // ── Authentification ──────────────────────────────────────────────────────

    @Test @Order(2)
    void login_client_role_CLIENT_PP() {
        ResponseEntity<Map> step1 = POST("/api/v1/auth/login",
                Map.of("email", "jean.mballa@example.cm", "password", "Demo1234"), null);
        assertThat(step1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(step1.getBody()).containsKey("challengeId");
        assertThat((Boolean) step1.getBody().get("mfaRequired")).isTrue();

        ResponseEntity<Map> step2 = POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", step1.getBody().get("challengeId"), "code", "123456"), null);
        assertThat(step2.getStatusCode()).isEqualTo(HttpStatus.OK);
        tokClient = (String) step2.getBody().get("accessToken");
        assertThat(tokClient).isNotBlank();
        Map<?, ?> user = (Map<?, ?>) step2.getBody().get("user");
        assertThat(user.get("role")).isEqualTo("CLIENT_PP");
    }

    @Test @Order(3)
    void login_agent_role_AGENT() {
        tokAgent = login("agent@afriland.cm");
        ResponseEntity<Map> me = GET("/api/v1/auth/me", tokAgent);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("role")).isEqualTo("AGENT");
    }

    @Test @Order(4)
    void login_superviseur_role_SUPERVISEUR() {
        tokSup = login("superviseur@afriland.cm");
        ResponseEntity<Map> me = GET("/api/v1/auth/me", tokSup);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("role")).isEqualTo("SUPERVISEUR");
    }

    @Test @Order(5)
    void login_admin_role_ADMIN() {
        tokAdmin = login("admin@afriland.cm");
        ResponseEntity<Map> me = GET("/api/v1/auth/me", tokAdmin);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("role")).isEqualTo("ADMIN");
    }

    @Test @Order(6)
    void refresh_via_cookie_HttpOnly_rotation() {
        // Re-login pour deposer un cookie afb_rt frais dans le CookieStore
        login("jean.mballa@example.cm");
        // /refresh n'utilise pas de Bearer — le cookie HttpOnly est envoye automatiquement
        ResponseEntity<Map> r = POST("/api/v1/auth/refresh", null, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("accessToken");
        assertThat((String) r.getBody().get("accessToken")).isNotBlank();
    }

    // ── Cycle émission ────────────────────────────────────────────────────────

    @Test @Order(7)
    void agent_cree_emission_BROUILLON() {
        ResponseEntity<Map> r = POST("/api/v1/emissions", emissionBody("BTA-TEST-", "CM"), tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("BROUILLON");
        emId = (String) r.getBody().get("id");
        assertThat(emId).isNotBlank();
    }

    @Test @Order(8)
    void superviseur_publie_emission_PUBLIE() {
        ResponseEntity<Map> r = POST("/api/v1/emissions/" + emId + "/publish", null, tokSup);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("PUBLIE");
    }

    // ── Cycle ordre — soumission + validation agent ───────────────────────────

    @Test @Order(9)
    void client_soumet_ordre_SOUMIS() {
        Map<String, Object> payload = Map.of("emissionId", emId, "volume", 3, "tauxSoumis", 5.5);
        ResponseEntity<Map> r = POST("/api/v1/orders", payload, tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("SOUMIS");
        ordId = (String) r.getBody().get("id");
        assertThat(ordId).isNotBlank();
    }

    @Test @Order(10)
    void agent_valide_ordre_EN_ATTENTE_ADJUDICATION() {
        ResponseEntity<Map> r = POST("/api/v1/orders/" + ordId + "/validate", null, tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("EN_ATTENTE_ADJUDICATION");
    }

    // ── Adjudication DIRECTE (un seul niveau) ────────────────────────────────
    // Le résultat saisi par l'agent (ORDER_RESULT) prend effet immédiatement :
    // l'ordre est finalisé et le client voit le résultat sans validation
    // superviseur (cf. OrderController#changeStatus).

    @Test @Order(11)
    void agent_saisit_adjudication_directe_TOTALEMENT_RETENU() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "TOTALEMENT_RETENU");
        payload.put("montantAdjuge", 3_000_000);
        payload.put("volumeAlloue", 3);
        payload.put("tauxAdjuge", 5.5);
        ResponseEntity<Map> r = POST("/api/v1/orders/" + ordId + "/status", payload, tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Adjudication directe : le résultat de l'agent est appliqué et final
        assertThat(r.getBody().get("status")).isEqualTo("TOTALEMENT_RETENU");
        assertThat(r.getBody().get("resultatPropose")).isEqualTo("TOTALEMENT_RETENU");
    }

    @Test @Order(12)
    void client_voit_resultat_immediatement_apres_adjudication() {
        ResponseEntity<Map> r = GET("/api/v1/orders/" + ordId, tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Le résultat est validé dès sa saisie → immédiatement visible côté client
        assertThat(r.getBody().get("status")).isEqualTo("TOTALEMENT_RETENU");
        assertThat(((Number) r.getBody().get("montantAdjuge")).longValue()).isEqualTo(3_000_000L);
    }

    @Test @Order(13)
    void agent_ne_peut_pas_appeler_validate_result_403() {
        // L'agent n'a pas ORDER_RESULT_VALIDATE : rejet avant tout contrôle d'état
        ResponseEntity<Map> r = POST("/api/v1/orders/" + ordId + "/validate-result", null, tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test @Order(14)
    void superviseur_validate_result_400_car_deja_finalise() {
        // En adjudication directe, aucun résultat n'est « en attente » de validation
        ResponseEntity<Map> r = POST("/api/v1/orders/" + ordId + "/validate-result", null, tokSup);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test @Order(15)
    void ordre_cloture_changement_statut_refuse_400() {
        ResponseEntity<Map> r = POST("/api/v1/orders/" + ordId + "/status",
                Map.of("status", "SOUMIS"), tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test @Order(16)
    void client_voit_montant_adjuge_final() {
        ResponseEntity<Map> r = GET("/api/v1/orders/" + ordId, tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("TOTALEMENT_RETENU");
        assertThat(((Number) r.getBody().get("montantAdjuge")).longValue()).isEqualTo(3_000_000L);
    }

    // ── Portefeuille ──────────────────────────────────────────────────────────

    @Test @Order(17)
    void portefeuille_reflete_ordre_adjuge() {
        ResponseEntity<Map> r = GET("/api/v1/portfolio", tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("positions");
        Number valeurTotale = (Number) r.getBody().get("valeurTotale");
        assertThat(valeurTotale).isNotNull();
        assertThat(valeurTotale.longValue()).isGreaterThanOrEqualTo(3_000_000L);
    }

    // ── RBAC V10 : USER_MANAGE = ADMIN uniquement ──────────────────────────────

    @Test @Order(18)
    void admin_liste_utilisateurs_USER_MANAGE() {
        ResponseEntity<Map> r = GET("/api/v1/users", tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // /users ne liste que les comptes internes (AGENT, SUPERVISEUR, ADMIN)
        // → le seed en crée exactement 3.
        assertThat(((Number) r.getBody().get("total")).intValue()).isGreaterThanOrEqualTo(3);
    }

    @Test @Order(19)
    void agent_ne_peut_pas_lister_utilisateurs_403() {
        ResponseEntity<Map> r = GET("/api/v1/users", tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── RBAC V10 : AUDIT_READ = ADMIN uniquement ───────────────────────────────

    @Test @Order(20)
    void admin_lit_journal_audit_AUDIT_READ() {
        ResponseEntity<Map> r = GET("/api/v1/audit", tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) r.getBody().get("total")).intValue()).isGreaterThanOrEqualTo(1);
    }

    @Test @Order(21)
    void superviseur_ne_peut_pas_lire_audit_403() {
        ResponseEntity<Map> r = GET("/api/v1/audit", tokSup);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test @Order(22)
    void audit_resout_nom_acteurs() {
        ResponseEntity<Map> r = GET("/api/v1/audit", tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> data = (List<Map<String, Object>>) r.getBody().get("data");
        assertThat(data).isNotEmpty();
        boolean anyNom = data.stream().anyMatch(e -> e.get("utilisateurNom") != null);
        assertThat(anyNom).as("au moins une entree d'audit doit avoir un nom resolu").isTrue();
    }

    // ── Clients ────────────────────────────────────────────────────────────────

    @Test @Order(23)
    void agent_liste_dossiers_clients_page() {
        ResponseEntity<Map> r = GET("/api/v1/clients/dossiers", tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("data");
    }

    // ── Permissions ────────────────────────────────────────────────────────────

    @Test @Order(24)
    void permissions_accessible_a_tout_utilisateur_authentifie() {
        ResponseEntity<Map> r = GET("/api/v1/permissions", tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("ADMIN");
    }

    @Test @Order(25)
    void superviseur_ne_peut_pas_modifier_matrice_RBAC_403() {
        Map<String, Object> payload = Map.of("role", "AGENT", "permission", "CONFIG_MARCHE", "granted", true);
        ResponseEntity<Map> r = PUT("/api/v1/permissions", payload, tokSup);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test @Order(26)
    void admin_modifie_matrice_RBAC_ajoute_CONFIG_MARCHE() {
        Map<String, Object> payload = Map.of("role", "AGENT", "permission", "CONFIG_MARCHE", "granted", true);
        ResponseEntity<Map> r = PUT("/api/v1/permissions", payload, tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> agentPerms = (List<String>) r.getBody().get("AGENT");
        assertThat(agentPerms).contains("CONFIG_MARCHE");
    }

    @Test @Order(27)
    void admin_restaure_matrice_RBAC_retire_CONFIG_MARCHE() {
        Map<String, Object> payload = Map.of("role", "AGENT", "permission", "CONFIG_MARCHE", "granted", false);
        ResponseEntity<Map> r = PUT("/api/v1/permissions", payload, tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> agentPerms = (List<String>) r.getBody().get("AGENT");
        assertThat(agentPerms).doesNotContain("CONFIG_MARCHE");
    }

    // ── Suppression emission brouillon ─────────────────────────────────────────

    @Test @Order(28)
    void admin_supprime_emission_brouillon_204() {
        // L'agent crée une 2e émission brouillon
        ResponseEntity<Map> create = POST("/api/v1/emissions",
                emissionBody("BTA-DEL-", "CW"), tokAgent);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String em2Id = (String) create.getBody().get("id");

        // L'admin la supprime
        ResponseEntity<Void> del = DELETE("/api/v1/emissions/" + em2Id, tokAdmin);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Vérification : l'émission n'est plus accessible (staff -> 404)
        ResponseEntity<Map> check = GET("/api/v1/emissions/" + em2Id, tokAdmin);
        assertThat(check.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
