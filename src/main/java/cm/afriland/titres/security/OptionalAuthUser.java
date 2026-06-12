package cm.afriland.titres.security;

import java.util.Optional;

/**
 * Authentification facultative : porte un {@link AuthUser} si un jeton valide
 * etait present, sinon vide. Si un jeton est present mais invalide, la
 * resolution echoue (401) — comme pour {@link AuthUser}.
 */
public record OptionalAuthUser(AuthUser value) {

    public boolean isPresent() {
        return value != null;
    }

    public Optional<AuthUser> get() {
        return Optional.ofNullable(value);
    }
}
