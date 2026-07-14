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
 * Tests fonctionnels end-to-end complementaires : administration (comptes,
 * clients, parametres), notifications, documents, consultation de solde,
 * supervision OTP et fins de parcours Auth. Complete {@link WorkflowV10Test}
 * pour couvrir l'ensemble des controleurs REST.
 *
 * <p>Meme base PostgreSQL externe dediee que {@link WorkflowV10Test}
 * (contexte Spring mutualise) : voir la javadoc de cette classe pour la
 * (re)creation propre de {@code afb_titres_test}.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings("unchecked")
class AdminWorkflowV10Test {

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
    String newUserId, newClientId;

    final BasicCookieStore cookieStore = new BasicCookieStore();
    RestTemplate rest;

    @BeforeAll
    void setUp() {
        CloseableHttpClient http = HttpClients.custom().setDefaultCookieStore(cookieStore).build();
        rest = new RestTemplate(new HttpComponentsClientHttpRequestFactory(http));
        rest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) throws IOException { return false; }
        });
        tokAgent = login("agent@afriland.cm", "Demo1234");
        tokSup = login("superviseur@afriland.cm", "Demo1234");
        tokAdmin = login("admin@afriland.cm", "Demo1234");
        tokClient = login("jean.mballa@example.cm", "Demo1234");
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

    ResponseEntity<Map> PUT(String path, Object payload, String token) {
        return rest.exchange(url(path), HttpMethod.PUT, body(payload, token), Map.class);
    }

    ResponseEntity<Map> DELETE(String path, String token) {
        return rest.exchange(url(path), HttpMethod.DELETE, noBody(token), Map.class);
    }

    String login(String email, String password) {
        ResponseEntity<Map> step1 = POST("/api/v1/auth/login",
                Map.of("email", email, "password", password), null);
        assertThat(step1.getStatusCode()).as("login %s", email).isEqualTo(HttpStatus.OK);
        String challengeId = (String) step1.getBody().get("challengeId");
        ResponseEntity<Map> step2 = POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", challengeId, "code", "123456"), null);
        assertThat(step2.getStatusCode()).as("mfa %s", email).isEqualTo(HttpStatus.OK);
        return (String) step2.getBody().get("accessToken");
    }

    String unique(String prefix) {
        return prefix + System.nanoTime();
    }

    // ══════════════════════ Comptes utilisateurs (ADMIN) ══════════════════════

    @Test @Order(1)
    void admin_cree_compte_agent() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("email", unique("agent.test.") + "@afriland.cm");
        req.put("password", "TempPass123");
        req.put("role", "AGENT");
        req.put("nom", "TESTAGENT");
        req.put("prenom", "Nouveau");
        ResponseEntity<Map> r = POST("/api/v1/users", req, tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("role")).isEqualTo("AGENT");
        newUserId = (String) r.getBody().get("id");
        assertThat(newUserId).isNotBlank();
    }

    @Test @Order(2)
    void agent_ne_peut_pas_creer_compte_403() {
        Map<String, Object> req = Map.of("email", unique("x.") + "@afriland.cm",
                "password", "TempPass123", "role", "AGENT", "nom", "X");
        ResponseEntity<Map> r = POST("/api/v1/users", req, tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test @Order(3)
    void admin_liste_utilisateurs_pagines() {
        ResponseEntity<Map> r = GET("/api/v1/users?page=1&size=10", tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("data");
        assertThat(r.getBody()).containsKey("total");
    }

    @Test @Order(4)
    void staff_consulte_mot_de_passe_initial() {
        ResponseEntity<Map> r = GET("/api/v1/users/" + newUserId + "/initial-password", tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("available");
    }

    @Test @Order(5)
    void admin_modifie_compte_PATCH() {
        ResponseEntity<Map> r = PATCH("/api/v1/users/" + newUserId,
                Map.of("nom", "TESTAGENT-MODIFIE", "statut", "ACTIF"), tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("nom")).isEqualTo("TESTAGENT-MODIFIE");
    }

    @Test @Order(6)
    void admin_reinitialise_mot_de_passe() {
        ResponseEntity<Map> r = POST("/api/v1/users/" + newUserId + "/reset-password",
                Map.of("password", "ResetPass789"), tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("temporaryPassword")).isEqualTo("ResetPass789");
    }

    @Test @Order(7)
    void admin_supprime_compte() {
        ResponseEntity<Map> r = DELETE("/api/v1/users/" + newUserId, tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("deleted")).isEqualTo(true);
    }

    @Test @Order(8)
    void nouvel_utilisateur_change_mot_de_passe_puis_volontairement() {
        // 1) Creation d'un agent avec mot de passe provisoire.
        String email = unique("chp.") + "@afriland.cm";
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("email", email);
        create.put("password", "TempPass123");
        create.put("role", "AGENT");
        create.put("nom", "CHANGEPWD");
        ResponseEntity<Map> created = POST("/api/v1/users", create, tokAdmin);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String uid = (String) created.getBody().get("id");

        // 2) 1re connexion : changement impose (sans mot de passe actuel).
        String tok = login(email, "TempPass123");
        ResponseEntity<Map> first = POST("/api/v1/auth/change-password",
                Map.of("newPassword", "Passw0rd-New1"), tok);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 3) Changement VOLONTAIRE : le mot de passe actuel est exige.
        String tok2 = login(email, "Passw0rd-New1");
        ResponseEntity<Map> voluntary = POST("/api/v1/auth/change-password",
                Map.of("currentPassword", "Passw0rd-New1", "newPassword", "Passw0rd-New2"), tok2);
        assertThat(voluntary.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 4) Mauvais mot de passe actuel → 401.
        String tok3 = login(email, "Passw0rd-New2");
        ResponseEntity<Map> wrong = POST("/api/v1/auth/change-password",
                Map.of("currentPassword", "FauxMotDePasse", "newPassword", "Passw0rd-New3"), tok3);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        DELETE("/api/v1/users/" + uid, tokAdmin);
    }

    // ══════════════════════ Dossiers clients (CLIENT_MANAGE) ══════════════════

    @Test @Order(10)
    void agent_cree_client_PP_onboarding_complet() {
        String email = unique("client.test.") + "@example.cm";
        Map<String, Object> signataire = new LinkedHashMap<>();
        signataire.put("type", "TITULAIRE");
        signataire.put("nom", "ESSOMBA");
        signataire.put("prenom", "Pierre");
        signataire.put("telephonePortable", "+237 699 000 111");
        signataire.put("email", email);

        Map<String, Object> sousCompte = new LinkedHashMap<>();
        sousCompte.put("numero", unique("SC-"));
        sousCompte.put("libelle", "Conservation principale");
        sousCompte.put("type", "CONSERVATION");

        Map<String, Object> adresse = new LinkedHashMap<>();
        adresse.put("type", "DOMICILE");
        adresse.put("rue", "Avenue Kennedy");
        adresse.put("ville", "Yaoundé");
        adresse.put("pays", "Cameroun");

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("type", "PP");
        req.put("raisonSociale", "ESSOMBA Pierre");
        req.put("categorie", "NON_QUALIFIE");
        req.put("typeCompte", "INDIVIDUEL");
        req.put("adresse", adresse);
        req.put("signataires", List.of(signataire));
        req.put("sousComptes", List.of(sousCompte));
        req.put("soldeEspecesInitial", 10_000_000);

        ResponseEntity<Map> r = POST("/api/v1/clients", req, tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        newClientId = (String) r.getBody().get("id");
        assertThat(newClientId).isNotBlank();
        assertThat(r.getBody().get("raisonSociale")).isEqualTo("ESSOMBA Pierre");
    }

    @Test @Order(11)
    void agent_liste_clients_pagines() {
        ResponseEntity<Map> r = GET("/api/v1/clients?page=1&size=20", tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) r.getBody().get("total")).intValue()).isGreaterThanOrEqualTo(1);
    }

    @Test @Order(12)
    void agent_lit_dossier_client() {
        ResponseEntity<Map> r = GET("/api/v1/clients/" + newClientId, tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("compte");
    }

    @Test @Order(13)
    void agent_modifie_dossier_client_PATCH() {
        ResponseEntity<Map> r = PATCH("/api/v1/clients/" + newClientId,
                Map.of("categorie", "QUALIFIE", "statut", "ACTIF"), tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test @Order(14)
    void staff_consulte_portefeuille_client() {
        ResponseEntity<Map> r = GET("/api/v1/portfolio/" + newClientId, tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("positions");
    }

    @Test @Order(15)
    void agent_desactive_client() {
        ResponseEntity<Map> r = DELETE("/api/v1/clients/" + newClientId, tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test @Order(16)
    void agent_cree_client_PM_compte_joint_et_le_met_a_jour() {
        String emailTit = unique("pm.tit.") + "@example.cm";
        String emailCo = unique("pm.co.") + "@example.cm";
        Map<String, Object> titulaire = new LinkedHashMap<>();
        titulaire.put("type", "TITULAIRE");
        titulaire.put("nom", "NKENG");
        titulaire.put("prenom", "Alice");
        titulaire.put("telephonePortable", "+237 690 100 200");
        titulaire.put("email", emailTit);
        Map<String, Object> cosignataire = new LinkedHashMap<>();
        cosignataire.put("type", "TITULAIRE");
        cosignataire.put("nom", "NKENG");
        cosignataire.put("prenom", "Bob");
        cosignataire.put("telephonePortable", "+237 690 300 400");
        cosignataire.put("email", emailCo);

        Map<String, Object> sousCompte = new LinkedHashMap<>();
        sousCompte.put("numero", unique("SC-PM-"));
        sousCompte.put("type", "CONSERVATION");

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("type", "PM");
        req.put("raisonSociale", "NKENG & Fils SARL");
        req.put("rccm", "RC/YAO/2020/B/1234");
        req.put("categorie", "QUALIFIE");
        req.put("typeCompte", "JOINT");
        req.put("signataires", List.of(titulaire, cosignataire));
        req.put("sousComptes", List.of(sousCompte));

        ResponseEntity<Map> create = POST("/api/v1/clients", req, tokAgent);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String pmId = (String) create.getBody().get("id");
        assertThat(create.getBody().get("type")).isEqualTo("PM");

        // Mise a jour : remplacement integral des signataires + sous-comptes.
        Map<String, Object> newSig = new LinkedHashMap<>();
        newSig.put("type", "TITULAIRE");
        newSig.put("nom", "NKENG");
        newSig.put("prenom", "Alice-Marie");
        newSig.put("telephonePortable", "+237 690 500 600");
        newSig.put("email", emailTit);
        Map<String, Object> newSc = new LinkedHashMap<>();
        newSc.put("numero", unique("SC-PM2-"));
        newSc.put("libelle", "Conservation secondaire");
        newSc.put("type", "CONSERVATION");

        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("raisonSociale", "NKENG & Fils SA");
        upd.put("categorie", "NON_QUALIFIE");
        upd.put("signataires", List.of(newSig));
        upd.put("sousComptes", List.of(newSc));
        Map<String, Object> adresse = new LinkedHashMap<>();
        adresse.put("rue", "Rue de la Reunification");
        adresse.put("ville", "Douala");
        upd.put("adresse", adresse);

        ResponseEntity<Map> update = PATCH("/api/v1/clients/" + pmId, upd, tokAgent);
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(update.getBody().get("raisonSociale")).isEqualTo("NKENG & Fils SA");
    }

    // ══════════════════════ Parcours client (self-service) ════════════════════

    @Test @Order(20)
    void client_lit_son_dossier() {
        ResponseEntity<Map> r = GET("/api/v1/clients/me", tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Le solde espece est masque cote client.
        Map<String, Object> compte = (Map<String, Object>) r.getBody().get("compte");
        assertThat(((Number) compte.get("soldeEspeces")).longValue()).isEqualTo(0L);
    }

    @Test @Order(21)
    void client_liste_ses_notifications() {
        ResponseEntity<Map> r = GET("/api/v1/notifications?page=1&size=20", tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("data");
    }

    @Test @Order(22)
    void client_compte_notifications_non_lues() {
        ResponseEntity<Map> r = GET("/api/v1/notifications/unread-count", tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("count");
    }

    @Test @Order(23)
    void client_marque_toutes_notifications_lues() {
        ResponseEntity<Map> r = POST("/api/v1/notifications/read-all", null, tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test @Order(24)
    void client_liste_ses_documents() {
        ResponseEntity<Map> r = GET("/api/v1/documents", tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("data");
    }

    // ══════════════════════ Parametres d'administration (ADMIN) ═══════════════

    @Test @Order(30)
    void admin_lit_parametres_messagerie() {
        ResponseEntity<Map> r = GET("/api/v1/admin/mail-settings", tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("host");
    }

    @Test @Order(31)
    void admin_enregistre_parametres_messagerie() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("host", "smtp.test.local");
        req.put("port", 587);
        req.put("username", "noreply@test.local");
        req.put("fromAddress", "noreply@test.local");
        req.put("fromName", "Test");
        req.put("auth", true);
        req.put("starttls", true);
        req.put("enabled", false);
        ResponseEntity<Map> r = PUT("/api/v1/admin/mail-settings", req, tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("host")).isEqualTo("smtp.test.local");
    }

    @Test @Order(32)
    void admin_teste_messagerie() {
        ResponseEntity<Map> r = POST("/api/v1/admin/mail-settings/test",
                Map.of("to", "dest@test.local"), tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("status");
    }

    @Test @Order(33)
    void agent_ne_peut_pas_lire_parametres_messagerie_403() {
        ResponseEntity<Map> r = GET("/api/v1/admin/mail-settings", tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test @Order(34)
    void admin_lit_et_enregistre_parametres_otp() {
        ResponseEntity<Map> get = GET("/api/v1/admin/otp-settings", tokAdmin);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("canal", "EMAIL");
        req.put("longueur", 6);
        req.put("ttlSecondes", 300);
        req.put("maxTentatives", 5);
        ResponseEntity<Map> put = PUT("/api/v1/admin/otp-settings", req, tokAdmin);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody().get("canal")).isEqualTo("EMAIL");
    }

    @Test @Order(35)
    void admin_lit_et_enregistre_parametres_ldap() {
        ResponseEntity<Map> get = GET("/api/v1/admin/ldap-settings", tokAdmin);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("enabled", false);
        req.put("host", "ldap.test.local");
        req.put("port", 389);
        req.put("baseDn", "dc=test,dc=local");
        ResponseEntity<Map> put = PUT("/api/v1/admin/ldap-settings", req, tokAdmin);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test @Order(36)
    void admin_teste_ldap_desactive() {
        // LDAP reste desactive (ne pas activer globalement : casserait les
        // connexions locales des autres tests partageant le contexte). Le test
        // de connexion sur une config desactivee renvoie proprement ok=false.
        ResponseEntity<Map> r = POST("/api/v1/admin/ldap-settings/test", null, tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("ok");
    }

    @Test @Order(37)
    void marche_config_lecture_ouverte_et_ecriture_superviseur() {
        ResponseEntity<Map> get = GET("/api/v1/admin/marche-config", null);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody()).containsKey("statuses");

        ResponseEntity<Map> put = PUT("/api/v1/admin/marche-config",
                Map.of("statuses", List.of("PUBLIE", "CLOTURE"), "dateDu", "", "dateAu", ""), tokSup);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<String>) put.getBody().get("statuses")).contains("PUBLIE");
    }

    // ══════════════════════ Supervision OTP (staff) ═══════════════════════════

    @Test @Order(40)
    void staff_consulte_journal_otp() {
        ResponseEntity<Map> r = GET("/api/v1/admin/otp-log?page=1&size=20", tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("data");
    }

    @Test @Order(41)
    void staff_consulte_journal_otp_filtre() {
        ResponseEntity<Map> r = GET("/api/v1/admin/otp-log?q=agent&activeOnly=true", tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test @Order(42)
    void client_ne_peut_pas_consulter_journal_otp_403() {
        ResponseEntity<Map> r = GET("/api/v1/admin/otp-log", tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ══════════════════════ Consultation de solde AIF (staff) ═════════════════

    @Test @Order(50)
    void staff_ping_sante_AIF() {
        ResponseEntity<Map> r = GET("/api/v1/account-balance/health", tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("available");
    }

    @Test @Order(51)
    void staff_recherche_solde_AIF_indisponible_gracieux() {
        // AIF non configure en test : degradation gracieuse (200 + statut UNAVAILABLE).
        ResponseEntity<Map> r = GET("/api/v1/account-balance?accountNumber=03710001123", tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("status");
    }

    @Test @Order(52)
    void client_ne_peut_pas_consulter_solde_403() {
        ResponseEntity<Map> r = GET("/api/v1/account-balance?accountNumber=03710001123", tokClient);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ══════════════════════ Emissions — chemins restants ══════════════════════

    @Test @Order(60)
    void staff_liste_emissions_filtrees() {
        ResponseEntity<Map> r = GET("/api/v1/emissions?status=PUBLIE&page=1&size=10", tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("data");
    }

    @Test @Order(61)
    void public_liste_emissions_publiees() {
        ResponseEntity<Map> r = GET("/api/v1/emissions", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("data");
    }

    @Test @Order(62)
    void cycle_emission_creation_modification_cloture() {
        // Cree une emission BROUILLON (agent) → PATCH → publie (sup) → close (sup).
        Map<String, Object> corps = emissionBody();
        ResponseEntity<Map> create = POST("/api/v1/emissions", corps, tokAgent);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String emId = (String) create.getBody().get("id");

        ResponseEntity<Map> get = GET("/api/v1/emissions/" + emId, tokAgent);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Le PATCH attend le corps complet (remplacement), pas un patch partiel.
        corps.put("libelle", "BTA Test modifie");
        ResponseEntity<Map> patch = PATCH("/api/v1/emissions/" + emId, corps, tokAgent);
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patch.getBody().get("libelle")).isEqualTo("BTA Test modifie");

        ResponseEntity<Map> publish = POST("/api/v1/emissions/" + emId + "/publish", null, tokSup);
        assertThat(publish.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> close = POST("/api/v1/emissions/" + emId + "/close", null, tokSup);
        assertThat(close.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(close.getBody().get("status")).isEqualTo("CLOTURE");
    }

    @Test @Order(63)
    void staff_liste_destinataires_diffusion() {
        ResponseEntity<Map> r = rest.exchange(url("/api/v1/emissions/broadcast/recipients"),
                HttpMethod.GET, noBody(tokSup), Map.class);
        // Selon la config, peut renvoyer 200 (liste) — on accepte OK.
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ══════════════════════ Documents (DOCUMENT_UPLOAD) ═══════════════════════

    @Test @Order(70)
    void agent_televerse_document_pour_client() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("clientId", clientUserId());
        req.put("type", "AVIS_OPERE");
        req.put("titre", "Avis d'opere test");
        req.put("mimeType", "application/pdf");
        req.put("taille", "12 Ko");
        req.put("contenu", "data:application/pdf;base64,JVBERi0xLjQKJeLjz9M=");
        ResponseEntity<Map> r = POST("/api/v1/documents", req, tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsKey("id");
    }

    @Test @Order(71)
    void staff_liste_documents() {
        ResponseEntity<Map> r = GET("/api/v1/documents?page=1&size=20", tokAgent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("data");
    }

    // ══════════════════════ QR de connexion ═══════════════════════════════════

    @Test @Order(80)
    void qr_connexion_genere() {
        ResponseEntity<Map> r = GET("/api/v1/connection-qr", tokAdmin);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ══════════════════════ Auth — fins de parcours ══════════════════════════

    @Test @Order(90)
    void forgot_password_reponse_generique() {
        ResponseEntity<Map> r = POST("/api/v1/auth/forgot-password",
                Map.of("email", "inconnu@example.cm"), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("message");
    }

    @Test @Order(91)
    void reset_password_token_invalide_rejete() {
        ResponseEntity<Map> r = POST("/api/v1/auth/reset-password",
                Map.of("token", "jeton-invalide-inexistant", "newPassword", "NewPass1234"), null);
        assertThat(r.getStatusCode().value()).isBetween(400, 401);
    }

    @Test @Order(92)
    void logout_revoque_session() {
        String tok = login("superviseur@afriland.cm", "Demo1234");
        ResponseEntity<Map> r = POST("/api/v1/auth/logout", null, tok);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─── Helpers de payload ─────────────────────────────────────────────────

    String clientUserId() {
        // L'id du client seed jean.mballa via /clients/me n'est pas expose ici :
        // on lit son dossier via l'agent en filtrant la liste des dossiers.
        ResponseEntity<Map> me = GET("/api/v1/clients/me", tokClient);
        return (String) me.getBody().get("id");
    }

    Map<String, Object> emissionBody() {
        // Suffixe tire d'un UUID (10 hex ~ 10^12 valeurs) et non de nanoTime tronque :
        // `code` et `isin` sont UNIQUE et la base de test n'est jamais purgee entre
        // les executions, or nanoTime tronque ne laissait que ~10^4 valeurs distinctes.
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String isin = ("CM" + suffix + "0000000").substring(0, 12).toUpperCase();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", "BTA-ADM-" + suffix);
        m.put("isin", isin);
        m.put("libelle", "BTA Admin Test 13 semaines");
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
