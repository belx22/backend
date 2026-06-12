package cm.afriland.titres.web;

import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.notif.OtpSettingsService;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;

/**
 * Espace d'administration — parametres du « serveur OTP ».
 *
 * <p>Reserve a l'administrateur. Controle le canal de diffusion de l'OTP
 * (e-mail / SMS), sa longueur, sa duree de validite et le nombre de tentatives.
 * La cle d'API SMS est ecrite uniquement (jamais renvoyee) ; un drapeau
 * {@code smsApiKeySet} indique si elle est definie.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/otp-settings")
public class OtpSettingsController {

    private static final Set<String> CANAUX = Set.of("EMAIL", "SMS", "EMAIL_SMS", "LOG");

    private final OtpSettingsService settings;
    private final AuditService audit;

    public OtpSettingsController(OtpSettingsService settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    /** Corps de mise a jour. {@code smsApiKey} : null/absent = inchange, "" = efface. */
    record UpdateRequest(
            String canal, Integer longueur, Integer ttlSecondes, Integer maxTentatives,
            String smsApiUrl, String smsApiKey, String smsExpediteur) {
    }

    /** {@code GET /admin/otp-settings} — parametres courants (sans cle SMS). */
    @GetMapping
    public Map<String, Object> get(AuthUser user) {
        ensureAdmin(user);
        return settings.publicView();
    }

    /** {@code PUT /admin/otp-settings} — enregistre les parametres. */
    @PutMapping
    public Map<String, Object> update(AuthUser user, ClientIp ip, @RequestBody UpdateRequest req) {
        ensureAdmin(user);

        String canal = req.canal() == null ? "EMAIL" : req.canal().trim().toUpperCase();
        ApiException.ensure(CANAUX.contains(canal), "canal OTP invalide (EMAIL, SMS, EMAIL_SMS ou LOG)");
        int longueur = req.longueur() == null ? 6 : req.longueur();
        ApiException.ensure(longueur >= 4 && longueur <= 8, "longueur OTP invalide (4 a 8)");
        int ttl = req.ttlSecondes() == null ? 300 : req.ttlSecondes();
        ApiException.ensure(ttl >= 60 && ttl <= 1800, "durée de validité invalide (60 à 1800 s)");
        int maxTent = req.maxTentatives() == null ? 5 : req.maxTentatives();
        ApiException.ensure(maxTent >= 1 && maxTent <= 10, "nombre de tentatives invalide (1 à 10)");
        if (canal.contains("SMS")) {
            ApiException.ensure(req.smsApiUrl() != null && !req.smsApiUrl().isBlank(),
                    "URL de la passerelle SMS obligatoire lorsque le canal SMS est actif.");
        }

        settings.update(canal, longueur, ttl, maxTent, req.smsApiUrl(), req.smsApiKey(),
                req.smsExpediteur(), user.id());

        audit.log(user.id().toString(), "CONFIG_OTP", AuditService.SUCCES, canal, ip.value());
        return settings.publicView();
    }

    private static void ensureAdmin(AuthUser user) {
        if (!"ADMIN".equals(user.role())) {
            throw ApiException.forbidden("Seul un administrateur peut configurer le serveur OTP.");
        }
    }
}
