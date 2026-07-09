package cm.afriland.titres.security;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokensTest {

    // ─── generateRefreshToken ─────────────────────────────────────────────────

    @Test
    void generateRefreshToken_est_64_chars_hexadecimaux() {
        String t = Tokens.generateRefreshToken();
        assertEquals(64, t.length());
        assertTrue(t.matches("[0-9a-f]+"), "doit etre en hexadecimal minuscule");
    }

    @Test
    void generateRefreshToken_deux_appels_distincts() {
        assertNotEquals(Tokens.generateRefreshToken(), Tokens.generateRefreshToken());
    }

    // ─── generateOtp ──────────────────────────────────────────────────────────

    @Test
    void generateOtp_defaut_6_chiffres() {
        String otp = Tokens.generateOtp();
        assertEquals(6, otp.length());
        assertTrue(otp.matches("\\d{6}"), "doit etre compose de chiffres uniquement");
    }

    @Test
    void generateOtp_longueur_4() {
        String otp = Tokens.generateOtp(4);
        assertEquals(4, otp.length());
        assertTrue(otp.matches("\\d{4}"));
    }

    @Test
    void generateOtp_longueur_8() {
        String otp = Tokens.generateOtp(8);
        assertEquals(8, otp.length());
        assertTrue(otp.matches("\\d{8}"));
    }

    @Test
    void generateOtp_longueur_trop_petite_clampee_a_4() {
        assertEquals(4, Tokens.generateOtp(2).length());
        assertEquals(4, Tokens.generateOtp(0).length());
    }

    @Test
    void generateOtp_longueur_trop_grande_clampee_a_8() {
        assertEquals(8, Tokens.generateOtp(9).length());
        assertEquals(8, Tokens.generateOtp(100).length());
    }

    @RepeatedTest(5)
    void generateOtp_valeur_dans_la_plage_6_chiffres() {
        int v = Integer.parseInt(Tokens.generateOtp(6));
        assertTrue(v >= 0 && v < 1_000_000);
    }

    // ─── generatePassword ─────────────────────────────────────────────────────

    @Test
    void generatePassword_longueur_12() {
        assertEquals(12, Tokens.generatePassword().length());
    }

    @RepeatedTest(20)
    void generatePassword_contient_maj_min_chiffre_symbole() {
        String pwd = Tokens.generatePassword();
        assertTrue(pwd.chars().anyMatch(Character::isUpperCase),
                "doit contenir une majuscule : " + pwd);
        assertTrue(pwd.chars().anyMatch(Character::isLowerCase),
                "doit contenir une minuscule : " + pwd);
        assertTrue(pwd.chars().anyMatch(Character::isDigit),
                "doit contenir un chiffre : " + pwd);
        assertTrue(pwd.chars().anyMatch(c -> "@#%&*!?".indexOf(c) >= 0),
                "doit contenir un symbole : " + pwd);
    }

    @Test
    void generatePassword_deux_appels_distincts() {
        assertNotEquals(Tokens.generatePassword(), Tokens.generatePassword());
    }

    // ─── sha256Hex ────────────────────────────────────────────────────────────

    @Test
    void sha256Hex_chaine_vide_valeur_connue() {
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Tokens.sha256Hex(""));
    }

    @Test
    void sha256Hex_hello_valeur_connue() {
        assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                Tokens.sha256Hex("hello"));
    }

    @Test
    void sha256Hex_deterministe() {
        String h1 = Tokens.sha256Hex("afriland");
        String h2 = Tokens.sha256Hex("afriland");
        assertEquals(h1, h2);
    }

    @Test
    void sha256Hex_deux_entrees_differentes_produisent_hashes_differents() {
        assertNotEquals(Tokens.sha256Hex("a"), Tokens.sha256Hex("b"));
    }

    @Test
    void sha256Hex_resultat_est_64_chars_hexadecimaux() {
        String h = Tokens.sha256Hex("test");
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]+"));
    }

    @Test
    void sha256Hex_unicode() {
        String h = Tokens.sha256Hex("café");
        assertNotNull(h);
        assertEquals(64, h.length());
    }
}
