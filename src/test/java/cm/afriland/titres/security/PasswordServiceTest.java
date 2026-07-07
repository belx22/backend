package cm.afriland.titres.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordServiceTest {

    private PasswordService passwordService;

    @BeforeEach
    void setup() {
        passwordService = new PasswordService();
    }

    @Test
    void hash_retourne_empreinte_non_nulle() {
        assertNotNull(passwordService.hash("MonMotDePasse123!"));
    }

    @Test
    void hash_different_du_mot_de_passe_en_clair() {
        String plain = "MonMotDePasse123!";
        assertNotEquals(plain, passwordService.hash(plain));
    }

    @Test
    void hash_format_argon2_phc() {
        assertTrue(passwordService.hash("test").startsWith("$argon2"),
                "Le hash doit être au format PHC Argon2");
    }

    @Test
    void deux_hash_du_meme_mot_de_passe_sont_differents() {
        String plain = "MonMotDePasse123!";
        assertNotEquals(passwordService.hash(plain), passwordService.hash(plain),
                "Le sel aléatoire doit produire deux hashes différents");
    }

    @Test
    void verify_correct_retourne_vrai() {
        String plain = "CorrectPassword!";
        assertTrue(passwordService.verify(plain, passwordService.hash(plain)));
    }

    @Test
    void verify_mauvais_mot_de_passe_retourne_faux() {
        assertFalse(passwordService.verify("MauvaisMotDePasse", passwordService.hash("BonMotDePasse")));
    }

    @Test
    void verify_empreinte_invalide_retourne_faux() {
        assertFalse(passwordService.verify("mdp", "empreinte-invalide-pas-argon2"));
    }

    @Test
    void verify_empreinte_vide_retourne_faux() {
        assertFalse(passwordService.verify("mdp", ""));
    }

    @Test
    void verify_sensible_a_la_casse() {
        String hash = passwordService.hash("Password");
        assertFalse(passwordService.verify("password", hash));
        assertFalse(passwordService.verify("PASSWORD", hash));
    }

    @Test
    void verify_sensible_aux_espaces() {
        String hash = passwordService.hash("mdp ");
        assertFalse(passwordService.verify("mdp", hash));
    }
}
