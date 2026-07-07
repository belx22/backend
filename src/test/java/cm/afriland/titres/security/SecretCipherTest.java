package cm.afriland.titres.security;

import cm.afriland.titres.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretCipherTest {

    private SecretCipher cipher;

    @BeforeEach
    void setup() {
        AppProperties props = new AppProperties();
        props.setJwtSecret("test-secret-key-at-least-32-chars-long!");
        cipher = new SecretCipher(props);
    }

    @Test
    void encrypt_decrypt_roundtrip() {
        String plain = "smtp-secret-password";
        assertEquals(plain, cipher.decrypt(cipher.encrypt(plain)));
    }

    @Test
    void encrypt_null_retourne_null() {
        assertNull(cipher.encrypt(null));
    }

    @Test
    void encrypt_vide_retourne_null() {
        assertNull(cipher.encrypt(""));
    }

    @Test
    void decrypt_null_retourne_null() {
        assertNull(cipher.decrypt(null));
    }

    @Test
    void decrypt_vide_retourne_null() {
        assertNull(cipher.decrypt(""));
    }

    @Test
    void deux_chiffrements_du_meme_texte_sont_differents() {
        String plain = "meme-secret";
        assertNotEquals(cipher.encrypt(plain), cipher.encrypt(plain),
                "L'IV aléatoire doit produire deux chiffrés différents");
    }

    @Test
    void encrypt_texte_unicode() {
        String plain = "Mòt-dé-påssé-123!";
        assertEquals(plain, cipher.decrypt(cipher.encrypt(plain)));
    }

    @Test
    void encrypt_texte_long() {
        String plain = "a".repeat(10_000);
        assertEquals(plain, cipher.decrypt(cipher.encrypt(plain)));
    }

    @Test
    void decrypt_texte_altere_leve_exception() {
        String encrypted = cipher.encrypt("smtp-secret");
        // Altère les derniers octets (tag GCM) → authentification invalide
        String tampered = encrypted.substring(0, Math.max(1, encrypted.length() - 4)) + "ZZZZ";
        assertThrows(Exception.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void decrypt_texte_base64_invalide_leve_exception() {
        assertThrows(Exception.class, () -> cipher.decrypt("!!!pas-du-base64!!!"));
    }
}
