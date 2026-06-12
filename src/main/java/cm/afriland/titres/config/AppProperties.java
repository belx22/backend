package cm.afriland.titres.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration applicative chargee depuis l'environnement (prefixe {@code app}).
 *
 * Securite : le secret JWT est obligatoire et doit faire au moins 32 caracteres.
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Secret de signature des jetons JWT (HS256) — >= 32 caracteres. */
    private String jwtSecret;
    /** Duree de vie du jeton d'acces, en secondes (15 min par defaut). */
    private long accessTokenTtl = 900;
    /** Duree de vie du jeton de rafraichissement, en secondes (30 jours). */
    private long refreshTokenTtl = 2_592_000;
    /** Origine autorisee pour le CORS (frontend Angular). */
    private String frontendOrigin = "http://localhost:4200";
    /**
     * Mode d'authentification a la plateforme :
     *   LOCAL            — mot de passe verifie en base (defaut).
     *   LDAP             — mot de passe verifie via LDAP / Active Directory (strict).
     *   LDAP_THEN_LOCAL  — verification LDAP pour les comptes de l'annuaire, repli
     *                      sur la base pour les comptes absents de l'annuaire (hybride).
     * En LDAP/LDAP_THEN_LOCAL, l'annuaire doit etre active et configure
     * (espace d'administration LDAP) ; sinon on retombe sur LOCAL par securite.
     */
    private String authMode = "LOCAL";
    /** Insere le jeu de donnees de demonstration si la base est vide. */
    private boolean seedOnStart = true;
    /** Code OTP fixe pour la demo. Vide en production (OTP aleatoire). */
    private String mfaDevCode;

    /** URL de base du serveur Amplitude / AIF (proxy interne uniquement). */
    private String aifBaseUrl = "https://172.21.88.95:8095/amplitude/accounts/v1";
    /** Login HTTP basic ou X-SBS-login transmis a AIF (jamais expose au front). */
    private String aifLogin;
    /** Mot de passe AIF (jamais expose au front). */
    private String aifPassword;
    /** Si vrai, on accepte les certificats TLS auto-signes du serveur AIF. */
    private boolean aifTrustAllCerts = true;

    /** Adresse expediteur pour les e-mails sortants (diffusion catalogue). */
    private String mailFrom = "no-reply@afriland.cm";
    /** Nom affiche de l'expediteur. */
    private String mailFromName = "Afriland First Bank - DFT";

    /**
     * Parametres SMTP fournis par l'environnement (SMTP_HOST, SMTP_PORT, …).
     * S'ils sont renseignes (host non vide), ils PRIORENT sur la configuration
     * stockee en base : la messagerie fonctionne des le deploiement, sans passer
     * par l'UI, et le mot de passe (en clair dans l'env) reste insensible a une
     * rotation de {@code APP_JWT_SECRET}.
     */
    private String smtpHost;
    private int smtpPort = 587;
    private String smtpUsername;
    private String smtpPassword;
    private boolean smtpAuth = true;
    private boolean smtpStarttls = true;

    public void validate() {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException("APP_JWT_SECRET doit comporter au moins 32 caracteres");
        }
    }

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }

    public long getAccessTokenTtl() { return accessTokenTtl; }
    public void setAccessTokenTtl(long accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }

    public long getRefreshTokenTtl() { return refreshTokenTtl; }
    public void setRefreshTokenTtl(long refreshTokenTtl) { this.refreshTokenTtl = refreshTokenTtl; }

    public String getFrontendOrigin() { return frontendOrigin; }
    public void setFrontendOrigin(String frontendOrigin) { this.frontendOrigin = frontendOrigin; }

    /**
     * Origine PRINCIPALE du frontend, pour construire des liens absolus (e-mails
     * de reinitialisation, QR de connexion, logo). {@code frontendOrigin} peut
     * contenir plusieurs origines separees par des virgules (CORS) : on retient
     * la premiere, sans slash final.
     */
    public String getPrimaryFrontendOrigin() {
        String raw = frontendOrigin == null ? "" : frontendOrigin.trim();
        String first = raw.split("\\s*,\\s*")[0].trim();
        if (first.isEmpty()) first = "http://localhost:4200";
        return first.endsWith("/") ? first.substring(0, first.length() - 1) : first;
    }

    public String getAuthMode() {
        String m = authMode == null ? "LOCAL" : authMode.trim().toUpperCase();
        return switch (m) {
            case "LDAP", "LDAP_THEN_LOCAL", "LOCAL" -> m;
            default -> "LOCAL";
        };
    }
    public void setAuthMode(String authMode) { this.authMode = authMode; }

    public boolean isSeedOnStart() { return seedOnStart; }
    public void setSeedOnStart(boolean seedOnStart) { this.seedOnStart = seedOnStart; }

    public String getMfaDevCode() {
        if (mfaDevCode != null && mfaDevCode.isBlank()) return null;
        return mfaDevCode;
    }
    public void setMfaDevCode(String mfaDevCode) { this.mfaDevCode = mfaDevCode; }

    public String getAifBaseUrl() { return aifBaseUrl; }
    public void setAifBaseUrl(String aifBaseUrl) { this.aifBaseUrl = aifBaseUrl; }

    public String getAifLogin() {
        if (aifLogin != null && aifLogin.isBlank()) return null;
        return aifLogin;
    }
    public void setAifLogin(String aifLogin) { this.aifLogin = aifLogin; }

    public String getAifPassword() {
        if (aifPassword != null && aifPassword.isBlank()) return null;
        return aifPassword;
    }
    public void setAifPassword(String aifPassword) { this.aifPassword = aifPassword; }

    public boolean isAifTrustAllCerts() { return aifTrustAllCerts; }
    public void setAifTrustAllCerts(boolean aifTrustAllCerts) {
        this.aifTrustAllCerts = aifTrustAllCerts;
    }

    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String mailFrom) { this.mailFrom = mailFrom; }

    public String getMailFromName() { return mailFromName; }
    public void setMailFromName(String mailFromName) { this.mailFromName = mailFromName; }

    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }

    public int getSmtpPort() { return smtpPort; }
    public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }

    public String getSmtpUsername() { return smtpUsername; }
    public void setSmtpUsername(String smtpUsername) { this.smtpUsername = smtpUsername; }

    public String getSmtpPassword() { return smtpPassword; }
    public void setSmtpPassword(String smtpPassword) { this.smtpPassword = smtpPassword; }

    public boolean isSmtpAuth() { return smtpAuth; }
    public void setSmtpAuth(boolean smtpAuth) { this.smtpAuth = smtpAuth; }

    public boolean isSmtpStarttls() { return smtpStarttls; }
    public void setSmtpStarttls(boolean smtpStarttls) { this.smtpStarttls = smtpStarttls; }

    /** Vrai si un hote SMTP est fourni par l'environnement (override prioritaire). */
    public boolean hasSmtpEnvConfig() {
        return smtpHost != null && !smtpHost.isBlank();
    }
}
