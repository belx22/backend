package cm.afriland.titres.notif;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import cm.afriland.titres.security.SecretCipher;
import jakarta.annotation.PostConstruct;

/**
 * Parametres du « serveur OTP » configurables par l'administrateur (a cote du
 * SMTP). L'OTP est genere aleatoirement, stocke en base sous forme d'empreinte
 * et invalide apres usage (cf. {@code AuthController}). Cet ecran controle le
 * canal de diffusion (e-mail / SMS), la longueur, la duree de validite et le
 * nombre de tentatives.
 *
 * <p>Le canal SMS est <em>prepare</em> : URL et cle d'API (chiffree AES-GCM)
 * peuvent etre renseignees ; tant qu'aucune passerelle n'est branchee, l'envoi
 * SMS est simule.</p>
 */
@Service
public class OtpSettingsService {

    private static final Logger log = LoggerFactory.getLogger(OtpSettingsService.class);

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final java.util.concurrent.atomic.AtomicReference<OtpSettings> cache =
            new java.util.concurrent.atomic.AtomicReference<>();

    public OtpSettingsService(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** {@code smsApiKey} en clair (usage interne uniquement, jamais serialise). */
    public record OtpSettings(
            String canal, int longueur, int ttlSecondes, int maxTentatives,
            String smsApiUrl, String smsApiKey, String smsExpediteur) {

        public boolean emailEnabled() {
            return "EMAIL".equals(canal) || "EMAIL_SMS".equals(canal);
        }

        public boolean smsEnabled() {
            return "SMS".equals(canal) || "EMAIL_SMS".equals(canal);
        }
    }

    @PostConstruct
    public void reload() {
        try {
            cache.set(jdbc.queryForObject(
                    "SELECT canal, longueur, ttl_secondes, max_tentatives, sms_api_url, "
                            + "sms_api_key_enc, sms_expediteur FROM otp_settings WHERE id = TRUE",
                    (rs, n) -> new OtpSettings(
                            rs.getString("canal"),
                            rs.getInt("longueur"),
                            rs.getInt("ttl_secondes"),
                            rs.getInt("max_tentatives"),
                            rs.getString("sms_api_url"),
                            cipher.decrypt(rs.getString("sms_api_key_enc")),
                            rs.getString("sms_expediteur"))));
        } catch (RuntimeException e) {
            log.warn("Parametres OTP indisponibles : {}", e.getMessage());
            cache.set(new OtpSettings("EMAIL", 6, 300, 5, null, null, null));
        }
    }

    public OtpSettings get() {
        if (cache.get() == null) reload();
        return cache.get();
    }

    /** Met a jour les parametres. {@code newApiKey} null => cle conservee. */
    public void update(String canal, int longueur, int ttlSecondes, int maxTentatives,
                       String smsApiUrl, String newApiKey, String smsExpediteur, UUID adminId) {
        String apiKeyEnc;
        if (newApiKey == null) {
            apiKeyEnc = jdbc.query("SELECT sms_api_key_enc FROM otp_settings WHERE id = TRUE",
                    rs -> rs.next() ? rs.getString("sms_api_key_enc") : null);
        } else {
            apiKeyEnc = cipher.encrypt(newApiKey);
        }
        jdbc.update("UPDATE otp_settings SET canal=?, longueur=?, ttl_secondes=?, max_tentatives=?, "
                        + "sms_api_url=?, sms_api_key_enc=?, sms_expediteur=?, updated_at=now(), "
                        + "updated_by=? WHERE id = TRUE",
                canal, longueur, ttlSecondes, maxTentatives, trimToNull(smsApiUrl), apiKeyEnc,
                trimToNull(smsExpediteur), adminId);
        reload();
    }

    /** Vue API (sans la cle d'API SMS). */
    public Map<String, Object> publicView() {
        OtpSettings s = get();
        return Map.of(
                "canal", s.canal(),
                "longueur", s.longueur(),
                "ttlSecondes", s.ttlSecondes(),
                "maxTentatives", s.maxTentatives(),
                "smsApiUrl", s.smsApiUrl() == null ? "" : s.smsApiUrl(),
                "smsExpediteur", s.smsExpediteur() == null ? "" : s.smsExpediteur(),
                "smsApiKeySet", s.smsApiKey() != null && !s.smsApiKey().isBlank());
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
