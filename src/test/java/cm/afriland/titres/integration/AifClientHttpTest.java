package cm.afriland.titres.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cm.afriland.titres.config.AppProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exerce {@link AifClient} contre un vrai serveur HTTP local (JDK), afin de
 * couvrir les branches de reponse d'AIF que les tests « serveur injoignable »
 * n'atteignent pas : succes 2xx, 404 normalise, degradation sur 5xx, en-tete
 * d'authentification Basic et en-tetes additionnels.
 */
class AifClientHttpTest {

    private HttpServer server;
    private String baseUrl;

    /** Derniere requete recue — permet d'inspecter les en-tetes envoyes. */
    private final AtomicReference<HttpExchange> lastExchange = new AtomicReference<>();

    /** Code HTTP et corps que le faux AIF renverra a la prochaine requete. */
    private int status = 200;
    private String body = "{\"accounts\":[{\"id\":\"1\"}],\"totalCount\":1}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        lastExchange.set(ex);
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        // 204/304 interdisent un corps : -1 signale « pas de contenu ».
        ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        }
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AifClient client(AppProperties props) {
        props.setAifBaseUrl(baseUrl);
        return new AifClient(props);
    }

    private AifClient client() {
        return client(new AppProperties());
    }

    // ───────────────────────────────── ping ─────────────────────────────────

    @Test
    void ping_repond_disponible_et_mesure_la_latence() {
        AifClient.PingResult r = client().ping();

        assertThat(r.available()).isTrue();
        assertThat(r.endpoint()).isEqualTo(baseUrl);
        assertThat(r.latencyMs()).isNotNegative();
    }

    /** Un 401/404 prouve qu'AIF repond : le serveur est joignable. */
    @Test
    void ping_considere_disponible_tout_statut_inferieur_a_500() {
        status = 401;
        assertThat(client().ping().available()).isTrue();
    }

    /** Un 5xx signale au contraire un serveur en panne. */
    @Test
    void ping_considere_indisponible_a_partir_de_500() {
        status = 503;
        assertThat(client().ping().available()).isFalse();
    }

    /** Le changement d'etat DOWN -> UP -> DOWN doit rester silencieux et sans effet de bord. */
    @Test
    void ping_supporte_les_transitions_d_etat() {
        AifClient aif = client();
        status = 200;
        assertThat(aif.ping().available()).isTrue();
        status = 500;
        assertThat(aif.ping().available()).isFalse();
        status = 200;
        assertThat(aif.ping().available()).isTrue();
    }

    // ──────────────────────────────── getJson ───────────────────────────────

    @Test
    void getJson_renvoie_le_corps_tel_quel_sur_2xx() {
        assertThat(client().getJson("accounts", Map.of())).isEqualTo(body);
    }

    /** Un 404 signifie « donnee absente », pas « AIF en panne » : liste vide normalisee. */
    @Test
    void getJson_normalise_le_404_en_liste_vide() {
        status = 404;
        body = "not found";

        assertThat(client().getJson("accounts", Map.of()))
                .isEqualTo("{\"accounts\":[],\"totalCount\":0}");
    }

    @Test
    void getJson_degrade_gracieusement_sur_500() {
        status = 500;

        assertThatThrownBy(() -> client().getJson("accounts", Map.of()))
                .isInstanceOf(AifUnavailableException.class)
                .hasMessageContaining("500");
    }

    @Test
    void getJson_degrade_gracieusement_sur_401() {
        status = 401;

        assertThatThrownBy(() -> client().getJson("accounts", Map.of()))
                .isInstanceOf(AifUnavailableException.class)
                .hasMessageContaining("401");
    }

    @Test
    void getJson_leve_AifUnavailable_quand_l_url_n_est_pas_configuree() {
        AppProperties props = new AppProperties();
        props.setAifBaseUrl("");

        assertThatThrownBy(() -> new AifClient(props).getJson("accounts", Map.of()))
                .isInstanceOf(AifUnavailableException.class)
                .hasMessageContaining("non configure");
    }

    /** L'URL de base peut se terminer ou non par « / » : le chemin ne doit jamais doubler le separateur. */
    @Test
    void getJson_ne_double_pas_le_separateur_de_chemin() {
        AppProperties props = new AppProperties();
        props.setAifBaseUrl(baseUrl + "/");
        new AifClient(props).getJson("accounts", Map.of());

        assertThat(lastExchange.get().getRequestURI().getPath()).isEqualTo("/accounts");
    }

    @Test
    void getJson_ajoute_l_authentification_basic_quand_les_identifiants_existent() {
        AppProperties props = new AppProperties();
        props.setAifLogin("bob");
        props.setAifPassword("s3cr3t");

        client(props).getJson("accounts", Map.of());

        String attendu = "Basic " + Base64.getEncoder()
                .encodeToString("bob:s3cr3t".getBytes(StandardCharsets.UTF_8));
        assertThat(lastExchange.get().getRequestHeaders().getFirst("Authorization")).isEqualTo(attendu);
    }

    @Test
    void getJson_n_envoie_pas_d_authentification_sans_identifiants() {
        client().getJson("accounts", Map.of());

        assertThat(lastExchange.get().getRequestHeaders().getFirst("Authorization")).isNull();
    }

    @Test
    void getJson_transmet_les_en_tetes_additionnels() {
        client().getJson("accounts", Map.of("X-SBS-account", "0001234567"));

        assertThat(lastExchange.get().getRequestHeaders().getFirst("X-SBS-account"))
                .isEqualTo("0001234567");
    }

    @Test
    void getJson_accepte_une_map_d_en_tetes_nulle() {
        assertThatCode(() -> client().getJson("accounts", null)).doesNotThrowAnyException();
    }

    // ─────────────────────────── construction TLS ───────────────────────────

    /**
     * Certificat epingle illisible : on NE retombe PAS en TLS non verifie, le
     * client se construit sur le magasin de confiance systeme.
     */
    @Test
    void construction_avec_certificat_illisible_ne_desactive_pas_le_TLS(@TempDir Path tmp) throws IOException {
        Path pasUnCertificat = tmp.resolve("aif.pem");
        Files.writeString(pasUnCertificat, "ceci n'est pas un certificat X.509");

        AppProperties props = new AppProperties();
        props.setAifCaCertPath(pasUnCertificat.toString());

        assertThatCode(() -> client(props).getJson("accounts", Map.of())).doesNotThrowAnyException();
    }

    @Test
    void construction_avec_chemin_de_certificat_inexistant_reste_fonctionnelle() {
        AppProperties props = new AppProperties();
        props.setAifCaCertPath("/chemin/inexistant/aif.pem");

        assertThatCode(() -> client(props).getJson("accounts", Map.of())).doesNotThrowAnyException();
    }
}
