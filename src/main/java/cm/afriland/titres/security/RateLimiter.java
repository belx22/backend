package cm.afriland.titres.security;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Limiteur de debit en memoire — fenetre glissante.
 *
 * Applique aux points d'entree sensibles (connexion, MFA) pour ralentir les
 * attaques par force brute. 10 requetes autorisees par minute et par cle (IP).
 * Une solution de production utiliserait un magasin partage (Redis).
 */
@Component
public class RateLimiter {

    private static final int MAX_HITS = 10;
    private static final long WINDOW_MILLIS = 60_000;

    private final Map<String, List<Long>> hits = new ConcurrentHashMap<>();

    /**
     * Enregistre une tentative pour la cle donnee (quota par defaut). Renvoie
     * {@code true} si la requete est autorisee, {@code false} si le quota est
     * depasse.
     */
    public boolean check(String key) {
        return check(key, MAX_HITS);
    }

    /**
     * Variante avec quota explicite. Permet d'assouplir certaines routes peu
     * sensibles a la force brute (ex. {@code /auth/refresh}, protege par un
     * cookie HttpOnly) pour tolerer des actualisations repetees de la page.
     */
    public synchronized boolean check(String key, int maxHits) {
        long now = System.currentTimeMillis();

        // Purge opportuniste pour eviter une croissance non bornee.
        if (hits.size() > 10_000) {
            hits.entrySet().removeIf(e -> e.getValue().stream()
                    .noneMatch(t -> now - t < WINDOW_MILLIS));
        }

        List<Long> entry = hits.computeIfAbsent(key, k -> new ArrayList<>());
        Iterator<Long> it = entry.iterator();
        while (it.hasNext()) {
            if (now - it.next() >= WINDOW_MILLIS) {
                it.remove();
            }
        }
        if (entry.size() >= maxHits) {
            return false;
        }
        entry.add(now);
        return true;
    }
}
