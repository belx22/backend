package cm.afriland.titres.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CSFT §M1-F04 — Cloture automatique des emissions dont la date limite de
 * souscription est depassee.
 *
 * <p>Une fiche {@code PUBLIE} dont {@code fermeture_souscription} est passee
 * bascule en {@code CLOTURE} de facon persistante (la base reste la source de
 * verite). Elle disparait ainsi du marche primaire cote client et n'est plus
 * souscriptible, sans dependre d'un traitement cote navigateur. Toute erreur est
 * journalisee sans interrompre les balayages suivants.</p>
 */
@Component
public class EmissionExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmissionExpiryScheduler.class);

    private final JdbcTemplate jdbc;

    public EmissionExpiryScheduler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Premier passage 5 s apres le demarrage (rattrape l'existant), puis toutes les 5 min. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 5_000)
    public void closeExpired() {
        try {
            int n = jdbc.update("UPDATE emissions SET status = 'CLOTURE', updated_at = now() "
                    + "WHERE status = 'PUBLIE' AND fermeture_souscription < now()");
            if (n > 0) {
                log.info("Cloture automatique : {} emission(s) expiree(s) passee(s) en CLOTURE.", n);
            }
        } catch (RuntimeException e) {
            log.error("Echec de la cloture automatique des emissions expirees", e);
        }
    }
}
