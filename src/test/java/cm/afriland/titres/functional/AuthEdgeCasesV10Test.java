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
 * Chemins d'erreur de {@code AuthController} : identifiants refuses, defis MFA
 * invalides, changement de mot de passe rejete, mot de passe oublie et OTP
 * d'action.
 *
 * <p><b>Contrainte forte</b> : {@code RateLimiter} n'autorise que 10 requetes
 * par minute et par cle {@code <endpoint>:<ip>}. Tous les tests partagent
 * 127.0.0.1, donc le nombre de connexions ratees doit rester sous ce quota —
 * d'ou l'absence volontaire de test de verrouillage apres 5 echecs (il
 * consommerait a lui seul la moitie du quota {@code login:}).</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({ "unchecked", "rawtypes" })
class AuthEdgeCasesV10Test {

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

    String tokClient;

    final BasicCookieStore cookieStore = new BasicCookieStore();
    RestTemplate rest;

    @BeforeAll
    void setUp() {
        rest = client(cookieStore);
        tokClient = login("jean.mballa@example.cm");
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

    String login(String email) {
        ResponseEntity<Map> step1 = POST("/api/v1/auth/login",
                Map.of("email", email, "password", "Demo1234"), null);
        assertThat(step1.getStatusCode()).as("login %s", email).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> step2 = POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", step1.getBody().get("challengeId"), "code", "123456"), null);
        assertThat(step2.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) step2.getBody().get("accessToken");
    }

    // ══════════════════════════════ /login ════════════════════════════════════

    /** Compte inexistant : 401 generique, jamais « e-mail inconnu ». */
    @Test
    void login_avec_un_email_inconnu_est_refuse_sans_reveler_l_existence_du_compte() {
        ResponseEntity<Map> r = POST("/api/v1/auth/login",
                Map.of("email", "inconnu@example.cm", "password", "Demo1234"), null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(String.valueOf(r.getBody())).doesNotContain("inconnu@example.cm");
    }

    /** Mot de passe errone : meme reponse que pour un compte inexistant. */
    @Test
    void login_avec_un_mauvais_mot_de_passe_est_refuse() {
        ResponseEntity<Map> r = POST("/api/v1/auth/login",
                Map.of("email", "jean.mballa@example.cm", "password", "MauvaisMDP1"), null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * L'e-mail est ramene en minuscules avant la recherche du compte.
     *
     * <p>Le {@code trim()} du controleur, lui, ne sert a rien pour les espaces :
     * la contrainte {@code @Email} s'applique <em>avant</em> et rejette en 400 un
     * e-mail entoure d'espaces (cf. {@link #login_avec_un_email_malforme_est_rejete_en_400}).</p>
     */
    @Test
    void login_normalise_la_casse_de_l_email() {
        ResponseEntity<Map> r = POST("/api/v1/auth/login",
                Map.of("email", "Jean.MBALLA@Example.CM", "password", "Demo1234"), null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("challengeId");
    }

    /** Un e-mail entoure d'espaces est rejete par la validation, pas normalise. */
    @Test
    void login_rejette_un_email_entoure_d_espaces() {
        ResponseEntity<Map> r = POST("/api/v1/auth/login",
                Map.of("email", "  jean.mballa@example.cm  ", "password", "Demo1234"), null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Un e-mail syntaxiquement invalide est rejete par la validation (400). */
    @Test
    void login_avec_un_email_malforme_est_rejete_en_400() {
        ResponseEntity<Map> r = POST("/api/v1/auth/login",
                Map.of("email", "pas-un-email", "password", "Demo1234"), null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ═══════════════════════════ /mfa/verify ══════════════════════════════════

    @Test
    void mfa_verify_avec_un_defi_inexistant_est_refuse() {
        ResponseEntity<Map> r = POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", UUID.randomUUID().toString(), "code", "123456"), null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Mauvais code : le compteur de tentatives du defi est incremente. */
    @Test
    void mfa_verify_avec_un_mauvais_code_est_refuse() {
        ResponseEntity<Map> step1 = POST("/api/v1/auth/login",
                Map.of("email", "jean.mballa@example.cm", "password", "Demo1234"), null);
        assertThat(step1.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> r = POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", step1.getBody().get("challengeId"), "code", "000000"), null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Un defi deja consomme ne peut pas resservir (rejeu interdit). */
    @Test
    void mfa_verify_ne_rejoue_pas_un_defi_deja_consomme() {
        ResponseEntity<Map> step1 = POST("/api/v1/auth/login",
                Map.of("email", "jean.mballa@example.cm", "password", "Demo1234"), null);
        Object challengeId = step1.getBody().get("challengeId");
        assertThat(POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", challengeId, "code", "123456"), null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> rejeu = POST("/api/v1/auth/mfa/verify",
                Map.of("challengeId", challengeId, "code", "123456"), null);

        assertThat(rejeu.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ════════════════════════ /change-password ════════════════════════════════

    /** Mot de passe actuel errone : refus, sans modifier le compte. */
    @Test
    void change_password_avec_un_mauvais_mot_de_passe_actuel_est_refuse() {
        ResponseEntity<Map> r = POST("/api/v1/auth/change-password",
                Map.of("currentPassword", "MauvaisMDP1", "newPassword", "NouveauMDP1"), tokClient);

        assertThat(r.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.BAD_REQUEST);
    }

    /** Nouveau mot de passe trop court : rejete par la validation avant tout traitement. */
    @Test
    void change_password_refuse_un_nouveau_mot_de_passe_trop_court() {
        ResponseEntity<Map> r = POST("/api/v1/auth/change-password",
                Map.of("currentPassword", "Demo1234", "newPassword", "court"), tokClient);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void change_password_exige_une_authentification() {
        ResponseEntity<Map> r = POST("/api/v1/auth/change-password",
                Map.of("currentPassword", "Demo1234", "newPassword", "NouveauMDP1"), null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ═════════════════════════ /forgot-password ═══════════════════════════════

    /**
     * Reponse generique : un e-mail inconnu et un e-mail connu donnent exactement
     * la meme reponse, sinon l'endpoint devient un oracle d'existence de comptes.
     */
    @Test
    void forgot_password_ne_revele_pas_si_le_compte_existe() {
        ResponseEntity<Map> inconnu = POST("/api/v1/auth/forgot-password",
                Map.of("email", "inconnu@example.cm"), null);
        ResponseEntity<Map> connu = POST("/api/v1/auth/forgot-password",
                Map.of("email", "jean.mballa@example.cm"), null);

        assertThat(inconnu.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(connu.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(connu.getBody()).isEqualTo(inconnu.getBody());
    }

    @Test
    void forgot_password_rejette_un_email_malforme() {
        ResponseEntity<Map> r = POST("/api/v1/auth/forgot-password",
                Map.of("email", "pas-un-email"), null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ═════════════════════════ /reset-password ════════════════════════════════

    @Test
    void reset_password_avec_un_jeton_inconnu_est_refuse() {
        ResponseEntity<Map> r = POST("/api/v1/auth/reset-password",
                Map.of("token", "jeton-qui-n-existe-pas", "newPassword", "NouveauMDP1"), null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reset_password_refuse_un_mot_de_passe_trop_court() {
        ResponseEntity<Map> r = POST("/api/v1/auth/reset-password",
                Map.of("token", "peu-importe", "newPassword", "court"), null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ══════════════════════════ OTP d'action ══════════════════════════════════

    /** Envoi d'un OTP d'action : un defi est cree et sa duree de vie annoncee. */
    @Test
    void otp_send_cree_un_defi_pour_l_utilisateur_connecte() {
        ResponseEntity<Map> r = POST("/api/v1/auth/otp/send", null, tokClient);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("challengeId")).isNotNull();
        assertThat(((Number) r.getBody().get("expiresIn")).intValue()).isPositive();
    }

    @Test
    void otp_send_exige_une_authentification() {
        assertThat(POST("/api/v1/auth/otp/send", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Le code correct consomme le defi ; un second usage echoue. */
    @Test
    void otp_verify_accepte_le_bon_code_puis_refuse_le_rejeu() {
        Object challengeId = POST("/api/v1/auth/otp/send", null, tokClient)
                .getBody().get("challengeId");

        ResponseEntity<Map> ok = POST("/api/v1/auth/otp/verify",
                Map.of("challengeId", challengeId, "code", "123456"), tokClient);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("ok")).isEqualTo(true);

        ResponseEntity<Map> rejeu = POST("/api/v1/auth/otp/verify",
                Map.of("challengeId", challengeId, "code", "123456"), tokClient);
        assertThat(rejeu.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void otp_verify_refuse_un_mauvais_code() {
        Object challengeId = POST("/api/v1/auth/otp/send", null, tokClient)
                .getBody().get("challengeId");

        ResponseEntity<Map> r = POST("/api/v1/auth/otp/verify",
                Map.of("challengeId", challengeId, "code", "000000"), tokClient);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void otp_verify_refuse_un_defi_inexistant() {
        ResponseEntity<Map> r = POST("/api/v1/auth/otp/verify",
                Map.of("challengeId", UUID.randomUUID().toString(), "code", "123456"), tokClient);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Un defi appartenant a un autre utilisateur ne peut pas etre verifie. */
    @Test
    void otp_verify_refuse_le_defi_d_un_autre_utilisateur() {
        // Defi cree pour le client, presente par l'agent.
        Object challengeIdClient = POST("/api/v1/auth/otp/send", null, tokClient)
                .getBody().get("challengeId");
        String tokAgent = login("agent@afriland.cm");

        ResponseEntity<Map> r = POST("/api/v1/auth/otp/verify",
                Map.of("challengeId", challengeIdClient, "code", "123456"), tokAgent);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ═════════════════════════ /refresh et /logout ════════════════════════════

    /** Sans cookie de rafraichissement, l'echange echoue. */
    @Test
    void refresh_sans_cookie_est_refuse() {
        RestTemplate sansCookie = client(new BasicCookieStore());

        ResponseEntity<Map> r = sansCookie.exchange(url("/api/v1/auth/refresh"),
                HttpMethod.POST, body(new LinkedHashMap<>(), null), Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void me_renvoie_le_profil_de_l_utilisateur_connecte() {
        ResponseEntity<Map> r = rest.exchange(url("/api/v1/auth/me"), HttpMethod.GET,
                body(null, tokClient), Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("email")).isEqualTo("jean.mballa@example.cm");
        // Un client ne voit jamais son solde via la plateforme.
        assertThat(r.getBody().get("solde")).isNull();
    }

    @Test
    void me_exige_une_authentification() {
        ResponseEntity<Map> r = rest.exchange(url("/api/v1/auth/me"), HttpMethod.GET,
                body(null, null), Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
