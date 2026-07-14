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
    void piece_identite_acceptee_avec_un_type_reconnu() {
        ResponseEntity<Map> r = POST("/api/v1/registration/dossiers/" + dossierId + "/piece-identite",
                Map.of("imageBase64", IMG, "cote", "RECTO", "type", "CNI",
                        "texte", "CARTE NATIONALE D IDENTITE NGONO",
                        "nettete", 300, "largeur", 1200, "hauteur", 800), tokenPrincipal);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("pieceId")).isNotNull();
    }

    /**
     * La detection du type est devenue INDICATIVE : elle peut ne rien reconnaitre.
     * Le depot doit alors aboutir malgre tout — {@code Map.of()} refusant les
     * valeurs nulles, un type absent faisait echouer l'envoi (NPE, HTTP 500).
     */
    @Test
    void piece_identite_acceptee_meme_SANS_type_reconnu() {
        java.util.Map<String, Object> corps = new java.util.HashMap<>();
        corps.put("imageBase64", IMG);
        corps.put("cote", "VERSO");
        corps.put("type", null);                 // l'OCR n'a rien reconnu
        corps.put("texte", "texte illisible");
        corps.put("nettete", 120);
        corps.put("largeur", 900);
        corps.put("hauteur", 600);

        ResponseEntity<Map> r = POST(
                "/api/v1/registration/dossiers/" + dossierId + "/piece-identite", corps, tokenPrincipal);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("pieceId")).isNotNull();
        assertThat(r.getBody().get("verificationStatus")).isEqualTo("EN_ATTENTE");
    }

    // ═══════════ Soumission → validation back-office → compte-titres ══════════

    /**
     * Le maillon qui manquait : sans soumission, le dossier restait indefiniment
     * en BROUILLON — jamais examine, donc jamais valide, et le client n'obtenait
     * jamais de compte-titres.
     */
    @Test
    void submit_fait_passer_le_dossier_en_attente_de_verification() {
        String token = nouveauProspect("submit");
        String id = dernierDossier;
        deposerVisageEtPiece(id, token);

        ResponseEntity<Map> r = POST("/api/v1/registration/dossiers/" + id + "/submit", null, token);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("statut")).isEqualTo("EN_ATTENTE_VERIFICATION");

        // Idempotent : re-soumettre ne casse rien (reponse reseau perdue).
        ResponseEntity<Map> encore =
                POST("/api/v1/registration/dossiers/" + id + "/submit", null, token);
        assertThat(encore.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(encore.getBody().get("dejaSoumis")).isEqualTo(true);
    }

    @Test
    void submit_refuse_un_dossier_sans_capture_faciale() {
        String token = nouveauProspect("nofacesubmit");
        ResponseEntity<Map> r =
                POST("/api/v1/registration/dossiers/" + dernierDossier + "/submit", null, token);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Le compte ESPECES saisi a l'inscription est porte des maintenant sur le
     * profil : c'est lui qui permet au client de soumettre un ordre avant meme
     * que le back-office ne lui attribue un compte-titres.
     */
    @Test
    void le_compte_especes_est_rattache_au_profil_des_l_inscription() {
        ResponseEntity<Map> me = GET("/api/v1/auth/me", tokenPrincipal);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("compteEspeces")).isEqualTo("1000500010000004320768");
        assertThat(me.getBody().get("compteTitres")).isNull();   // attribue par l'agent
    }

    /** L'agent valide le dossier et saisit LUI-MEME le compte-titres. */
    @Test
    void approve_attribue_le_compte_titres_saisi_par_l_agent() {
        String token = nouveauProspect("approve");
        String id = dernierDossier;
        deposerVisageEtPiece(id, token);
        POST("/api/v1/registration/dossiers/" + id + "/submit", null, token);

        String agent = login("agent@afriland.cm");
        // La capture faciale doit d'abord etre verifiee : c'est le controle d'identite.
        ResponseEntity<Map> tot = POST("/api/v1/admin/registrations/" + id + "/approve",
                Map.of("compteTitres", "037 10001 00000099999"), agent);
        assertThat(tot.getStatusCode()).as("visage non verifie").isEqualTo(HttpStatus.BAD_REQUEST);

        POST("/api/v1/admin/registrations/" + id + "/face/verify", Map.of("status", "VALIDE"), agent);

        String compteTitres = "037 10001 " + String.format("%011d", Math.abs(id.hashCode()) % 1_000_000);
        ResponseEntity<Map> ok = POST("/api/v1/admin/registrations/" + id + "/approve",
                Map.of("compteTitres", compteTitres), agent);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("statut")).isEqualTo("VALIDE");
        assertThat(ok.getBody().get("compteTitres")).isEqualTo(compteTitres);
    }

    /** Un compte-titres omis reste NULL : le client est « en rouge », pas bloque. */
    @Test
    void approve_sans_compte_titres_laisse_le_client_a_completer() {
        String token = nouveauProspect("approve-vide");
        String id = dernierDossier;
        deposerVisageEtPiece(id, token);
        POST("/api/v1/registration/dossiers/" + id + "/submit", null, token);

        String agent = login("agent@afriland.cm");
        POST("/api/v1/admin/registrations/" + id + "/face/verify", Map.of("status", "VALIDE"), agent);

        ResponseEntity<Map> ok =
                POST("/api/v1/admin/registrations/" + id + "/approve", Map.of(), agent);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("compteTitres")).isNull();
        assertThat(ok.getBody().get("compteEspeces")).isNotNull();
    }

    @Test
    void reject_exige_un_motif() {
        String token = nouveauProspect("reject");
        String id = dernierDossier;
        deposerVisageEtPiece(id, token);
        POST("/api/v1/registration/dossiers/" + id + "/submit", null, token);

        String agent = login("agent@afriland.cm");
        ResponseEntity<Map> sansMotif =
                POST("/api/v1/admin/registrations/" + id + "/reject", Map.of(), agent);
        assertThat(sansMotif.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> avecMotif = POST("/api/v1/admin/registrations/" + id + "/reject",
                Map.of("motif", "Piece d'identite illisible"), agent);
        assertThat(avecMotif.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(avecMotif.getBody().get("statut")).isEqualTo("REJETE");
    }

    @Test
    void approve_refuse_un_dossier_non_soumis() {
        String agent = login("agent@afriland.cm");
        // dossierId (principal) n'a jamais ete soumis : il est en BROUILLON.
        ResponseEntity<Map> r =
                POST("/api/v1/admin/registrations/" + dossierId + "/approve", Map.of(), agent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Le cœur du correctif : un client auto-inscrit n'a PAS encore de
     * compte-titres (l'agent l'attribue plus tard) — il doit pouvoir soumettre
     * son ordre malgre tout. L'ordre part avec un compte-titres vide, que le
     * back-office voit signale en rouge.
     */
    @Test
    void un_client_sans_compte_titres_peut_soumettre_un_ordre() {
        String prospect = nouveauProspect("ordre");

        String agent = login("agent@afriland.cm");
        String sup = login("superviseur@afriland.cm");
        ResponseEntity<Map> em = POST("/api/v1/emissions", emissionBody(), agent);
        assertThat(em.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String emId = (String) em.getBody().get("id");
        ResponseEntity<Map> pub = POST("/api/v1/emissions/" + emId + "/publish", null, sup);
        assertThat(pub.getStatusCode()).as("publication — %s", pub.getBody())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> ordre = POST("/api/v1/orders",
                Map.of("emissionId", emId, "volume", 2, "tauxSoumis", 5.0), prospect);

        assertThat(ordre.getStatusCode())
                .as("l'absence de compte-titres ne doit PAS bloquer la soumission — %s",
                        ordre.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(ordre.getBody().get("id")).isNotNull();
    }

    Map<String, Object> emissionBody() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("code", "BTA-REG-" + suffix);
        m.put("isin", ("CR" + suffix + "0000000").substring(0, 12).toUpperCase());
        m.put("libelle", "BTA Auto-inscription 13 semaines");
        m.put("nature", "BTA");
        m.put("paysCode", "CMR");
        m.put("dateEmission", java.time.LocalDate.now().toString());
        m.put("ouvertureSouscription", java.time.LocalDate.now().plusDays(1) + "T08:00:00Z");
        m.put("fermetureSouscription", java.time.LocalDate.now().plusDays(8) + "T09:00:00Z");
        m.put("dateEcheance", java.time.LocalDate.now().plusDays(94).toString());
        m.put("dateReglement", java.time.LocalDate.now().plusDays(9).toString());
        m.put("valeurNominaleUnitaire", 1_000_000);
        m.put("montantGlobal", 5_000_000_000L);
        m.put("tauxNominal", 0.0);
        m.put("montantMinimum", 1_000_000);
        m.put("modeAdjudication", "TAUX");
        return m;
    }

    // ─── Helpers de scenario ──────────────────────────────────────────────────

    String dernierDossier;

    /** Cree un prospect distinct (chaque scenario a besoin de son propre dossier). */
    String nouveauProspect(String tag) {
        ResponseEntity<Map> r = POST("/api/v1/registration/register", Map.of(
                "email", tag + "+" + UUID.randomUUID() + "@example.cm", "password", "MotDePasse1",
                "nom", "TEST", "prenom", tag, "telephone", "+237690000001",
                "typePersonne", "PP", "compteEspeces", compteEspecesAleatoire()), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        dernierDossier = String.valueOf(r.getBody().get("dossierId"));
        return (String) ((Map) r.getBody().get("auth")).get("accessToken");
    }

    /** 22 chiffres, uniques : le compte especes est porte sur users (contrainte d'unicite). */
    private static String compteEspecesAleatoire() {
        long n = Math.abs(UUID.randomUUID().getMostSignificantBits() % 10_000_000_000_000L);
        return "100050001" + String.format("%013d", n);
    }

    void deposerVisageEtPiece(String id, String token) {
        POST("/api/v1/registration/dossiers/" + id + "/face", Map.of(
                "imageBase64", IMG, "livenessScore", 0.9, "livenessPassed", true,
                "challengeType", "BLINK"), token);
        POST("/api/v1/registration/dossiers/" + id + "/piece-identite", Map.of(
                "imageBase64", IMG, "cote", "RECTO", "type", "CNI", "texte", "CNI",
                "nettete", 300, "largeur", 1200, "hauteur", 800), token);
    }

    @Test
    void convention_courante_est_publique() {
        ResponseEntity<Map> r = GET("/api/v1/registration/convention?langue=FR", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("version")).isEqualTo("2026-01");
        assertThat(String.valueOf(r.getBody().get("contenuHtml"))).contains("Article 1");
    }
}
