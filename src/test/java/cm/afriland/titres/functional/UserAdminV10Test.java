package cm.afriland.titres.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashMap;
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
 * Gestion des comptes internes : anti-elevation de privileges a la creation,
 * modification et suppression reservees a l'ADMIN, reinitialisation de mot de
 * passe et consultation du mot de passe initial.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({ "unchecked", "rawtypes" })
class UserAdminV10Test {

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

    String tokAdmin, tokAgent, tokSup, tokClient;
    String adminId;

    final BasicCookieStore cookieStore = new BasicCookieStore();
    RestTemplate rest;

    @BeforeAll
    void setUp() {
        CloseableHttpClient http = HttpClients.custom().setDefaultCookieStore(cookieStore).build();
        rest = new RestTemplate(new HttpComponentsClientHttpRequestFactory(http));
        rest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) throws IOException { return false; }
        });
        tokAdmin = login("admin@afriland.cm");
        tokAgent = login("agent@afriland.cm");
        tokSup = login("superviseur@afriland.cm");
        tokClient = login("jean.mballa@example.cm");
        adminId = (String) GET("/api/v1/auth/me", tokAdmin).getBody().get("id");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    String url(String path) { return "http://localhost:" + port + path; }

    HttpEntity<Object> body(Object payload, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return new HttpEntity<>(payload, h);
    }

    ResponseEntity<Map> POST(String path, Object payload, String token) {
        return rest.exchange(url(path), HttpMethod.POST, body(payload, token), Map.class);
    }

    ResponseEntity<Map> GET(String path, String token) {
        return rest.exchange(url(path), HttpMethod.GET, body(null, token), Map.class);
    }

    ResponseEntity<Map> PATCH(String path, Object payload, String token) {
        return rest.exchange(url(path), HttpMethod.PATCH, body(payload, token), Map.class);
    }

    ResponseEntity<Map> DELETE(String path, String token) {
        return rest.exchange(url(path), HttpMethod.DELETE, body(null, token), Map.class);
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

    static String uniqueEmail(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@afriland.cm";
    }

    Map<String, Object> compte(String role, String email) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", email);
        m.put("password", "Provisoire1");
        m.put("role", role);
        m.put("nom", "TEST");
        m.put("prenom", "Compte");
        return m;
    }

    /** Cree un compte interne via l'admin et renvoie son identifiant. */
    String creerCompte(String role) {
        ResponseEntity<Map> r = POST("/api/v1/users", compte(role, uniqueEmail("u.")), tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    // ═══════════════════════════ Liste et lecture ═════════════════════════════

    @Test
    void l_admin_liste_les_comptes_internes_pagines() {
        ResponseEntity<Map> r = GET("/api/v1/users?page=1&size=5", tokAdmin);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKeys("data", "total");
        assertThat(((Number) r.getBody().get("total")).intValue()).isPositive();
    }

    /** USER_MANAGE n'appartient qu'a l'ADMIN : un agent ne liste pas les comptes. */
    @Test
    void un_agent_ne_liste_pas_les_comptes_internes() {
        assertThat(GET("/api/v1/users", tokAgent).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void un_client_ne_liste_pas_les_comptes_internes() {
        assertThat(GET("/api/v1/users", tokClient).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ═════════════════════ Creation et anti-elevation ═════════════════════════

    @Test
    void l_admin_cree_un_agent() {
        ResponseEntity<Map> r = POST("/api/v1/users", compte("AGENT", uniqueEmail("ag.")), tokAdmin);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("role")).isEqualTo("AGENT");
        assertThat(r.getBody().get("mustChangePassword")).isEqualTo(true);
        // Aucune empreinte ni mot de passe ne doit sortir.
        assertThat(r.getBody()).doesNotContainKeys("password", "passwordHash");
    }

    /** Le role est normalise (casse et espaces) avant validation. */
    @Test
    void le_role_est_normalise_avant_validation() {
        Map<String, Object> req = compte("  agent  ", uniqueEmail("norm."));

        ResponseEntity<Map> r = POST("/api/v1/users", req, tokAdmin);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("role")).isEqualTo("AGENT");
    }

    @Test
    void un_role_inconnu_est_refuse() {
        assertThat(POST("/api/v1/users", compte("SUPER_ADMIN", uniqueEmail("x.")), tokAdmin)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** USER_MANAGE manque a l'agent : il ne cree aucun compte interne. */
    @Test
    void un_agent_ne_cree_pas_de_compte_interne() {
        assertThat(POST("/api/v1/users", compte("AGENT", uniqueEmail("y.")), tokAgent)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void un_superviseur_ne_cree_pas_de_compte_interne() {
        assertThat(POST("/api/v1/users", compte("AGENT", uniqueEmail("z.")), tokSup)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void un_mot_de_passe_trop_court_est_refuse_a_la_creation() {
        Map<String, Object> req = compte("AGENT", uniqueEmail("court."));
        req.put("password", "court");

        assertThat(POST("/api/v1/users", req, tokAdmin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void un_email_malforme_est_refuse_a_la_creation() {
        Map<String, Object> req = compte("AGENT", "pas-un-email");

        assertThat(POST("/api/v1/users", req, tokAdmin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ══════════════════════════════ Modification ══════════════════════════════

    @Test
    void l_admin_modifie_un_compte() {
        String id = creerCompte("AGENT");

        ResponseEntity<Map> r = PATCH("/api/v1/users/" + id,
                Map.of("nom", "  NOUVEAU  ", "statut", "SUSPENDU"), tokAdmin);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("nom")).isEqualTo("NOUVEAU");
        assertThat(r.getBody().get("statut")).isEqualTo("SUSPENDU");
    }

    /** Les champs omis conservent leur valeur courante (branche de repli). */
    @Test
    void les_champs_omis_conservent_leur_valeur() {
        String id = creerCompte("AGENT");
        Map avant = GET("/api/v1/users?page=1&size=1", tokAdmin).getBody();
        assertThat(avant).isNotNull();

        ResponseEntity<Map> r = PATCH("/api/v1/users/" + id, Map.of("nom", "SEUL_LE_NOM"), tokAdmin);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("nom")).isEqualTo("SEUL_LE_NOM");
        assertThat(r.getBody().get("prenom")).isEqualTo("Compte");   // inchange
        assertThat(r.getBody().get("statut")).isEqualTo("ACTIF");    // inchange
    }

    @Test
    void un_statut_invalide_est_refuse() {
        String id = creerCompte("AGENT");

        assertThat(PATCH("/api/v1/users/" + id, Map.of("statut", "ARCHIVE"), tokAdmin)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Seul l'ADMIN modifie un profil — pas meme le superviseur. */
    @Test
    void un_superviseur_ne_modifie_pas_un_profil() {
        String id = creerCompte("AGENT");

        assertThat(PATCH("/api/v1/users/" + id, Map.of("nom", "X"), tokSup)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void modifier_un_compte_inexistant_renvoie_404() {
        assertThat(PATCH("/api/v1/users/00000000-0000-0000-0000-000000000000",
                Map.of("nom", "X"), tokAdmin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ═══════════════════ Reinitialisation de mot de passe ═════════════════════

    /** Sans corps, un mot de passe provisoire robuste est genere et renvoye une fois. */
    @Test
    void l_admin_reinitialise_avec_un_mot_de_passe_genere() {
        String id = creerCompte("AGENT");

        ResponseEntity<Map> r = POST("/api/v1/users/" + id + "/reset-password", null, tokAdmin);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String mdp = (String) r.getBody().get("temporaryPassword");
        assertThat(mdp).isNotBlank().hasSizeGreaterThanOrEqualTo(8);
    }

    /** Un mot de passe explicite est applique tel quel (apres trim). */
    @Test
    void l_admin_reinitialise_avec_un_mot_de_passe_choisi() {
        String id = creerCompte("AGENT");

        ResponseEntity<Map> r = POST("/api/v1/users/" + id + "/reset-password",
                Map.of("password", "  MotDePasseChoisi1  "), tokAdmin);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("temporaryPassword")).isEqualTo("MotDePasseChoisi1");
    }

    @Test
    void un_mot_de_passe_choisi_trop_court_est_refuse() {
        String id = creerCompte("AGENT");

        assertThat(POST("/api/v1/users/" + id + "/reset-password",
                Map.of("password", "court"), tokAdmin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void un_agent_ne_reinitialise_pas_un_mot_de_passe() {
        String id = creerCompte("AGENT");

        assertThat(POST("/api/v1/users/" + id + "/reset-password", null, tokAgent)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void reinitialiser_un_compte_inexistant_renvoie_404() {
        assertThat(POST("/api/v1/users/00000000-0000-0000-0000-000000000000/reset-password",
                null, tokAdmin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ═══════════════════════ Mot de passe initial ═════════════════════════════

    /** Tant que le compte n'a pas change son mot de passe, le BO peut le lire. */
    @Test
    void le_back_office_lit_le_mot_de_passe_initial_d_un_compte_neuf() {
        String id = creerCompte("AGENT");

        ResponseEntity<Map> r = GET("/api/v1/users/" + id + "/initial-password", tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("available")).isEqualTo(true);
        assertThat(r.getBody().get("password")).isEqualTo("Provisoire1");
    }

    /** Un compte de demonstration a deja change son mot de passe : plus rien a lire. */
    @Test
    void le_mot_de_passe_initial_n_est_plus_disponible_apres_changement() {
        String idAgent = (String) GET("/api/v1/auth/me", tokAgent).getBody().get("id");

        ResponseEntity<Map> r = GET("/api/v1/users/" + idAgent + "/initial-password", tokAdmin);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("available")).isEqualTo(false);
        assertThat(r.getBody().get("password")).isNull();
    }

    @Test
    void un_client_ne_lit_pas_un_mot_de_passe_initial() {
        String id = creerCompte("AGENT");

        assertThat(GET("/api/v1/users/" + id + "/initial-password", tokClient)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void mot_de_passe_initial_d_un_compte_inexistant_renvoie_404() {
        assertThat(GET("/api/v1/users/00000000-0000-0000-0000-000000000000/initial-password",
                tokAdmin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ═══════════════════════════════ Suppression ══════════════════════════════

    @Test
    void l_admin_supprime_un_compte_sans_operation_liee() {
        String id = creerCompte("AGENT");

        ResponseEntity<Map> r = DELETE("/api/v1/users/" + id, tokAdmin);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("deleted")).isEqualTo(true);
    }

    /** Garde-fou : on ne supprime pas son propre compte. */
    @Test
    void l_admin_ne_supprime_pas_son_propre_compte() {
        assertThat(DELETE("/api/v1/users/" + adminId, tokAdmin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void un_superviseur_ne_supprime_pas_de_compte() {
        String id = creerCompte("AGENT");

        assertThat(DELETE("/api/v1/users/" + id, tokSup).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void supprimer_un_compte_inexistant_renvoie_404() {
        assertThat(DELETE("/api/v1/users/00000000-0000-0000-0000-000000000000", tokAdmin)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Un compte porteur d'operations (le client de demonstration a des ordres)
     * ne peut pas etre supprime : on invite a le suspendre.
     */
    @Test
    void un_compte_lie_a_des_operations_ne_peut_pas_etre_supprime() {
        String idClient = (String) GET("/api/v1/auth/me", tokClient).getBody().get("id");

        ResponseEntity<Map> r = DELETE("/api/v1/users/" + idClient, tokAdmin);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(r.getBody())).contains("Suspendez");
    }
}
