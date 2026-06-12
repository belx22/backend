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
import cm.afriland.titres.integration.LdapSettingsService;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;

/**
 * Espace d'administration — parametres de connexion LDAP / Active Directory.
 *
 * <p>Reserve a l'administrateur. Permet de configurer la liaison a l'annuaire
 * d'entreprise (AD) sans toucher au code ni aux variables d'environnement. Le mot
 * de passe du compte de service n'est jamais renvoye (write-only) ; un drapeau
 * {@code bindPasswordSet} indique s'il est defini. Chaque modification et chaque
 * test de connexion sont audites.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/ldap-settings")
public class LdapSettingsController {

    private final LdapSettingsService settings;
    private final AuditService audit;

    public LdapSettingsController(LdapSettingsService settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    /** Corps de mise a jour. {@code bindPassword} : null/absent = inchange, "" = efface. */
    record UpdateRequest(
            Boolean enabled, String host, Integer port, Boolean ssl, Boolean startTls,
            String baseDn, String bindDn, String bindPassword,
            String userSearchBase, String userSearchFilter) {
    }

    /** {@code GET /admin/ldap-settings} — parametres courants (sans mot de passe). */
    @GetMapping
    public Map<String, Object> get(AuthUser user) {
        ensureAdmin(user);
        return settings.publicView();
    }

    /** {@code PUT /admin/ldap-settings} — enregistre les parametres. */
    @PutMapping
    public Map<String, Object> update(AuthUser user, ClientIp ip, @RequestBody UpdateRequest req) {
        ensureAdmin(user);

        int port = req.port() == null ? 389 : req.port();
        ApiException.ensure(port > 0 && port <= 65535, "port LDAP invalide");
        boolean enabled = Boolean.TRUE.equals(req.enabled());
        boolean ssl = Boolean.TRUE.equals(req.ssl());
        boolean startTls = Boolean.TRUE.equals(req.startTls());
        if (enabled) {
            ApiException.ensure(req.host() != null && !req.host().isBlank(),
                    "Hote LDAP obligatoire lorsque LDAP est active.");
            ApiException.ensure(req.baseDn() != null && !req.baseDn().isBlank(),
                    "Base DN obligatoire lorsque LDAP est active.");
        }
        ApiException.ensure(!(ssl && startTls),
                "SSL (LDAPS) et STARTTLS sont mutuellement exclusifs.");

        settings.update(enabled, req.host(), port, ssl, startTls,
                req.baseDn(), req.bindDn(), req.bindPassword(),
                req.userSearchBase(), req.userSearchFilter(), user.id());

        audit.log(user.id().toString(), "CONFIG_LDAP", AuditService.SUCCES,
                (req.host() == null ? "-" : req.host()) + ":" + port, ip.value());
        return settings.publicView();
    }

    /** {@code POST /admin/ldap-settings/test} — teste la liaison a l'annuaire. */
    @PostMapping("/test")
    public Map<String, Object> test(AuthUser user, ClientIp ip) {
        ensureAdmin(user);
        LdapSettingsService.TestResult r = settings.testConnection();
        audit.log(user.id().toString(), "TEST_LDAP",
                r.ok() ? AuditService.SUCCES : AuditService.ECHEC,
                settings.get().host() == null ? "-" : settings.get().host(), ip.value());
        return Map.of("ok", r.ok(), "message", r.message());
    }

    private static void ensureAdmin(AuthUser user) {
        if (!"ADMIN".equals(user.role())) {
            throw ApiException.forbidden("Seul un administrateur peut configurer LDAP / Active Directory.");
        }
    }
}
