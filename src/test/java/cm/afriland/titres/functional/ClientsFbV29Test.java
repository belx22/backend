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
 * Referentiel « BASE CLIENTS » (V29) : import du fichier de la banque, puis
 * <b>rapprochement a l'inscription</b> — c'est le numero de compte qui dit si le
 * prospect est deja client (on reprend ses informations et son compte de depot)
 * ou un nouveau client (a orienter en agence).
 *
 * <p>Verifie aussi que le compte de depot ({@code CCEICMCXXnnn}) n'est
 * <b>jamais</b> renvoye a un client.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({ "unchecked", "rawtypes" })
class ClientsFbV29Test {

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

    RestTemplate rest;
    String agent;

    /** Numero du fichier : agence(5) + compte(11) + cle(2). Unique par execution. */
    String numeroFichier;
    /** RIB saisi par le prospect : code banque 10005 + numero du fichier. */
    String rib;
    String compteDepot;

    @BeforeAll
    void setUp() {
        rest = client();
        agent = login("agent@afriland.cm");

        numeroFichier = numeroFichier();
        rib = "10005" + numeroFichier;
        compteDepot = depot(numeroFichier);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    RestTemplate client() {
        CloseableHttpClient http = HttpClients.custom()
                .setDefaultCookieStore(new BasicCookieStore()).build();
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

    ResponseEntity<List> GET_LIST(String p, String token) {
        return rest.exchange(url(p), HttpMethod.GET, body(null, token), List.class);
    }

    String login(String email) {
        return loginAvec(email, "Demo1234");
    }

    /** Connexion complete (mot de passe + OTP) avec un mot de passe explicite. */
    String loginAvec(String email, String motDePasse) {
        ResponseEntity<Map> s1 = POST("/api/v1/auth/login",
                Map.of("email", email, "password", motDePasse), null);
        ResponseEntity<Map> s2 = POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", s1.getBody().get("challengeId"), "code", "123456"), null);
        return (String) s2.getBody().get("accessToken");
    }

    /** Un numero de compte du fichier, unique : la base de test n'est jamais purgee. */
    static String numeroFichier() {
        long n = Math.abs(UUID.randomUUID().getMostSignificantBits() % 10_000_000_000_000L);
        return "00090" + String.format("%013d", n);
    }

    static String depot(String numero) {
        return "CCEICMCXX" + String.format("%03d", Math.abs(numero.hashCode()) % 1000);
    }

    Map<String, Object> ligne() {
        return ligne(numeroFichier, compteDepot);
    }

    /** Une ligne du fichier « BASE CLIENTS », telle que le navigateur la transmet. */
    Map<String, Object> ligne(String numero, String depot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nomPrenom", "MEKULU M");
        m.put("numeroCompte", numero);
        m.put("agence", "00090");
        m.put("matricule", "0686513");
        m.put("categorie", "Personnes physiques");
        m.put("compteDepot", depot);
        m.put("assujettiTaxes", "OUI");
        m.put("localisation", "YAOUNDE");
        m.put("dirigeant", "Monsieur");
        m.put("telephone1", "222223437");
        m.put("email", "mekulu@example.cm");
        return m;
    }

    /** Lignes d'une page du referentiel (la reponse est paginee : {data, page, size, total}). */
    List<Map<String, Object>> pageDe(String queryString) {
        ResponseEntity<Map> r = GET("/api/v1/admin/clients-fb" + queryString, agent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("data");
    }

    long totalDe(String queryString) {
        ResponseEntity<Map> r = GET("/api/v1/admin/clients-fb" + queryString, agent);
        return ((Number) r.getBody().get("total")).longValue();
    }

    ResponseEntity<Map> inscrire(String compteEspeces) {
        return POST("/api/v1/registration/register", Map.of(
                "email", "fb+" + UUID.randomUUID() + "@example.cm", "password", "MotDePasse1",
                "nom", "MEKULU", "prenom", "M", "typePersonne", "PP",
                "compteEspeces", compteEspeces), null);
    }

    // ═══════════════════════════════ Tests ════════════════════════════════════

    @Test
    void import_puis_reimport_est_idempotent() {
        ResponseEntity<Map> premier = POST("/api/v1/admin/clients-fb/import",
                Map.of("lignes", List.of(ligne())), agent);
        assertThat(premier.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(premier.getBody().get("importees")).isEqualTo(1);

        // Re-jouer le meme fichier rafraichit la ligne au lieu de la dupliquer.
        ResponseEntity<Map> second = POST("/api/v1/admin/clients-fb/import",
                Map.of("lignes", List.of(ligne())), agent);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> trouve = pageDe("?q=" + numeroFichier);
        assertThat(trouve).hasSize(1);
        Map<String, Object> row = trouve.get(0);
        // Le compte especes est calcule en base : code banque + numero du fichier.
        assertThat(row.get("compte_especes")).isEqualTo(rib);
        assertThat(row.get("assujetti_taxes")).isEqualTo(true);
    }

    @Test
    void all_renvoie_l_identite_pour_le_modele_d_import_portefeuille() {
        POST("/api/v1/admin/clients-fb/import",
                Map.of("lignes", List.of(ligne())), agent);

        ResponseEntity<List> r = GET_LIST("/api/v1/admin/clients-fb/all", agent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> lignes = r.getBody();
        Map<String, Object> row = lignes.stream()
                .filter(m -> numeroFichier.equals(m.get("numero_compte")))
                .findFirst().orElseThrow();
        // Champs d'identite exposes pour pre-remplir le modele.
        assertThat(row.get("nom_prenom")).isEqualTo("MEKULU M");
        assertThat(row.get("matricule")).isEqualTo("0686513");
        assertThat(row.get("compte_depot")).isEqualTo(compteDepot);
    }

    @Test
    void une_ligne_sans_numero_de_compte_est_ignoree_sans_faire_echouer_l_import() {
        Map<String, Object> vide = new LinkedHashMap<>(ligne());
        vide.put("numeroCompte", "");
        ResponseEntity<Map> r = POST("/api/v1/admin/clients-fb/import",
                Map.of("lignes", List.of(vide)), agent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("importees")).isEqualTo(0);
        assertThat(r.getBody().get("ignorees")).isEqualTo(1);
    }

    @Test
    void l_import_est_reserve_au_back_office() {
        // Le client se connecte normalement (OTP) : meme avec une session complete,
        // il ne detient pas CLIENT_MANAGE — l'import lui est refuse (403).
        String email = "fb+" + UUID.randomUUID() + "@example.cm";
        POST("/api/v1/registration/register", Map.of(
                "email", email, "password", "MotDePasse1", "nom", "MEKULU", "prenom", "M",
                "typePersonne", "PP", "compteEspeces", "10005" + "00090" + "99999999999" + "11"), null);
        String client = loginAvec(email, "MotDePasse1");
        ResponseEntity<Map> r = POST("/api/v1/admin/clients-fb/import",
                Map.of("lignes", List.of(ligne())), client);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * Le cas nominal : le compte est connu de la banque — le prospect est deja
     * client, son compte de depot lui est rattache immediatement.
     */
    @Test
    void un_compte_connu_rattache_le_client_et_son_compte_de_depot() {
        String numero = numeroFichier();
        POST("/api/v1/admin/clients-fb/import",
                Map.of("lignes", List.of(ligne(numero, depot(numero)))), agent);

        ResponseEntity<Map> insc = inscrire("10005" + numero);
        assertThat(insc.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(insc.getBody().get("clientConnu")).isEqualTo(true);
    }

    /**
     * Un compte de depot ne sert qu'une fois ({@code users.compte_titres} est UNIQUE).
     * Une seconde inscription sur le MEME compte bancaire (co-titulaire, ou simple
     * re-inscription) ne doit donc pas exploser : elle aboutit, sans compte de depot,
     * et c'est le back-office qui tranchera.
     */
    @Test
    void une_seconde_inscription_sur_le_meme_compte_n_echoue_pas() {
        String numero = numeroFichier();
        POST("/api/v1/admin/clients-fb/import",
                Map.of("lignes", List.of(ligne(numero, depot(numero)))), agent);

        assertThat(inscrire("10005" + numero).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> second = inscrire("10005" + numero);
        assertThat(second.getStatusCode())
                .as("le compte de depot est deja pris — l'inscription doit tout de meme aboutir")
                .isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("clientConnu")).isEqualTo(true);
    }

    /** Compte inconnu : nouveau client, sans compte de depot — a orienter en agence. */
    @Test
    void un_compte_inconnu_donne_un_nouveau_client() {
        String inconnu = "10005" + "00099" + String.format("%011d",
                Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000_000L)) + "77";
        ResponseEntity<Map> insc = inscrire(inconnu);
        assertThat(insc.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(insc.getBody().get("clientConnu")).isEqualTo(false);
    }

    /**
     * Le compte de depot est un identifiant INTERNE : meme rattache au client, il
     * ne doit jamais figurer dans une reponse qui lui est adressee.
     */
    @Test
    void le_compte_de_depot_n_est_jamais_renvoye_au_client() {
        String numero = numeroFichier();
        POST("/api/v1/admin/clients-fb/import",
                Map.of("lignes", List.of(ligne(numero, depot(numero)))), agent);
        ResponseEntity<Map> insc = inscrire("10005" + numero);
        String token = (String) ((Map) insc.getBody().get("auth")).get("accessToken");

        ResponseEntity<Map> me = GET("/api/v1/auth/me", token);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("compteEspeces")).isEqualTo("10005" + numero);
        assertThat(me.getBody().get("compteTitres"))
                .as("le compte de depot ne doit JAMAIS sortir cote client")
                .isNull();
    }

    // ─── Pagination du referentiel ────────────────────────────────────────────

    /**
     * Le referentiel compte des milliers de lignes. Une troncature muette (« les
     * 200 premieres ») ferait croire a l'agent qu'il a tout vu : on pagine, et on
     * rend le TOTAL.
     */
    @Test
    void la_liste_est_paginee_et_annonce_le_total() {
        // Trois lignes partageant un meme prefixe de recherche.
        String tag = "PAGIN" + Math.abs(UUID.randomUUID().hashCode() % 100000);
        for (int i = 0; i < 3; i++) {
            Map<String, Object> l = new LinkedHashMap<>(ligne(numeroFichier(), depot("d" + i)));
            l.put("nomPrenom", tag + " " + i);
            POST("/api/v1/admin/clients-fb/import", Map.of("lignes", List.of(l)), agent);
        }

        // Une page de 2 : on obtient 2 lignes, mais le total en annonce bien 3.
        assertThat(pageDe("?q=" + tag + "&page=1&size=2")).hasSize(2);
        assertThat(totalDe("?q=" + tag + "&page=1&size=2")).isEqualTo(3);

        // La page suivante livre le reste, sans recouvrement.
        List<Map<String, Object>> p2 = pageDe("?q=" + tag + "&page=2&size=2");
        assertThat(p2).hasSize(1);
        assertThat(String.valueOf(p2.get(0).get("nom_prenom"))).startsWith(tag);
    }

    @Test
    void une_page_au_dela_du_total_est_vide_sans_erreur() {
        assertThat(pageDe("?page=9999&size=25")).isEmpty();
    }

    /** La taille de page est bornee : on ne peut pas reclamer tout le referentiel d'un coup. */
    @Test
    void la_taille_de_page_est_plafonnee() {
        ResponseEntity<Map> r = GET("/api/v1/admin/clients-fb?page=1&size=100000", agent);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) r.getBody().get("size")).longValue()).isLessThanOrEqualTo(100);
    }
}
