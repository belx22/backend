package cm.afriland.titres.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.List;
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
 * Tests fonctionnels des <b>chemins d'erreur</b> (validations, conflits, droits) :
 * chaque cas exerce une branche de rejet (400/401/403/404/409) des controleurs,
 * afin de couvrir les conditions non atteintes par les parcours nominaux.
 *
 * <p>Meme base PostgreSQL externe dediee que {@link WorkflowV10Test}.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings("unchecked")
class ValidationV10Test {

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

    String tokAgent, tokSup, tokAdmin, tokClient;

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
        tokAdmin = login("admin@afriland.cm");
        tokClient = login("jean.mballa@example.cm");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    String url(String p) { return "http://localhost:" + port + p; }

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

    ResponseEntity<Map> POST(String p, Object payload, String token) {
        return rest.exchange(url(p), HttpMethod.POST, body(payload, token), Map.class);
    }
    ResponseEntity<Map> GET(String p, String token) {
        return rest.exchange(url(p), HttpMethod.GET, noBody(token), Map.class);
    }
    ResponseEntity<Map> PATCH(String p, Object payload, String token) {
        return rest.exchange(url(p), HttpMethod.PATCH, body(payload, token), Map.class);
    }
    ResponseEntity<Map> PUT(String p, Object payload, String token) {
        return rest.exchange(url(p), HttpMethod.PUT, body(payload, token), Map.class);
    }
    ResponseEntity<Map> DELETE(String p, String token) {
        return rest.exchange(url(p), HttpMethod.DELETE, noBody(token), Map.class);
    }
    int status(ResponseEntity<Map> r) { return r.getStatusCode().value(); }

    String login(String email) {
        ResponseEntity<Map> s1 = POST("/api/v1/auth/login",
                Map.of("email", email, "password", "Demo1234"), null);
        ResponseEntity<Map> s2 = POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", s1.getBody().get("challengeId"), "code", "123456"), null);
        return (String) s2.getBody().get("accessToken");
    }

    Map<String, Object> emission() {
        // Suffixe tire d'un UUID (10 hex ~ 10^12 valeurs) et non de nanoTime tronque :
        // `code` et `isin` sont UNIQUE et la base de test n'est jamais purgee entre
        // les executions, or nanoTime tronque ne laissait que ~10^4 valeurs distinctes.
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", "BTA-VAL-" + suffix);
        m.put("isin", ("CV" + suffix + "0000000").substring(0, 12).toUpperCase());
        m.put("libelle", "BTA Validation");
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

    String createEmission() {
        return (String) POST("/api/v1/emissions", emission(), tokAgent).getBody().get("id");
    }

    // ══════════════════════ Emissions — validations ══════════════════════════

    @Test void emission_nature_invalide_400() {
        Map<String, Object> m = emission(); m.put("nature", "XXX");
        assertThat(status(POST("/api/v1/emissions", m, tokAgent))).isEqualTo(400);
    }
    @Test void emission_pays_invalide_400() {
        Map<String, Object> m = emission(); m.put("paysCode", "ZZZ");
        assertThat(status(POST("/api/v1/emissions", m, tokAgent))).isEqualTo(400);
    }
    @Test void emission_mode_invalide_400() {
        Map<String, Object> m = emission(); m.put("modeAdjudication", "LOTO");
        assertThat(status(POST("/api/v1/emissions", m, tokAgent))).isEqualTo(400);
    }
    @Test void emission_echeance_avant_emission_400() {
        Map<String, Object> m = emission();
        m.put("dateEcheance", LocalDate.now().minusDays(1).toString());
        assertThat(status(POST("/api/v1/emissions", m, tokAgent))).isEqualTo(400);
    }
    @Test void emission_fermeture_avant_ouverture_400() {
        Map<String, Object> m = emission();
        m.put("ouvertureSouscription", LocalDate.now().plusDays(8) + "T08:00:00Z");
        m.put("fermetureSouscription", LocalDate.now().plusDays(1) + "T09:00:00Z");
        assertThat(status(POST("/api/v1/emissions", m, tokAgent))).isEqualTo(400);
    }
    @Test void emission_montant_min_inferieur_nominal_400() {
        Map<String, Object> m = emission(); m.put("montantMinimum", 1);
        assertThat(status(POST("/api/v1/emissions", m, tokAgent))).isEqualTo(400);
    }
    @Test void emission_OTA_sans_coupon_400() {
        Map<String, Object> m = emission();
        m.put("nature", "OTA"); m.put("modeAdjudication", "PRIX");
        m.put("frequenceCoupon", null);
        assertThat(status(POST("/api/v1/emissions", m, tokAgent))).isEqualTo(400);
    }
    @Test void emission_isin_duplique_400() {
        Map<String, Object> m = emission(); m.put("isin", "CM0000DEMO01"); // seed
        assertThat(status(POST("/api/v1/emissions", m, tokAgent))).isEqualTo(400);
    }
    @Test void emission_creation_par_client_403() {
        assertThat(status(POST("/api/v1/emissions", emission(), tokClient))).isEqualTo(403);
    }
    @Test void emission_introuvable_404() {
        assertThat(status(GET("/api/v1/emissions/00000000-0000-0000-0000-000000000000", tokAgent)))
                .isEqualTo(404);
    }
    @Test void emission_publish_deja_publiee_400() {
        String id = createEmission();
        POST("/api/v1/emissions/" + id + "/publish", null, tokSup);
        assertThat(status(POST("/api/v1/emissions/" + id + "/publish", null, tokSup))).isEqualTo(400);
    }
    @Test void emission_close_brouillon_400() {
        String id = createEmission();
        assertThat(status(POST("/api/v1/emissions/" + id + "/close", null, tokSup))).isEqualTo(400);
    }
    @Test void emission_archive_non_cloturee_400() {
        String id = createEmission();
        assertThat(status(POST("/api/v1/emissions/" + id + "/archive", null, tokSup))).isEqualTo(400);
    }
    @Test void emission_reject_non_brouillon_400() {
        String id = createEmission();
        POST("/api/v1/emissions/" + id + "/publish", null, tokSup);
        assertThat(status(POST("/api/v1/emissions/" + id + "/reject",
                Map.of("motif", "trop tard pour rejeter"), tokSup))).isEqualTo(400);
    }
    @Test void emission_suppression_par_agent_403() {
        String id = createEmission();
        assertThat(status(DELETE("/api/v1/emissions/" + id, tokAgent))).isEqualTo(403);
    }

    // ══════════════════════ Ordres — validations ═════════════════════════════

    @Test void ordre_volume_zero_400() {
        String em = createEmission();
        POST("/api/v1/emissions/" + em + "/publish", null, tokSup);
        assertThat(status(POST("/api/v1/orders",
                Map.of("emissionId", em, "volume", 0, "tauxSoumis", 5.0), tokClient))).isEqualTo(400);
    }
    @Test void ordre_emission_inexistante_rejete() {
        int st = status(POST("/api/v1/orders",
                Map.of("emissionId", "00000000-0000-0000-0000-000000000000",
                        "volume", 2, "tauxSoumis", 5.0), tokClient));
        assertThat(st).isBetween(400, 404);
    }
    @Test void ordre_introuvable_404() {
        assertThat(status(GET("/api/v1/orders/00000000-0000-0000-0000-000000000000", tokAgent)))
                .isEqualTo(404);
    }
    @Test void ordre_validate_inexistant_404() {
        assertThat(status(POST("/api/v1/orders/00000000-0000-0000-0000-000000000000/validate",
                null, tokAgent))).isEqualTo(404);
    }
    @Test void ordre_status_invalide_400() {
        String em = createEmission();
        POST("/api/v1/emissions/" + em + "/publish", null, tokSup);
        String ord = (String) POST("/api/v1/orders",
                Map.of("emissionId", em, "volume", 2, "tauxSoumis", 5.0), tokClient).getBody().get("id");
        POST("/api/v1/orders/" + ord + "/validate", null, tokAgent);
        assertThat(status(POST("/api/v1/orders/" + ord + "/status",
                Map.of("status", "STATUT_BIDON"), tokAgent))).isEqualTo(400);
    }

    // ══════════════════════ Clients — validations ════════════════════════════

    @Test void client_type_invalide_400() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("type", "XX");
        req.put("raisonSociale", "Test");
        req.put("signataires", List.of(Map.of("nom", "T", "email", "a@b.cm",
                "telephonePortable", "+237 690 000 000")));
        req.put("sousComptes", List.of(Map.of("numero", "SC-1")));
        assertThat(status(POST("/api/v1/clients", req, tokAgent))).isEqualTo(400);
    }
    @Test void client_sans_signataires_400() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("type", "PP");
        req.put("raisonSociale", "Test");
        req.put("signataires", List.of());
        req.put("sousComptes", List.of(Map.of("numero", "SC-1")));
        assertThat(status(POST("/api/v1/clients", req, tokAgent))).isEqualTo(400);
    }
    @Test void client_email_duplique_400() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("type", "PP");
        req.put("raisonSociale", "Doublon");
        req.put("signataires", List.of(Map.of("nom", "MBALLA", "email", "jean.mballa@example.cm",
                "telephonePortable", "+237 690 000 001")));
        req.put("sousComptes", List.of(Map.of("numero", "SC-DUP")));
        assertThat(status(POST("/api/v1/clients", req, tokAgent))).isEqualTo(400);
    }
    @Test void client_creation_par_client_403() {
        Map<String, Object> req = Map.of("type", "PP", "raisonSociale", "X",
                "signataires", List.of(Map.of("nom", "X", "email", "x@y.cm",
                        "telephonePortable", "+237 690 000 002")),
                "sousComptes", List.of(Map.of("numero", "SC-X")));
        assertThat(status(POST("/api/v1/clients", req, tokClient))).isEqualTo(403);
    }
    @Test void client_introuvable_404() {
        assertThat(status(GET("/api/v1/clients/00000000-0000-0000-0000-000000000000", tokAgent)))
                .isEqualTo(404);
    }

    // ══════════════════════ Utilisateurs — validations ═══════════════════════

    @Test void user_role_invalide_400() {
        Map<String, Object> req = Map.of("email", "role.bidon@afriland.cm",
                "password", "TempPass123", "role", "SUPERADMIN", "nom", "X");
        assertThat(status(POST("/api/v1/users", req, tokAdmin))).isEqualTo(400);
    }
    @Test void user_suppression_de_soi_meme_400() {
        String me = (String) GET("/api/v1/auth/me", tokAdmin).getBody().get("id");
        assertThat(status(DELETE("/api/v1/users/" + me, tokAdmin))).isEqualTo(400);
    }
    @Test void user_reset_password_trop_court_400() {
        // Cree un agent puis tente un reset avec mot de passe < 8 caracteres.
        String email = "short.reset." + System.nanoTime() + "@afriland.cm";
        String id = (String) POST("/api/v1/users", Map.of("email", email,
                "password", "TempPass123", "role", "AGENT", "nom", "S"), tokAdmin).getBody().get("id");
        int st = status(POST("/api/v1/users/" + id + "/reset-password",
                Map.of("password", "abc"), tokAdmin));
        DELETE("/api/v1/users/" + id, tokAdmin);
        assertThat(st).isEqualTo(400);
    }
    @Test void user_modification_par_agent_403() {
        String me = (String) GET("/api/v1/auth/me", tokAdmin).getBody().get("id");
        assertThat(status(PATCH("/api/v1/users/" + me, Map.of("nom", "Hack"), tokAgent))).isEqualTo(403);
    }
    @Test void user_introuvable_404() {
        assertThat(status(PATCH("/api/v1/users/00000000-0000-0000-0000-000000000000",
                Map.of("nom", "X"), tokAdmin))).isEqualTo(404);
    }

    // ══════════════════════ Parametres — validations ═════════════════════════

    @Test void mail_port_invalide_400() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", "smtp.x"); m.put("port", 70000); m.put("enabled", false);
        assertThat(status(PUT("/api/v1/admin/mail-settings", m, tokAdmin))).isEqualTo(400);
    }
    @Test void mail_active_sans_hote_400() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("port", 587); m.put("enabled", true); m.put("fromAddress", "a@b.cm");
        assertThat(status(PUT("/api/v1/admin/mail-settings", m, tokAdmin))).isEqualTo(400);
    }
    @Test void otp_canal_invalide_400() {
        Map<String, Object> m = Map.of("canal", "PIGEON", "longueur", 6,
                "ttlSecondes", 300, "maxTentatives", 5);
        assertThat(status(PUT("/api/v1/admin/otp-settings", m, tokAdmin))).isEqualTo(400);
    }
    @Test void otp_longueur_invalide_400() {
        Map<String, Object> m = Map.of("canal", "EMAIL", "longueur", 99,
                "ttlSecondes", 300, "maxTentatives", 5);
        assertThat(status(PUT("/api/v1/admin/otp-settings", m, tokAdmin))).isEqualTo(400);
    }
    @Test void ldap_ssl_et_starttls_exclusifs_400() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true); m.put("host", "ldap.x"); m.put("baseDn", "dc=x");
        m.put("ssl", true); m.put("startTls", true);
        assertThat(status(PUT("/api/v1/admin/ldap-settings", m, tokAdmin))).isEqualTo(400);
    }
    @Test void marche_config_statuts_vides_400() {
        assertThat(status(PUT("/api/v1/admin/marche-config",
                Map.of("statuses", List.of(), "dateDu", "", "dateAu", ""), tokSup))).isEqualTo(400);
    }
    @Test void marche_config_date_invalide_400() {
        assertThat(status(PUT("/api/v1/admin/marche-config",
                Map.of("statuses", List.of("PUBLIE"), "dateDu", "pas-une-date", "dateAu", ""),
                tokSup))).isEqualTo(400);
    }
    @Test void settings_par_agent_403() {
        assertThat(status(GET("/api/v1/admin/otp-settings", tokAgent))).isEqualTo(403);
    }

    // ══════════════════════ Auth — validations ═══════════════════════════════

    @Test void mfa_mauvais_code_rejete() {
        ResponseEntity<Map> s1 = POST("/api/v1/auth/login",
                Map.of("email", "agent@afriland.cm", "password", "Demo1234"), null);
        int st = status(POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", s1.getBody().get("challengeId"), "code", "000000"), null));
        assertThat(st).isBetween(400, 401);
    }
    @Test void mfa_challenge_inexistant_rejete() {
        int st = status(POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", "00000000-0000-0000-0000-000000000000", "code", "123456"), null));
        assertThat(st).isBetween(400, 401);
    }
    @Test void refresh_sans_cookie_401() {
        // Client HTTP neuf sans cookie afb_rt.
        CloseableHttpClient bare = HttpClients.custom().setDefaultCookieStore(new BasicCookieStore()).build();
        RestTemplate rt = new RestTemplate(new HttpComponentsClientHttpRequestFactory(bare));
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) throws IOException { return false; }
        });
        ResponseEntity<Map> r = rt.exchange(url("/api/v1/auth/refresh"), HttpMethod.POST,
                body(null, null), Map.class);
        assertThat(status(r)).isEqualTo(401);
    }
    @Test void reset_password_token_invalide_rejete() {
        assertThat(status(POST("/api/v1/auth/reset-password",
                Map.of("token", "inexistant", "newPassword", "NewPass1234"), null)))
                .isBetween(400, 401);
    }

    // ══════════════════════ Documents / Solde — validations ══════════════════

    @Test void document_mime_interdit_400() {
        String clientId = (String) GET("/api/v1/clients/me", tokClient).getBody().get("id");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("clientId", clientId);
        req.put("type", "X"); req.put("titre", "t");
        req.put("mimeType", "application/x-msdownload");
        req.put("contenu", "data:application/x-msdownload;base64,AAAA");
        assertThat(status(POST("/api/v1/documents", req, tokAgent))).isEqualTo(400);
    }
    @Test void document_destinataire_non_client_400() {
        String staffId = (String) GET("/api/v1/auth/me", tokAgent).getBody().get("id");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("clientId", staffId);
        req.put("type", "X"); req.put("titre", "t");
        req.put("mimeType", "application/pdf");
        req.put("contenu", "data:application/pdf;base64,JVBERi0=");
        assertThat(status(POST("/api/v1/documents", req, tokAgent))).isEqualTo(400);
    }
    @Test void solde_numero_trop_court_400() {
        assertThat(status(GET("/api/v1/account-balance?accountNumber=ab", tokAgent))).isEqualTo(400);
    }
    @Test void solde_par_client_403() {
        assertThat(status(GET("/api/v1/account-balance?accountNumber=03710001999", tokClient)))
                .isEqualTo(403);
    }
}
