package cm.afriland.titres.integration;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import cm.afriland.titres.config.AppProperties;

/**
 * Client HTTP du serveur Amplitude/AIF (Sopra) — appele exclusivement depuis le
 * backend pour ne jamais exposer les identifiants AIF au navigateur. Le TLS
 * peut etre configure en mode "trust-all" pour le serveur AIF interne qui
 * presente un certificat auto-signe (cf. CSFT §INT-CB01).
 */
@Component
public class AifClient {

    private static final Logger log = LoggerFactory.getLogger(AifClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    /** Le ping a un budget de temps court pour ne pas bloquer la barre de statut. */
    private static final Duration PING_TIMEOUT = Duration.ofSeconds(4);

    private static final String APPLICATION_JSON = "application/json";
    private final AppProperties props;
    private final HttpClient http;
    /** Etat courant de la connexion AIF, mis a jour a chaque ping. */
    private final java.util.concurrent.atomic.AtomicBoolean lastPingOk =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public AifClient(AppProperties props) {
        this.props = props;
        this.http = buildClient(props.getAifCaCertPath());
    }

    /** Resultat d'un ping AIF — utilise par le badge de statut dans la barre BO. */
    public record PingResult(boolean available, long latencyMs, String endpoint) {
    }

    /**
     * Ping discret vers le serveur AIF — GET racine, timeout court, swallow
     * total. Aucune trace d'audit ; un log INFO uniquement lors d'un changement
     * d'etat (UP -> DOWN ou DOWN -> UP) pour eviter d'inonder les logs.
     */
    public PingResult ping() {
        String base = props.getAifBaseUrl();
        if (base == null || base.isBlank()) {
            transitionTo(false, base);
            return new PingResult(false, 0L, "(non configure)");
        }
        long t0 = System.currentTimeMillis();
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base))
                    .timeout(PING_TIMEOUT)
                    .header("Accept", APPLICATION_JSON)
                    .GET();
            String login = props.getAifLogin();
            String password = props.getAifPassword();
            if (login != null && password != null) {
                String token = Base64.getEncoder().encodeToString(
                        (login + ":" + password).getBytes(StandardCharsets.UTF_8));
                req.header("Authorization", "Basic " + token);
            }
            HttpResponse<Void> resp = http.send(req.build(), HttpResponse.BodyHandlers.discarding());
            int code = resp.statusCode();
            // Tout statut < 500 prouve qu'AIF repond : meme 401/403/404 signifient
            // que le serveur est joignable (la consultation reelle est verifiee
            // ailleurs avec ses propres credentials).
            boolean ok = code < 500;
            transitionTo(ok, base);
            return new PingResult(ok, System.currentTimeMillis() - t0, base);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            transitionTo(false, base);
            return new PingResult(false, System.currentTimeMillis() - t0, base);
        }
    }

    private void transitionTo(boolean ok, String endpoint) {
        boolean previous = lastPingOk.getAndSet(ok);
        if (previous != ok) {
            if (ok) {
                log.info("AIF accessible ({}).", endpoint);
            } else {
                log.warn("AIF injoignable ({}).", endpoint);
            }
        }
    }

    /**
     * GET {baseUrl}/{path} avec en-tete X-SBS-account. Renvoie le corps JSON
     * brut tel quel : le controleur appelant choisit de le re-serialiser ou de
     * le projeter dans son propre DTO.
     */
    public String getJson(String path, java.util.Map<String, String> headers) {
        String base = props.getAifBaseUrl();
        if (base == null || base.isBlank()) {
            log.warn("APP_AIF_BASE_URL n'est pas configure — appel ignore.");
            throw new AifUnavailableException("AIF non configure");
        }

        String url = base.endsWith("/") ? base + path : base + "/" + path;
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", APPLICATION_JSON)
                .header("Content-Type", APPLICATION_JSON)
                .GET();

        String login = props.getAifLogin();
        String password = props.getAifPassword();
        if (login != null && password != null) {
            String token = Base64.getEncoder().encodeToString(
                    (login + ":" + password).getBytes(StandardCharsets.UTF_8));
            req.header("Authorization", "Basic " + token);
        }

        if (headers != null) {
            for (var e : headers.entrySet()) {
                req.header(e.getKey(), e.getValue());
            }
        }

        try {
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                return resp.body();
            }
            if (code == 404) {
                // Donnee absente cote AIF -> resultat vide, pas une indisponibilite.
                return EMPTY_LIST_JSON;
            }
            // Tout autre code (auth, 5xx, etc.) : on degrade gracieusement.
            log.warn("AIF a renvoye HTTP {} sur {} — degradation gracieuse.", code, url);
            throw new AifUnavailableException("AIF HTTP " + code);
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("AIF injoignable ({}) — degradation gracieuse.", e.getMessage());
            throw new AifUnavailableException("AIF injoignable", e);
        }
    }

    /** Reponse vide normalisee pour les cas ou AIF ne trouve pas le compte. */
    private static final String EMPTY_LIST_JSON = "{\"accounts\":[],\"totalCount\":0}";

    /**
     * Construit le client HTTP du serveur AIF.
     *
     * <p>La validation TLS est <b>toujours active</b> (protection contre les
     * attaques de type « man-in-the-middle ») : la seule adaptation possible pour
     * un serveur AIF a certificat auto-signe est d'<b>epingler</b> ce certificat
     * via {@code caCertPath}. Le magasin de confiance construit ne contient alors
     * que ce certificat, et l'identite du serveur est verifiee normalement.
     * Sans chemin fourni, on s'appuie sur le magasin de confiance systeme
     * (validation standard).</p>
     */
    private static HttpClient buildClient(String caCertPath) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(TIMEOUT);
        if (caCertPath != null) {
            try {
                SSLContext ctx = pinnedContext(caCertPath);
                b.sslContext(ctx);
            } catch (Exception e) {
                // On NE retombe PAS en connexion non validee : mieux vaut une
                // indisponibilite (degradation gracieuse) qu'un TLS non verifie.
                log.error("Certificat AIF epingle illisible ({}) : {} — "
                        + "validation TLS systeme utilisee par defaut.", caCertPath, e.getMessage());
            }
        }
        return b.build();
    }

    /**
     * Construit un {@link SSLContext} dont le magasin de confiance ne contient
     * que le certificat AIF fourni (validation stricte du serveur auto-signe).
     */
    private static SSLContext pinnedContext(String caCertPath) throws Exception {
        java.security.cert.CertificateFactory cf =
                java.security.cert.CertificateFactory.getInstance("X.509");
        java.security.cert.Certificate cert;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(
                java.nio.file.Path.of(caCertPath))) {
            cert = cf.generateCertificate(in);
        }
        java.security.KeyStore ks = java.security.KeyStore.getInstance(
                java.security.KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("aif", cert);

        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), new SecureRandom());
        return ctx;
    }
}
