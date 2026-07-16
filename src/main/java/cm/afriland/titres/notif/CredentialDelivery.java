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
     * Transmet le mot de passe provisoire au nouvel utilisateur, à la suite de
     * l'ouverture de son compte-titres.
     *
     * <p>La salutation s'adapte au type de titulaire : « Bonjour Mr/Mme … » pour
     * une personne physique, « À l'attention de … » pour une personne morale.
     * Le numéro de compte-titres est <b>masqué</b> (seuls les 5 premiers et 3
     * derniers caractères restent visibles) — il n'apparaît jamais en entier.</p>
     *
     * @param destEmail      identifiant de connexion (e-mail)
     * @param telephone      numero du destinataire (pour le SMS, peut etre {@code null})
     * @param nomComplet     libelle d'adresse (nom du titulaire ou raison sociale)
     * @param compteTitres   numero de compte-titres (affiche masque ; peut etre {@code null})
     * @param personneMorale vrai pour une personne morale (PM), faux pour une PP
     * @param motDePasse     mot de passe provisoire en clair (jamais stocke)
     * @param sms            true pour tenter aussi l'envoi SMS (prepare)
     */
    public void sendInitialPassword(String destEmail, String telephone, String nomComplet,
                                    String compteTitres, boolean personneMorale,
                                    String motDePasse, boolean sms) {
        String subject = "Vos identifiants — Plateforme Valeurs du Trésor";
        String salutation = personneMorale
                ? "<p>À l'attention de " + esc(nomComplet) + ",</p>"
                : "<p>Bonjour Mr/Mme " + esc(nomComplet) + ",</p>";
        String html = salutation
                + "<p>Votre compte vient d'être créé. Voici vos identifiants de première connexion :</p>"
                + "<ul>"
                + "<li>Identifiant : <b>" + esc(destEmail) + "</b></li>"
                + "<li>Mot de passe provisoire : <b>" + esc(motDePasse) + "</b></li>"
                + "</ul>";
        if (compteTitres != null && !compteTitres.isBlank()) {
            html += "<p>Votre compte-titres : <b>" + esc(maskCompte(compteTitres)) + "</b></p>";
        }
        html += "<p>Pour votre sécurité, ce mot de passe vous sera demandé puis devra être "
                + "remplacé dès votre première connexion.</p>";

        if (destEmail != null && destEmail.contains("@")) {
            // Envoi NON bloquant AVEC reprise : la creation du compte ne doit pas
            // attendre le SMTP, et un hoquet DNS/reseau transitoire ne doit pas
            // faire perdre silencieusement l'e-mail d'identifiants (cf. OTP).
            email.dispatchOne(destEmail, subject, html);
        }
        if (sms && telephone != null && !telephone.isBlank()) {
            // Passerelle SMS preparee : a brancher (otp_settings.sms_api_url).
            log.info("SMS (simulé) vers {} : transmission du mot de passe initial.", telephone);
        }
    }

    /**
     * Confirme au client l'ouverture de son compte-titres (à la validation du
     * dossier par le back-office). Salutation adaptée au type (« Bonjour Mr/Mme »
     * pour une PP, « À l'attention de » pour une PM) et numéro de compte-titres
     * <b>masqué</b>. Envoi NON bloquant avec reprise (comme l'OTP).
     */
    public void sendAccountOpened(String destEmail, String nomComplet, String compteTitres,
                                  boolean personneMorale) {
        if (destEmail == null || !destEmail.contains("@")) {
            return;
        }
        String subject = "Ouverture de votre compte-titres — Plateforme Valeurs du Trésor";
        String salutation = personneMorale
                ? "<p>À l'attention de " + esc(nomComplet) + ",</p>"
                : "<p>Bonjour Mr/Mme " + esc(nomComplet) + ",</p>";
        String html = salutation
                + "<p>Nous vous confirmons l'ouverture de votre <b>compte-titres</b> sur la "
                + "Plateforme Valeurs du Trésor.</p>";
        if (compteTitres != null && !compteTitres.isBlank()) {
            html += "<p>Votre compte-titres : <b>" + esc(maskCompte(compteTitres)) + "</b></p>";
        }
        html += "<p>Vous pouvez dès à présent vous connecter pour consulter votre compte et "
                + "passer des ordres sur les valeurs du Trésor.</p>";
        email.dispatchOne(destEmail, subject, html);
    }

    /**
     * Masque un numéro de compte : seuls les 5 premiers et 3 derniers caractères
     * restent visibles, tout le reste est remplacé par des « x ». Un numéro trop
     * court (≤ 8 caractères) est renvoyé tel quel (rien d'utile à masquer).
     */
    public static String maskCompte(String compte) {
        if (compte == null) return "";
        String s = compte.trim();
        if (s.length() <= 8) return s;
        return s.substring(0, 5) + "x".repeat(s.length() - 8) + s.substring(s.length() - 3);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
