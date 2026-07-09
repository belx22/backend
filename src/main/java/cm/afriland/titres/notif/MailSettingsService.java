package cm.afriland.titres.notif;

import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import cm.afriland.titres.security.SecretCipher;
import jakarta.annotation.PostConstruct;

/**
 * Parametres de messagerie (SMTP) persistes en base et configurables par
 * l'administrateur. Le mot de passe est chiffre au repos (AES-GCM) et n'est
 * jamais expose par l'API.
 *
 * <p>Le sender JavaMail est construit dynamiquement a partir de ces parametres,
 * de sorte qu'une modification via l'espace d'administration prend effet sans
 * redemarrage.</p>
 */
@Service
public class MailSettingsService {

    private static final Logger log = LoggerFactory.getLogger(MailSettingsService.class);

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final cm.afriland.titres.config.AppProperties props;

    /** Cache des parametres courants (mot de passe en clair, jamais serialise). */
    private final java.util.concurrent.atomic.AtomicReference<MailSettings> cache =
            new java.util.concurrent.atomic.AtomicReference<>();

    public MailSettingsService(JdbcTemplate jdbc, SecretCipher cipher,
                               cm.afriland.titres.config.AppProperties props) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.props = props;
    }

    /** Vue des parametres ; {@code password} est en clair (usage interne uniquement). */
    public record MailSettings(
            String host, int port, String username, String password,
            String fromAddress, String fromName,
            boolean auth, boolean starttls, boolean enabled,
            String logoUrl, String signature) {

        public boolean usable() {
            // Avec authentification, un mot de passe dechiffrable est requis : sans
            // lui, l'envoi echouerait. On retombe alors proprement en simulation /
            // code OTP de secours plutot que de bloquer les connexions.
            return enabled && host != null && !host.isBlank()
                    && (!auth || (password != null && !password.isBlank()));
        }
    }

    @PostConstruct
    public void reload() {
        try {
            cache.set(jdbc.queryForObject(
                    "SELECT host, port, username, password_enc, from_address, from_name, "
                            + "auth, starttls, enabled, logo_url, signature FROM mail_settings WHERE id = TRUE",
                    (rs, n) -> new MailSettings(
                            rs.getString("host"),
                            rs.getInt("port"),
                            rs.getString("username"),
                            // Le dechiffrement ne doit PAS faire echouer le chargement de
                            // toute la config : si le secret (APP_JWT_SECRET) a change, le
                            // mot de passe devient illisible -> on garde le reste visible et
                            // on invite a le re-saisir, au lieu de tout desactiver en silence.
                            decryptPasswordSafely(rs.getString("password_enc")),
                            rs.getString("from_address"),
                            rs.getString("from_name"),
                            rs.getBoolean("auth"),
                            rs.getBoolean("starttls"),
                            rs.getBoolean("enabled"),
                            rs.getString("logo_url"),
                            rs.getString("signature"))));
        } catch (RuntimeException e) {
            log.warn("Parametres de messagerie indisponibles : {}", e.getMessage());
            cache.set(new MailSettings(null, 587, null, null,
                    "no-reply@afriland.cm", "Afriland First Bank - DFT", true, true, false,
                    null, null));
        }
    }

    /** Dechiffre le mot de passe SMTP ; renvoie {@code null} si illisible (secret change). */
    private String decryptPasswordSafely(String passwordEnc) {
        if (passwordEnc == null || passwordEnc.isBlank()) return null;
        try {
            return cipher.decrypt(passwordEnc);
        } catch (RuntimeException e) {
            log.warn("Mot de passe SMTP indechiffrable (APP_JWT_SECRET a-t-il change ?). "
                    + "Re-saisissez-le dans Administration > Messagerie pour reactiver l'envoi.");
            return null;
        }
    }

    public MailSettings get() {
        MailSettings env = envOverride();
        if (env != null) return env;
        if (cache.get() == null) reload();
        return cache.get();
    }

    /** Vrai si la config SMTP provient de l'environnement (prioritaire sur la base). */
    public boolean isEnvManaged() {
        return props.hasSmtpEnvConfig();
    }

    /**
     * Construit les parametres SMTP a partir de l'environnement si {@code SMTP_HOST}
     * est renseigne (sinon {@code null}). La connexion (hote/port/identifiants) vient
     * de l'env ; l'habillage (logo/signature) reste pilote par la base.
     */
    private MailSettings envOverride() {
        if (!props.hasSmtpEnvConfig()) return null;
        String user = props.getSmtpUsername();
        String pass = props.getSmtpPassword();
        // Expediteur : adresse dediee si fournie, sinon le compte authentifie
        // (exige par Office365 et la plupart des fournisseurs).
        String from = props.getMailFrom();
        if (from == null || from.isBlank() || "no-reply@afriland.cm".equals(from)) {
            from = (user != null && !user.isBlank()) ? user : from;
        }
        // Habillage (logo/signature) toujours lu depuis la base.
        if (cache.get() == null) reload();
        String logoUrl = cache.get() != null ? cache.get().logoUrl() : null;
        String signature = cache.get() != null ? cache.get().signature() : null;
        return new MailSettings(
                props.getSmtpHost(), props.getSmtpPort(), trimToNull(user),
                (pass == null || pass.isBlank()) ? null : pass,
                from, props.getMailFromName(),
                props.isSmtpAuth(), props.isSmtpStarttls(), true,
                logoUrl, signature);
    }

    public boolean usable() {
        return get().usable();
    }

    /**
     * Met a jour les parametres. Si {@code newPassword} est {@code null}, le mot
     * de passe existant est conserve ; s'il est vide explicitement, il est efface.
     */
    public void update(String host, int port, String username, String newPassword,
                       String fromAddress, String fromName,
                       boolean auth, boolean starttls, boolean enabled,
                       String logoUrl, String signature, UUID adminId) {
        // Conserver l'ancien secret si aucun nouveau n'est fourni (null).
        String passwordEnc;
        if (newPassword == null) {
            passwordEnc = jdbc.query("SELECT password_enc FROM mail_settings WHERE id = TRUE",
                    rs -> rs.next() ? rs.getString("password_enc") : null);
        } else {
            passwordEnc = cipher.encrypt(newPassword); // vide -> null (efface)
        }

        jdbc.update(
                "UPDATE mail_settings SET host = ?, port = ?, username = ?, password_enc = ?, "
                        + "from_address = ?, from_name = ?, auth = ?, starttls = ?, enabled = ?, "
                        + "logo_url = ?, signature = ?, updated_at = now(), updated_by = ? WHERE id = TRUE",
                trimToNull(host), port, trimToNull(username), passwordEnc,
                trimToNull(fromAddress), trimToNull(fromName), auth, starttls, enabled,
                trimToNull(logoUrl), trimToNull(signature), adminId);
        reload();
    }

    /** Construit un sender JavaMail a partir des parametres courants. */
    public JavaMailSenderImpl buildSender() {
        MailSettings s = get();
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(s.host());
        sender.setPort(s.port());
        if (s.username() != null) sender.setUsername(s.username());
        if (s.password() != null) sender.setPassword(s.password());
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(s.auth()));
        props.put("mail.smtp.starttls.enable", String.valueOf(s.starttls()));
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return sender;
    }

    /** Expose les parametres pour l'API (sans mot de passe). */
    public Map<String, Object> publicView() {
        MailSettings s = get();
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("host", s.host() == null ? "" : s.host());
        view.put("port", s.port());
        view.put("username", s.username() == null ? "" : s.username());
        view.put("fromAddress", s.fromAddress() == null ? "" : s.fromAddress());
        view.put("fromName", s.fromName() == null ? "" : s.fromName());
        view.put("auth", s.auth());
        view.put("starttls", s.starttls());
        view.put("enabled", s.enabled());
        view.put("logoUrl", s.logoUrl() == null ? "" : s.logoUrl());
        view.put("signature", s.signature() == null ? "" : s.signature());
        view.put("passwordSet", s.password() != null && !s.password().isBlank());
        // Vrai si la connexion SMTP est imposee par l'environnement (UI en lecture seule).
        view.put("envManaged", isEnvManaged());
        return view;
    }

    /** URL du logo affiche dans les e-mails (repli : logo Afriland servi par le frontend). */
    public String logoUrl() {
        String configured = get().logoUrl();
        if (configured != null && !configured.isBlank()) return configured;
        return props.getPrimaryFrontendOrigin() + "/Logo_Afriland.png";
    }

    /**
     * Habille un contenu d'e-mail dans le gabarit commun : en-tete avec le logo
     * Afriland, le contenu, puis la signature/message configurable en pied.
     */
    public String brand(String contentHtml) {
        String logo = logoUrl();
        String signature = get().signature();
        String footer = (signature == null || signature.isBlank()) ? "" :
                "<div style=\"padding:16px 24px;border-top:1px solid #eee;color:#777;"
                        + "font-size:12px;line-height:1.5;\">" + signature + "</div>";
        return "<div style=\"font-family:Arial,Helvetica,sans-serif;max-width:600px;margin:0 auto;"
                + "border:1px solid #eee;border-radius:8px;overflow:hidden;\">"
                + "<div style=\"text-align:center;padding:20px;border-bottom:3px solid #c8102e;\">"
                + "<img src=\"" + logo + "\" alt=\"Afriland First Bank\" style=\"max-height:56px;\"/>"
                + "</div>"
                + "<div style=\"padding:24px;color:#333;font-size:14px;line-height:1.6;\">"
                + contentHtml + "</div>"
                + footer
                + "</div>";
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
