package cm.afriland.titres.web;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;
import cm.afriland.titres.security.Permission;
import cm.afriland.titres.security.Rbac;

/**
 * Module 10 — Matrice RBAC role -> permissions (CSFT §M6-S03).
 *
 * Consultation pour tout utilisateur authentifie ; modification reservee a
 * l'administrateur. Chaque modification est persistee puis rechargee en memoire.
 */
@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionController {

    private final JdbcTemplate jdbc;
    private final Rbac rbac;
    private final AuditService audit;

    public PermissionController(JdbcTemplate jdbc, Rbac rbac, AuditService audit) {
        this.jdbc = jdbc;
        this.rbac = rbac;
        this.audit = audit;
    }

    record ToggleRequest(String role, String permission, boolean granted) {
    }

    /** {@code GET /permissions} — matrice role -> permissions (tout utilisateur authentifie). */
    @GetMapping
    public Map<String, List<String>> getPermissions(AuthUser user) {
        return Rbac.matrixSnapshot();
    }

    /** {@code PUT /permissions} — octroie ou retire une permission a un role (ADMIN uniquement). */
    @PutMapping
    public Map<String, List<String>> update(AuthUser user, ClientIp ip,
                                            @RequestBody ToggleRequest req) {
        if (!"ADMIN".equals(user.role())) {
            throw ApiException.forbidden(
                    "Seul un administrateur peut modifier la matrice des droits RBAC.");
        }
        ApiException.ensure(Rbac.ROLES.contains(req.role()), "rôle invalide");
        Permission perm = Permission.fromName(req.permission());
        if (perm == null) {
            throw ApiException.badRequest("permission invalide");
        }

        if (req.granted()) {
            jdbc.update("INSERT INTO role_permissions (role, permission) VALUES (?, ?) "
                    + "ON CONFLICT (role, permission) DO NOTHING", req.role(), perm.name());
        } else {
            jdbc.update("DELETE FROM role_permissions WHERE role = ? AND permission = ?",
                    req.role(), perm.name());
        }

        // Rechargement immediat en memoire — la nouvelle matrice s'applique aussitot.
        rbac.loadMatrix();

        audit.log(user.id().toString(),
                req.granted() ? "OCTROI_PERMISSION" : "RETRAIT_PERMISSION",
                AuditService.SUCCES, req.role() + ":" + perm.name(), ip.value());

        return Rbac.matrixSnapshot();
    }
}
