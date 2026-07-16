package cm.afriland.titres.security;

import java.util.UUID;

import cm.afriland.titres.error.ApiException;

/**
 * Utilisateur authentifie, derive d'un jeton d'acces JWT verifie.
 *
 * <p>{@code scope} distingue une session complete ({@link #SCOPE_FULL}, obtenue
 * apres OTP) d'un jeton restreint au parcours d'inscription
 * ({@link #SCOPE_REGISTRATION}). Ce dernier ne vaut PAS session generale : il
 * autorise seulement a completer son propre dossier, jamais les operations
 * sensibles (un compte ne doit jamais etre actif sans avoir passe l'OTP).
 */
public record AuthUser(UUID id, String email, String role, boolean mustChangePassword, String scope) {

    /** Session complete (acces general a l'API), emise apres validation OTP. */
    public static final String SCOPE_FULL = "FULL";
    /** Jeton restreint au parcours d'inscription (televersement du dossier). */
    public static final String SCOPE_REGISTRATION = "REGISTRATION";

    /** Constructeur de compatibilite : session complete par defaut. */
    public AuthUser(UUID id, String email, String role, boolean mustChangePassword) {
        this(id, email, role, mustChangePassword, SCOPE_FULL);
    }

    /** Vrai si le jeton est restreint au parcours d'inscription. */
    public boolean isRegistrationScoped() {
        return SCOPE_REGISTRATION.equals(scope);
    }

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
