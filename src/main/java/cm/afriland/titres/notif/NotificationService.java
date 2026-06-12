package cm.afriland.titres.notif;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Notifications in-app (CSFT §M2-S03). Le client est notifie a chaque
 * changement d'etat d'un de ses ordres.
 *
 * Les echecs sont journalises mais n'interrompent jamais l'operation metier.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JdbcTemplate jdbc;

    public NotificationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Cree une notification pour un utilisateur. */
    public void notify(UUID userId, String type, String titre, String message, String reference) {
        try {
            jdbc.update(
                    "INSERT INTO notifications (user_id, type, titre, message, reference) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    userId, type, titre, message, reference);
        } catch (RuntimeException e) {
            log.error("echec de la creation de notification", e);
        }
    }

    /**
     * Cree une notification pour tous les utilisateurs actifs d'un role donne
     * (ex. tous les SUPERVISEUR pour une adjudication a valider, ou superviseurs
     * et admins pour une publication). Un seul INSERT ... SELECT.
     */
    public void notifyRole(String role, String type, String titre, String message, String reference) {
        try {
            jdbc.update(
                    "INSERT INTO notifications (user_id, type, titre, message, reference) "
                            + "SELECT id, ?, ?, ?, ? FROM users WHERE role = ? AND statut = 'ACTIF'",
                    type, titre, message, reference, role);
        } catch (RuntimeException e) {
            log.error("echec de la creation de notifications de role", e);
        }
    }

    /** Variante multi-roles (ex. SUPERVISEUR + ADMIN pour les publications). */
    public void notifyRoles(java.util.List<String> roles, String type, String titre,
                            String message, String reference) {
        for (String r : roles) {
            notifyRole(r, type, titre, message, reference);
        }
    }
}
