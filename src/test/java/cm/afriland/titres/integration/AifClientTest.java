package cm.afriland.titres.integration;

import cm.afriland.titres.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AifClientTest {

    private AifClient client(String baseUrl) {
        AppProperties props = new AppProperties();
        props.setAifBaseUrl(baseUrl);
        return new AifClient(props);
    }

    @Test
    void ping_url_absente_renvoie_non_configure() {
        AifClient.PingResult r = client("").ping();
        assertFalse(r.available());
        assertEquals("(non configure)", r.endpoint());
    }

    @Test
    void ping_url_nulle_non_configure() {
        AifClient.PingResult r = client(null).ping();
        assertFalse(r.available());
    }

    @Test
    void ping_serveur_injoignable_indisponible() {
        // Port 1 : aucune ecoute → connexion refusee rapidement.
        AifClient.PingResult r = client("https://127.0.0.1:1/aif").ping();
        assertFalse(r.available());
        assertEquals("https://127.0.0.1:1/aif", r.endpoint());
    }

    @Test
    void getJson_serveur_injoignable_leve_AifUnavailable() {
        AifClient aif = client("https://127.0.0.1:1/aif");
        assertThrows(AifUnavailableException.class, () -> aif.getJson("/", Map.of()));
    }

    @Test
    void pingResult_expose_ses_champs() {
        AifClient.PingResult r = new AifClient.PingResult(true, 42L, "http://x");
        assertTrue(r.available());
        assertEquals(42L, r.latencyMs());
        assertEquals("http://x", r.endpoint());
    }
}
