package cm.afriland.titres.security;

import java.util.UUID;

import cm.afriland.titres.error.ApiException;

/**
 * Utilisateur authentifie, derive d'un jeton d'acces JWT verifie.
 */
public record AuthUser(UUID id, String email, String role, boolean mustChangePassword) {

    /** Echoue avec 403 si l'utilisateur ne detient pas la permission requise. */
    public void require(Permission perm) {
        if (!Rbac.roleHasPermission(role, perm)) {
            throw ApiException.forbidden("Permission insuffisante pour cette operation.");
        }
    }

    public boolean isClient() {
        return Rbac.isClient(role);
    }

    public boolean isStaff() {
        return Rbac.isStaff(role);
    }
}
