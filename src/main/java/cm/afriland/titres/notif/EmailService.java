package cm.afriland.titres.notif;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import cm.afriland.titres.notif.MailSettingsService.MailSettings;
import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Envoi d'e-mails via les parametres SMTP configures par l'administrateur
 * ({@link MailSettingsService}). Si la messagerie n'est pas activee / configuree,
 * le service bascule en mode « simulation » : chaque message est journalise au
 * lieu d'etre envoye (utile en dev / avant configuration).
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    public enum Status { SENT, SIMULATED, FAILED }

    private final MailSettingsService settings;

    /** Pool d'envoi en arriere-plan : l'appelant (OTP, reinitialisation) n'attend
     *  PAS la latence du serveur SMTP (connexion + STARTTLS + auth + envoi). */
    private final ExecutorService mailPool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "mail-sender");
        t.setDaemon(true);
        return t;
    });

    public EmailService(MailSettingsService settings) {
        this.settings = settings;
    }

    @PreDestroy
    void shutdown() {
        mailPool.shutdown();
    }

    /** Vrai quand la messagerie est activee et configuree. */
    public boolean isConfigured() {
        return settings.usable();
    }

    /**
     * Envoi NON bloquant : la requete HTTP appelante revient immediatement, le
     * SMTP est contacte en arriere-plan. A utiliser pour les OTP et les e-mails
     * de reinitialisation (ou la reactivite prime sur le retour de statut).
     * En mode simulation (messagerie non configuree), l'envoi reste synchrone
     * car instantane (simple journalisation).
     */
    public void dispatch(List<String> recipients, String subject, String htmlBody) {
        if (recipients == null || recipients.isEmpty()) return;
        if (!settings.usable()) {
            send(recipients, subject, htmlBody); // simulation : instantanee
            return;
        }
        mailPool.submit(() -> {
            try {
                send(recipients, subject, htmlBody);
            } catch (RuntimeException e) {
                log.warn("Echec d'envoi e-mail (arriere-plan) : {}", e.getMessage());
            }
        });
    }

    /** Envoi unitaire NON bloquant (OTP, lien de reinitialisation). */
    public void dispatchOne(String to, String subject, String htmlBody) {
        dispatch(List.of(to), subject, htmlBody);
    }

    /**
     * Envoie un e-mail HTML aux destinataires donnes (un envoi par destinataire,
     * via {@code To:} — pas de fuite d'adresses comme avec un Cc).
     */
    public Status send(List<String> recipients, String subject, String htmlBody) {
        if (recipients == null || recipients.isEmpty()) {
            return Status.SENT;
        }
        if (!settings.usable()) {
            log.info("Messagerie non configuree/activee — simulation. Sujet=\"{}\" destinataires={}",
                    subject, recipients.size());
            return Status.SIMULATED;
        }

        MailSettings cfg = settings.get();
        JavaMailSenderImpl sender = settings.buildSender();
        // Habillage commun : en-tete logo Afriland + contenu + signature configurable.
        String brandedBody = settings.brand(htmlBody);
        Status overall = Status.SENT;
        for (String to : recipients) {
            try {
                MimeMessage msg = sender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(msg, false,
                        StandardCharsets.UTF_8.name());
                helper.setFrom(new InternetAddress(cfg.fromAddress(),
                        cfg.fromName(), StandardCharsets.UTF_8.name()));
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(brandedBody, true);
                sender.send(msg);
            } catch (Exception e) {
                log.warn("Echec d'envoi a {} : {}", to, e.getMessage());
                overall = Status.FAILED;
            }
        }
        return overall;
    }

    /** Envoi unitaire (e-mail de test depuis l'espace d'administration). */
    public Status sendOne(String to, String subject, String htmlBody) {
        return send(List.of(to), subject, htmlBody);
    }
}
