package cm.afriland.titres.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Generation de secrets opaques : jetons de rafraichissement et codes OTP.
 *
 * On ne stocke jamais la valeur en clair en base : seule son empreinte SHA-256
 * est persistee.
 */
public final class Tokens {

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Tokens() {
    }

    /** Jeton de rafraichissement aleatoire (256 bits, encode en hexadecimal). */
    public static String generateRefreshToken() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return hex(bytes);
    }

    /** Code OTP numerique a 6 chiffres pour la MFA. */
    public static String generateOtp() {
        return generateOtp(6);
    }

    /** Code OTP numerique de {@code longueur} chiffres (4 a 8). */
    public static String generateOtp(int longueur) {
        int n = Math.clamp(longueur, 4, 8);
        int bound = (int) Math.pow(10, n);
        String tirage = Integer.toString(RNG.nextInt(bound));
        // Rembourrage a gauche : le tirage peut comporter moins de n chiffres.
        return "0".repeat(n - tirage.length()) + tirage;
    }

    /**
     * Mot de passe initial robuste, lisible (sans caracteres ambigus), comportant
     * au moins une majuscule, une minuscule, un chiffre et un symbole. Genere a la
     * creation d'un compte puis transmis au client par e-mail ou SMS.
     */
    public static String generatePassword() {
        final String maj = "ABCDEFGHJKMNPQRSTUVWXYZ";   // sans I, L, O
        final String min = "abcdefghijkmnpqrstuvwxyz";   // sans l, o
        final String num = "23456789";                   // sans 0, 1
        final String sym = "@#%&*!?";
        final String all = maj + min + num + sym;
        StringBuilder sb = new StringBuilder();
        sb.append(maj.charAt(RNG.nextInt(maj.length())));
        sb.append(min.charAt(RNG.nextInt(min.length())));
        sb.append(num.charAt(RNG.nextInt(num.length())));
        sb.append(sym.charAt(RNG.nextInt(sym.length())));
        for (int i = 0; i < 8; i++) {
            sb.append(all.charAt(RNG.nextInt(all.length())));
        }
        // Melange Fisher-Yates pour ne pas exposer le motif des 4 premiers tirages.
        char[] c = sb.toString().toCharArray();
        for (int i = c.length - 1; i > 0; i--) {
            int j = RNG.nextInt(i + 1);
            char t = c[i]; c[i] = c[j]; c[j] = t;
        }
        return new String(c);
    }

    /** Empreinte SHA-256 (hexadecimal) — pour stocker jetons et OTP. */
    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
