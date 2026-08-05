package cm.afriland.titres.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.config.FaceScanSettingsService;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;

import jakarta.validation.constraints.NotNull;

/**
 * Espace d'administration — interrupteur GLOBAL de la capture faciale.
 *
 * <p>Reserve a l'administrateur. Quand il est desactive, aucune photo n'est plus
 * demandee a l'inscription ni a la soumission/cosignature (la signature et l'OTP
 * restent exiges). Se combine avec le drapeau par compte {@code prioritaire}.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/face-scan-settings")
public class FaceScanSettingsController {

    private final FaceScanSettingsService settings;
    private final AuditService audit;

    public FaceScanSettingsController(FaceScanSettingsService settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    record UpdateRequest(@NotNull Boolean enabled) {
    }

    /** {@code GET /admin/face-scan-settings} — etat courant. */
    @GetMapping
    public Map<String, Object> get(AuthUser user) {
        ensureAdmin(user);
        return settings.view();
    }

    /** {@code PUT /admin/face-scan-settings} — active/desactive la capture faciale. */
    @PutMapping
    public Map<String, Object> update(AuthUser user, ClientIp ip, @RequestBody UpdateRequest req) {
        ensureAdmin(user);
        boolean enabled = req.enabled() != null && req.enabled();
        settings.setEnabled(enabled, user.id());
        audit.log(user.id().toString(), "CONFIG_CAPTURE_FACIALE",
                AuditService.SUCCES, enabled ? "ACTIVEE" : "DESACTIVEE", ip.value());
        return settings.view();
    }

    private static void ensureAdmin(AuthUser user) {
        if (!"ADMIN".equals(user.role())) {
            throw ApiException.forbidden(
                    "Seul un administrateur peut configurer la capture faciale.");
        }
    }
}
