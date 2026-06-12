package cm.afriland.titres.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import cm.afriland.titres.config.AppProperties;

/**
 * Chiffrement symetrique des secrets stockes en base (ex. mot de passe SMTP).
 *
 * <p>AES-256 GCM, cle derivee par SHA-256 du secret applicatif
 * ({@code app.jwt-secret}). Le format de sortie est
 * {@code base64(iv(12o) || ciphertext+tag)}. Authentifie (GCM) : toute
 * alteration du chiffre est detectee au dechiffrement.</p>
 *
 * <p>Note : la cle vit avec l'application. En production, preferer un KMS /
 * coffre de secrets dedie pour la rotation et l'isolation.</p>
 */
@Component
public class SecretCipher {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;       // 96 bits recommande pour GCM
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(AppProperties props) {
        this.key = deriveKey(props.getJwtSecret());
    }

    private static SecretKeySpec deriveKey(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Derivation de cle impossible", e);
        }
    }

    /** Chiffre une chaine claire. {@code null}/vide -> {@code null}. */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Chiffrement impossible", e);
        }
    }

    /** Dechiffre une chaine produite par {@link #encrypt}. {@code null} -> {@code null}. */
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Dechiffrement impossible", e);
        }
    }
}
