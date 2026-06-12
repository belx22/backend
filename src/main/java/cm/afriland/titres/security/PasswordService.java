package cm.afriland.titres.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hachage et verification des mots de passe avec Argon2id.
 *
 * Argon2id est la fonction recommandee par l'OWASP : resistante au GPU et aux
 * attaques par canal auxiliaire. Le sel est genere aleatoirement par mot de
 * passe et stocke dans la chaine PHC retournee.
 */
@Component
public class PasswordService {

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    /** Calcule l'empreinte Argon2id d'un mot de passe en clair. */
    public String hash(String plain) {
        return encoder.encode(plain);
    }

    /**
     * Verifie un mot de passe en clair contre une empreinte stockee. Renvoie
     * {@code false} (sans exception) si l'empreinte est invalide ou ne correspond pas.
     */
    public boolean verify(String plain, String storedHash) {
        try {
            return encoder.matches(plain, storedHash);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
