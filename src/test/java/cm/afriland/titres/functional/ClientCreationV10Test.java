package cm.afriland.titres.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
 * Onboarding client : branches de <b>repli</b> de {@code ClientController.createClient}
 * (champs facultatifs omis, valeurs par defaut, co-signataires ignores) et
 * masquage du solde selon l'appelant.
 *
 * <p>{@code AdminWorkflowV10Test} couvre deja le cas « tous les champs
 * renseignes » ; ce test exerce systematiquement l'autre cote de chaque
 * alternative. Meme base PostgreSQL externe.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({ "unchecked", "rawtypes" })
class ClientCreationV10Test {

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
        tokClient = login("jean.mballa@example.cm");
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

    /** Suffixe unique : `email`, `numero` de sous-compte et comptes sont uniques en base. */
    static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    static String uniqueEmail() {
        return unique("cli") + "@example.cm";
    }

    /** Signataire minimal : nom + un seul telephone (bureau) et un e-mail. */
    static Map<String, Object> signataireMinimal(String email) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("nom", "MBALLA");
        c.put("telephoneBureau", "+237 222 000 111");
        c.put("email", email);
        return c;
    }

    static Map<String, Object> sousCompteMinimal() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("numero", unique("SC-"));
        return s;
    }

    static Map<String, Object> requeteMinimale(String type, String email) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("type", type);
        req.put("raisonSociale", "PM".equals(type) ? "SARL EXEMPLE" : "MBALLA Jean");
        req.put("signataires", List.of(signataireMinimal(email)));
        req.put("sousComptes", List.of(sousCompteMinimal()));
        return req;
    }

    // ══════════════════ Branches de repli de createClient ═════════════════════

    /**
     * Requete la plus depouillee possible : ni adresse, ni categorie, ni typeCompte,
     * ni prenom, ni solde, ni libelle de sous-compte. Toutes les valeurs par defaut
     * doivent s'appliquer.
     */
    @Test
    void creation_minimale_applique_toutes_les_valeurs_par_defaut() {
        ResponseEntity<Map> r = POST("/api/v1/clients", requeteMinimale("PP", uniqueEmail()), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Categorie client (segment metier) : defaut PP = « Personnes physiques ».
        assertThat(r.getBody().get("categorieClient")).isEqualTo("Personnes physiques");
        // Dirigeant (formule d'adresse des documents) : defaut PP = « Monsieur ».
        assertThat(r.getBody().get("dirigeant")).isEqualTo("Monsieur");
        Map compte = (Map) r.getBody().get("compte");
        assertThat(compte.get("typeCompte")).isEqualTo("INDIVIDUEL");
        assertThat(compte.get("categorie")).isEqualTo("NON_QUALIFIE");
        assertThat(compte.get("soldeEspeces")).isEqualTo(0);
        // Aucune adresse fournie -> aucune ligne inseree.
        assertThat((List<?>) compte.get("adresses")).isEmpty();
        // Libelle et type de sous-compte par defaut.
        Map sc = (Map) ((List<?>) compte.get("sousComptes")).get(0);
        assertThat(sc.get("libelle")).isEqualTo("Compte de conservation");
        assertThat(sc.get("type")).isEqualTo("CONSERVATION");
        assertThat(sc.get("statut")).isEqualTo("ACTIF");
    }

    /** Le compte especes fourni est repris tel quel, au lieu d'etre genere. */
    @Test
    void compte_especes_fourni_est_conserve() {
        String numero = unique("CE");
        Map<String, Object> req = requeteMinimale("PP", uniqueEmail());
        req.put("compteEspecesLie", "  " + numero + "  ");

        ResponseEntity<Map> r = POST("/api/v1/clients", req, tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map) r.getBody().get("compte")).get("compteEspecesLie")).isEqualTo(numero);
    }

    /** Un solde initial negatif est ramene a zero, jamais stocke tel quel. */
    @Test
    void solde_initial_negatif_est_ramene_a_zero() {
        Map<String, Object> req = requeteMinimale("PP", uniqueEmail());
        req.put("soldeEspecesInitial", -50_000);

        ResponseEntity<Map> r = POST("/api/v1/clients", req, tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map) r.getBody().get("compte")).get("soldeEspeces")).isEqualTo(0);
    }

    /** Compteurs de position negatifs : idem, bornes a zero. */
    @Test
    void positions_et_valeur_negatives_sont_bornees_a_zero() {
        Map<String, Object> sc = sousCompteMinimal();
        sc.put("positionsCount", -3);
        sc.put("valeurTotale", -1000);
        Map<String, Object> req = requeteMinimale("PP", uniqueEmail());
        req.put("sousComptes", List.of(sc));

        ResponseEntity<Map> r = POST("/api/v1/clients", req, tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map cree = (Map) ((List<?>) ((Map) r.getBody().get("compte")).get("sousComptes")).get(0);
        assertThat(cree.get("positionsCount")).isEqualTo(0);
        assertThat(cree.get("valeurTotale")).isEqualTo(0);
    }

    /** Une date d'ouverture illisible ne fait pas echouer l'onboarding : on retombe sur aujourd'hui. */
    @Test
    void date_d_ouverture_illisible_retombe_sur_aujourd_hui() {
        Map<String, Object> sc = sousCompteMinimal();
        sc.put("dateOuverture", "pas-une-date");
        Map<String, Object> req = requeteMinimale("PP", uniqueEmail());
        req.put("sousComptes", List.of(sc));

        ResponseEntity<Map> r = POST("/api/v1/clients", req, tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map cree = (Map) ((List<?>) ((Map) r.getBody().get("compte")).get("sousComptes")).get(0);
        assertThat((String) cree.get("dateOuverture")).isNotBlank();
    }

    /** Adresse sans type, ville ni pays : valeurs par defaut selon PP (DOMICILE) / PM (SIEGE). */
    @Test
    void adresse_sans_type_ni_pays_prend_les_defauts_du_type_de_personne() {
        Map<String, Object> adresse = new LinkedHashMap<>();
        adresse.put("rue", "  Avenue Kennedy  ");
        Map<String, Object> req = requeteMinimale("PM", uniqueEmail());
        req.put("adresse", adresse);

        ResponseEntity<Map> r = POST("/api/v1/clients", req, tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Categorie client : defaut PM = « Personne morale ».
        assertThat(r.getBody().get("categorieClient")).isEqualTo("Personne morale");
        // Dirigeant : defaut PM = « Directeur ».
        assertThat(r.getBody().get("dirigeant")).isEqualTo("Directeur");
        Map a = (Map) ((List<?>) ((Map) r.getBody().get("compte")).get("adresses")).get(0);
        assertThat(a.get("type")).isEqualTo("SIEGE");   // PM
        assertThat(a.get("rue")).isEqualTo("Avenue Kennedy");
        assertThat(a.get("pays")).isEqualTo("Cameroun");
        assertThat(a.get("ville")).isEqualTo("");
    }

    /** Une adresse dont la rue est blanche est ignoree (aucune ligne inseree). */
    @Test
    void adresse_sans_rue_est_ignoree() {
        Map<String, Object> adresse = new LinkedHashMap<>();
        adresse.put("ville", "Douala");
        adresse.put("rue", "   ");
        Map<String, Object> req = requeteMinimale("PP", uniqueEmail());
        req.put("adresse", adresse);

        ResponseEntity<Map> r = POST("/api/v1/clients", req, tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((List<?>) ((Map) r.getBody().get("compte")).get("adresses")).isEmpty();
    }

    /** PM : la raison sociale sert de nom et le prenom du signataire est ignore. */
    @Test
    void personne_morale_utilise_la_raison_sociale_comme_nom() {
        Map<String, Object> sig = signataireMinimal(uniqueEmail());
        sig.put("prenom", "Bob");
        Map<String, Object> req = requeteMinimale("PM", (String) sig.get("email"));
        req.put("raisonSociale", "NKENG & Fils SARL");
        req.put("categorie", "QUALIFIE");
        req.put("signataires", List.of(sig));

        ResponseEntity<Map> r = POST("/api/v1/clients", req, tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("raisonSociale")).isEqualTo("NKENG & Fils SARL");
        assertThat(((Map) r.getBody().get("compte")).get("categorie")).isEqualTo("QUALIFIE");
    }

    // ─────────────── Comptes joints : co-signataires ignores ──────────────────

    /** Compte JOINT : un co-signataire sans e-mail ne recoit pas de login (il est ignore). */
    @Test
    void cosignataire_sans_email_est_ignore() {
        Map<String, Object> cosignataire = new LinkedHashMap<>();
        cosignataire.put("nom", "SANS-EMAIL");
        cosignataire.put("telephonePortable", "+237 690 000 000");

        Map<String, Object> req = requeteMinimale("PM", uniqueEmail());
        req.put("typeCompte", "JOINT");
        req.put("signataires",
                List.of(signataireMinimal((String) ((Map) ((List) req.get("signataires")).get(0)).get("email")),
                        cosignataire));

        ResponseEntity<Map> r = POST("/api/v1/clients", req, tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Le co-signataire est bien enregistre comme contact, mais sans compte utilisateur.
        assertThat((List<?>) ((Map) r.getBody().get("compte")).get("contacts")).hasSize(2);
    }

    /** Compte INDIVIS : un co-signataire dont l'e-mail est deja pris est ignore, sans erreur. */
    @Test
    void cosignataire_avec_email_deja_utilise_est_ignore() {
        Map<String, Object> cosignataire = new LinkedHashMap<>();
        cosignataire.put("nom", "MBALLA");
        cosignataire.put("prenom", "Jean");
        cosignataire.put("telephoneBureau", "+237 222 111 222");
        cosignataire.put("email", "jean.mballa@example.cm");   // compte de demonstration existant

        Map<String, Object> req = requeteMinimale("PM", uniqueEmail());
        req.put("typeCompte", "INDIVIS");
        req.put("signataires",
                List.of(signataireMinimal((String) ((Map) ((List) req.get("signataires")).get(0)).get("email")),
                        cosignataire));

        ResponseEntity<Map> r = POST("/api/v1/clients", req, tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ────────────────────── Validations (400 explicites) ──────────────────────

    @Test
    void type_de_client_inconnu_est_refuse() {
        Map<String, Object> req = requeteMinimale("PP", uniqueEmail());
        req.put("type", "SA");

        assertThat(POST("/api/v1/clients", req, tokAgent).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void type_de_client_absent_est_refuse() {
        Map<String, Object> req = requeteMinimale("PP", uniqueEmail());
        req.remove("type");

        assertThat(POST("/api/v1/clients", req, tokAgent).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void raison_sociale_blanche_est_refusee() {
        Map<String, Object> req = requeteMinimale("PP", uniqueEmail());
        req.put("raisonSociale", "   ");

        assertThat(POST("/api/v1/clients", req, tokAgent).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sous_comptes_vides_sont_refuses() {
        Map<String, Object> req = requeteMinimale("PP", uniqueEmail());
        req.put("sousComptes", List.of());

        assertThat(POST("/api/v1/clients", req, tokAgent).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void numero_de_sous_compte_blanc_est_refuse() {
        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("numero", "   ");
        Map<String, Object> req = requeteMinimale("PP", uniqueEmail());
        req.put("sousComptes", List.of(sc));

        assertThat(POST("/api/v1/clients", req, tokAgent).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Le premier signataire porte l'e-mail de connexion : il doit etre valide. */
    @Test
    void premier_signataire_sans_email_valide_est_refuse() {
        Map<String, Object> sig = signataireMinimal("pas-un-email");
        Map<String, Object> req = requeteMinimale("PP", "x@y.cm");
        req.put("signataires", List.of(sig));

        assertThat(POST("/api/v1/clients", req, tokAgent).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Le premier signataire doit avoir au moins un numero de telephone. */
    @Test
    void premier_signataire_sans_telephone_est_refuse() {
        Map<String, Object> sig = new LinkedHashMap<>();
        sig.put("nom", "MBALLA");
        sig.put("email", uniqueEmail());
        Map<String, Object> req = requeteMinimale("PP", "x@y.cm");
        req.put("signataires", List.of(sig));

        assertThat(POST("/api/v1/clients", req, tokAgent).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Chaque signataire, pas seulement le premier, doit porter un nom. */
    @Test
    void cosignataire_sans_nom_est_refuse() {
        Map<String, Object> cosignataire = new LinkedHashMap<>();
        cosignataire.put("nom", "  ");
        cosignataire.put("email", uniqueEmail());
        Map<String, Object> req = requeteMinimale("PM", uniqueEmail());
        req.put("signataires",
                List.of(signataireMinimal((String) ((Map) ((List) req.get("signataires")).get(0)).get("email")),
                        cosignataire));

        assertThat(POST("/api/v1/clients", req, tokAgent).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Un e-mail deja utilise pour le titulaire principal est un conflit metier. */
    @Test
    void email_du_titulaire_deja_utilise_est_refuse() {
        Map<String, Object> req = requeteMinimale("PP", "jean.mballa@example.cm");

        assertThat(POST("/api/v1/clients", req, tokAgent).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Un client ne cree jamais de client. */
    @Test
    void un_client_ne_peut_pas_creer_de_client() {
        assertThat(POST("/api/v1/clients", requeteMinimale("PP", uniqueEmail()), tokClient)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────── Masquage du solde selon l'appelant ───────────────────

    /** Le back-office voit le solde espece en cache ; le client, jamais. */
    @Test
    void le_solde_est_masque_au_client_et_visible_au_back_office() {
        ResponseEntity<Map> moi = GET("/api/v1/clients/me", tokClient);
        assertThat(moi.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map) moi.getBody().get("compte")).get("soldeEspeces")).isEqualTo(0);

        String id = (String) moi.getBody().get("id");
        ResponseEntity<Map> vueAgent = GET("/api/v1/clients/" + id, tokAgent);
        assertThat(vueAgent.getStatusCode()).isEqualTo(HttpStatus.OK);
        // L'agent lit la valeur en cache, quelle qu'elle soit : le champ n'est pas force a 0.
        assertThat((Map) vueAgent.getBody().get("compte")).containsKey("soldeEspeces");
    }

    /** Un client ne lit pas le dossier d'un autre client. */
    @Test
    void un_client_ne_lit_pas_le_dossier_d_un_autre() {
        String autre = (String) POST("/api/v1/clients", requeteMinimale("PP", uniqueEmail()), tokAgent)
                .getBody().get("id");

        assertThat(GET("/api/v1/clients/" + autre, tokClient).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void dossier_client_inexistant_renvoie_404() {
        assertThat(GET("/api/v1/clients/00000000-0000-0000-0000-000000000000", tokAgent)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── Drapeau compteJoint expose par /auth/me (menu « Validations ») ───────

    /** Titulaire d'un compte JOINT : le drapeau est vrai. */
    @Test
    void auth_me_signale_un_compte_joint_pour_le_titulaire() {
        String tok = login("joint.titulaire@example.cm");

        assertThat(GET("/api/v1/auth/me", tok).getBody().get("compteJoint")).isEqualTo(true);
    }

    /**
     * Co-signataire : son propre compte ne porte AUCUN {@code typeCompte}, mais il
     * participe bien au compte joint via {@code accountHolderId}. C'est lui qui
     * valide les ordres : le drapeau doit etre vrai, sinon le menu disparait
     * precisement pour la personne concernee.
     */
    @Test
    void auth_me_signale_un_compte_joint_pour_le_cosignataire() {
        String tok = login("joint.cosignataire@example.cm");

        Map corps = GET("/api/v1/auth/me", tok).getBody();
        assertThat(corps.get("typeCompte")).isNull();
        assertThat(corps.get("compteJoint")).isEqualTo(true);
    }

    /** Client individuel : pas de menu de validation. */
    @Test
    void auth_me_ne_signale_pas_de_compte_joint_pour_un_client_individuel() {
        Map corps = GET("/api/v1/auth/me", tokClient).getBody();

        assertThat(corps.get("typeCompte")).isEqualTo("INDIVIDUEL");
        assertThat(corps.get("compteJoint")).isEqualTo(false);
    }

    /** Un acteur back-office n'a pas de compte-titres du tout. */
    @Test
    void auth_me_ne_signale_pas_de_compte_joint_pour_un_agent() {
        assertThat(GET("/api/v1/auth/me", tokAgent).getBody().get("compteJoint")).isEqualTo(false);
    }
}
