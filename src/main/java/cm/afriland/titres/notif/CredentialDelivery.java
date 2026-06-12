package cm.afriland.titres.notif;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Remise des identifiants de premiere connexion au nouvel utilisateur.
 *
 * <p>Le mot de passe initial est genere aleatoirement a la creation du compte
 * puis transmis par e-mail (SMTP existant) et/ou par SMS. Le canal SMS est
 * <em>prepare</em> : tant qu'aucune passerelle n'est branchee, l'envoi SMS est
 * simule (journalise). Le mot de passe n'est jamais persiste en clair.</p>
 */
@Service
public class CredentialDelivery {

    private static final Logger log = LoggerFactory.getLogger(CredentialDelivery.class);

    private final EmailService email;

    public CredentialDelivery(EmailService email) {
        this.email = email;
    }

    /**
     * Transmet le mot de passe provisoire au nouvel utilisateur.
     *
     * @param destEmail  identifiant de connexion (e-mail)
     * @param telephone  numero du destinataire (pour le SMS, peut etre {@code null})
     * @param nomComplet libelle d'adresse
     * @param motDePasse mot de passe provisoire en clair (jamais stocke)
     * @param sms        true pour tenter aussi l'envoi SMS (prepare)
     */
    public void sendInitialPassword(String destEmail, String telephone, String nomComplet,
                                    String motDePasse, boolean sms) {
        String subject = "Vos identifiants — Plateforme Valeurs du Trésor";
        String html = "<p>Bonjour " + esc(nomComplet) + ",</p>"
                + "<p>Votre compte vient d'être créé. Voici vos identifiants de première connexion :</p>"
                + "<ul>"
                + "<li>Identifiant : <b>" + esc(destEmail) + "</b></li>"
                + "<li>Mot de passe provisoire : <b>" + esc(motDePasse) + "</b></li>"
                + "</ul>"
                + "<p>Pour votre sécurité, ce mot de passe vous sera demandé puis devra être "
                + "remplacé dès votre première connexion.</p>";

        if (destEmail != null && destEmail.contains("@")) {
            email.sendOne(destEmail, subject, html);
        }
        if (sms && telephone != null && !telephone.isBlank()) {
            // Passerelle SMS preparee : a brancher (otp_settings.sms_api_url).
            log.info("SMS (simulé) vers {} : transmission du mot de passe initial.", telephone);
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
