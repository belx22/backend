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

    /** Nombre total de tentatives d'envoi en arriere-plan (1 initiale + reprises). */
    private static final int MAX_ATTEMPTS = 5;
    /** Attente avant chaque tentative (ms). La 1re est immediate ; les suivantes
     *  s'etalent jusqu'a ~110 s au total, de quoi encaisser une panne DNS/reseau
     *  de plusieurs minutes (ex. resolution intermittente de l'hote SMTP) sans
     *  perdre silencieusement l'e-mail. */
    private static final long[] BACKOFF_MS = {0L, 5_000L, 15_000L, 30_000L, 60_000L};

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
        mailPool.submit(() -> sendWithRetry(recipients, subject, htmlBody));
    }

    /**
     * Envoi en arriere-plan avec reprise : les incidents transitoires (coupure DNS,
     * SMTP momentanement injoignable) ne doivent pas faire perdre SILENCIEUSEMENT un
     * code OTP ou un lien de reinitialisation — l'appelant a deja repondu « code
     * envoye » a l'utilisateur. On retente donc {@link #MAX_ATTEMPTS} fois avec un
     * back-off avant d'abandonner. Reserve aux envois mono-destinataire (OTP,
     * reinitialisation, ouverture de compte) : une reprise ne peut pas creer de
     * doublon pour un destinataire deja servi.
     */
    private void sendWithRetry(List<String> recipients, String subject, String htmlBody) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (BACKOFF_MS[attempt] > 0 && !sleep(BACKOFF_MS[attempt])) {
                return; // thread interrompu (arret de l'application) : on abandonne.
            }
            Status status;
            try {
                status = send(recipients, subject, htmlBody);
            } catch (RuntimeException e) {
                status = Status.FAILED;
                log.warn("Echec d'envoi e-mail (arriere-plan, tentative {}/{}) : {}",
                        attempt + 1, MAX_ATTEMPTS, e.getMessage());
            }
            if (status != Status.FAILED) {
                return; // SENT ou SIMULATED : termine.
            }
        }
        log.warn("E-mail non distribue apres {} tentatives. Sujet=\"{}\" destinataires={}",
                MAX_ATTEMPTS, subject, recipients.size());
    }

    /** Attente interruptible ; renvoie {@code false} si le thread a ete interrompu. */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
