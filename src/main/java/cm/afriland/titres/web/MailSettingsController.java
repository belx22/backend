package cm.afriland.titres.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.notif.EmailService;
import cm.afriland.titres.notif.MailSettingsService;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;

/**
 * Espace d'administration — parametres de connexion a la messagerie (SMTP).
 *
 * <p>Reserve a l'administrateur. Permet de configurer l'envoi d'e-mails sans
 * toucher au code ni aux variables d'environnement. Le mot de passe n'est
 * jamais renvoye (write-only) ; un drapeau {@code passwordSet} indique s'il est
 * defini. Chaque modification et chaque test sont audites.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/mail-settings")
public class MailSettingsController {

    private final MailSettingsService settings;
    private final EmailService email;
    private final AuditService audit;

    public MailSettingsController(MailSettingsService settings, EmailService email,
                                  AuditService audit) {
        this.settings = settings;
        this.email = email;
        this.audit = audit;
    }

    /** Corps de mise a jour. {@code password} : null/absent = inchange, "" = efface. */
    record UpdateRequest(
            String host, Integer port, String username, String password,
            String fromAddress, String fromName,
            Boolean auth, Boolean starttls, Boolean enabled,
            String logoUrl, String signature) {
    }

    record TestRequest(String to) {
    }

    /** {@code GET /admin/mail-settings} — parametres courants (sans mot de passe). */
    @GetMapping
    public Map<String, Object> get(AuthUser user) {
        ensureAdmin(user);
        return settings.publicView();
    }

    /** {@code PUT /admin/mail-settings} — enregistre les parametres. */
    @PutMapping
    public Map<String, Object> update(AuthUser user, ClientIp ip, @RequestBody UpdateRequest req) {
        ensureAdmin(user);

        int port = req.port() == null ? 587 : req.port();
        ApiException.ensure(port > 0 && port <= 65535, "port SMTP invalide");
        boolean enabled = Boolean.TRUE.equals(req.enabled());
        if (enabled) {
            ApiException.ensure(req.host() != null && !req.host().isBlank(),
                    "Hote SMTP obligatoire lorsque la messagerie est activee.");
            ApiException.ensure(req.fromAddress() != null && req.fromAddress().contains("@"),
                    "Adresse expediteur invalide.");
        }

        settings.update(
                req.host(), port, req.username(), req.password(),
                req.fromAddress(), req.fromName(),
                req.auth() == null || req.auth(),
                req.starttls() == null || req.starttls(),
                enabled, req.logoUrl(), req.signature(), user.id());

        audit.log(user.id().toString(), "CONFIG_MESSAGERIE", AuditService.SUCCES,
                (req.host() == null ? "-" : req.host()) + ":" + port, ip.value());
        return settings.publicView();
    }

    /** {@code POST /admin/mail-settings/test} — envoi d'un e-mail de test. */
    @PostMapping("/test")
    public Map<String, Object> test(AuthUser user, ClientIp ip, @RequestBody TestRequest req) {
        ensureAdmin(user);
        ApiException.ensure(req.to() != null && req.to().contains("@"),
                "Adresse de test invalide.");

        EmailService.Status status = email.sendOne(req.to().trim(),
                "Test de configuration — Plateforme Valeurs du Tresor CEMAC",
                "<p>Ceci est un e-mail de test envoye depuis l'espace d'administration "
                        + "Afriland First Bank.</p><p>Si vous recevez ce message, la "
                        + "configuration de la messagerie est operationnelle.</p>");

        audit.log(user.id().toString(), "TEST_MESSAGERIE",
                status == EmailService.Status.FAILED ? AuditService.ECHEC : AuditService.SUCCES,
                req.to().trim(), ip.value());
        return Map.of("status", status.name());
    }

    private static void ensureAdmin(AuthUser user) {
        if (!"ADMIN".equals(user.role())) {
            throw ApiException.forbidden(
                    "Seul un administrateur peut configurer la messagerie.");
        }
    }
}
