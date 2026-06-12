package cm.afriland.titres.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Ecriture dans le journal d'audit immuable (CSFT §M6).
 *
 * L'echec d'une ecriture d'audit ne fait jamais echouer la requete metier :
 * il est journalise en erreur mais l'operation se poursuit.
 */
@Service
public class AuditService {

    public static final String SUCCES = "SUCCES";
    public static final String ECHEC = "ECHEC";

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insere une entree dans {@code audit_log}. */
    public void log(String utilisateur, String action, String resultat, String reference, String ip) {
        try {
            jdbc.update(
                    "INSERT INTO audit_log (utilisateur, action, resultat, reference, ip) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    utilisateur, action, resultat, reference, ip);
        } catch (RuntimeException e) {
            log.error("echec de l'ecriture du journal d'audit", e);
        }
    }
}
