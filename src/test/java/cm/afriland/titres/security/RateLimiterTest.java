package cm.afriland.titres.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private RateLimiter rateLimiter;

    @BeforeEach
    void setup() {
        rateLimiter = new RateLimiter();
    }

    @Test
    void check_autorise_premiere_requete() {
        assertTrue(rateLimiter.check("192.168.0.1"));
    }

    @Test
    void check_autorise_jusqu_a_10_requetes() {
        String ip = "10.0.0.1";
        for (int i = 0; i < 10; i++) {
            assertTrue(rateLimiter.check(ip), "Requête " + (i + 1) + " doit être autorisée");
        }
    }

    @Test
    void check_bloque_la_11eme_requete() {
        String ip = "10.0.0.2";
        for (int i = 0; i < 10; i++) {
            rateLimiter.check(ip);
        }
        assertFalse(rateLimiter.check(ip), "La 11e requête doit être bloquée");
    }

    @Test
    void check_cles_differentes_sont_independantes() {
        String ip1 = "10.0.1.1";
        String ip2 = "10.0.1.2";
        for (int i = 0; i < 10; i++) {
            rateLimiter.check(ip1);
        }
        assertTrue(rateLimiter.check(ip2), "ip2 doit rester autorisé indépendamment de ip1");
    }

    @Test
    void check_quota_explicite_5_bloque_a_la_6eme() {
        String ip = "10.0.0.3";
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.check(ip, 5), "Requête " + (i + 1) + " dans quota=5");
        }
        assertFalse(rateLimiter.check(ip, 5), "6e requête hors quota=5");
    }

    @Test
    void check_quota_1_bloque_des_la_deuxieme() {
        String ip = "10.0.0.4";
        assertTrue(rateLimiter.check(ip, 1));
        assertFalse(rateLimiter.check(ip, 1));
    }

    @Test
    void check_cles_multiples_independantes() {
        for (int k = 0; k < 10; k++) {
            String ip = "172.16.0." + k;
            for (int i = 0; i < 10; i++) {
                assertTrue(rateLimiter.check(ip), "ip=" + ip + " requête " + (i + 1));
            }
        }
    }

    @Test
    void check_quota_zero_bloque_immediatement() {
        assertFalse(rateLimiter.check("10.0.0.5", 0));
    }

    @Test
    void check_default_appelle_check_avec_10() {
        String ip = "10.0.0.6";
        // Le quota par défaut est 10 — la 10e doit passer, la 11e doit être bloquée
        for (int i = 0; i < 10; i++) {
            assertTrue(rateLimiter.check(ip));
        }
        assertFalse(rateLimiter.check(ip));
    }
}
