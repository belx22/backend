package cm.afriland.titres.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rejet automatique des ordres de comptes joints dont le delai de validation des
 * co-signataires (5 min) est depasse.
 *
 * <p>Balayage periodique : chaque demande de signature {@code PENDING} expiree
 * passe {@code EXPIRED} ; l'ordre encore en attente est rejete ({@code ANNULE})
 * et tous les signataires du compte sont notifies. Toute erreur est journalisee
 * sans interrompre les balayages suivants.</p>
 */
@Component
public class JointSignatureScheduler {

    private static final Logger log = LoggerFactory.getLogger(JointSignatureScheduler.class);

    private final JdbcTemplate jdbc;

    public JointSignatureScheduler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Toutes les 30 s (premier passage apres 30 s) — granularite suffisante pour un TTL de 5 min. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void rejectExpired() {
        try {
            List<Map<String, Object>> orders = jdbc.queryForList(
                    "SELECT DISTINCT o.id, o.reference, o.client_id "
                            + "FROM order_signatures s JOIN orders o ON o.id = s.order_id "
                            + "WHERE s.status = 'PENDING' AND s.expires_at < now() "
                            + "AND o.status = 'EN_ATTENTE_SIGNATURES'");
            if (orders.isEmpty()) {
                return;
            }

            jdbc.update("UPDATE order_signatures SET status = 'EXPIRED', decided_at = now() "
                    + "WHERE status = 'PENDING' AND expires_at < now()");

            for (Map<String, Object> o : orders) {
                UUID id = (UUID) o.get("id");
                UUID account = (UUID) o.get("client_id");
                String reference = (String) o.get("reference");
                int n = jdbc.update("UPDATE orders SET status = 'ANNULE', "
                        + "motif_annulation = 'Délai de validation des co-signataires dépassé (5 min)', "
                        + "updated_at = now() WHERE id = ? AND status = 'EN_ATTENTE_SIGNATURES'", id);
                if (n > 0) {
                    jdbc.update("INSERT INTO notifications (user_id, type, titre, message, reference) "
                                    + "SELECT id, 'WARN', 'Opération rejetée', ?, ? "
                                    + "FROM users WHERE id = ? OR account_holder_id = ?",
                            "L'ordre " + reference + " a été rejeté : un signataire n'a pas validé "
                                    + "l'opération dans le délai imparti.", reference, account, account);
                }
            }
            log.info("Co-signature : {} ordre(s) rejete(s) pour delai depasse.", orders.size());
        } catch (RuntimeException e) {
            log.error("Echec du balayage des signatures de co-titulaires expirees", e);
        }
    }
}
