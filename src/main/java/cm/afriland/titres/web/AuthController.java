package cm.afriland.titres.web;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.config.AppProperties;
import cm.afriland.titres.dto.AuthResponse;
import cm.afriland.titres.dto.UserProfile;
import cm.afriland.titres.dto.UserRow;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.integration.LdapSettingsService;
import cm.afriland.titres.notif.EmailService;
import cm.afriland.titres.notif.OtpSettingsService;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;
import cm.afriland.titres.security.JwtService;
import cm.afriland.titres.security.PasswordService;
import cm.afriland.titres.security.Rbac;
import cm.afriland.titres.security.RateLimiter;
import cm.afriland.titres.security.SecretCipher;
import cm.afriland.titres.security.Tokens;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Module 1 — Authentification & comptes utilisateurs.
 *
 * Chaine de connexion (CSFT §M6-S01) : login -> defi MFA -> verification OTP ->
 * emission des jetons. Mesures : Argon2id, MFA, limitation de debit,
 * verrouillage anti-bruteforce, messages d'erreur generiques.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOCK_DURATION_SECS = 15L * 60;

    /** Litteraux reutilises — evite la duplication (S1192). */
    private static final String SELECT_USER_BY_ID =
            "SELECT " + UserRow.COLUMNS + " FROM users WHERE id = ?";
    private static final String SELECT_USER_BY_EMAIL =
            "SELECT " + UserRow.COLUMNS + " FROM users WHERE email = ?";
    private static final String ACTION_CONNEXION = "CONNEXION";
    private static final String STATUT_ACTIF = "ACTIF";
    private static final String CLE_MESSAGE = "message";
    private static final String COL_USER_ID = "user_id";
    private static final String COL_EXPIRES_AT = "expires_at";

    /** Quota /refresh par IP et par minute — large car protege par cookie HttpOnly. */
    private static final int REFRESH_RATE_LIMIT = 60;
    /** Fenetre de tolerance (s) ou un jeton tout juste rotate reste accepte (rechargement rapide). */
    private static final long REFRESH_GRACE_SECONDS = 30;

    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final JwtService jwt;
    private final PasswordService password;
    private final RateLimiter rateLimiter;
    private final AuditService audit;
    private final OtpSettingsService otpSettings;
    private final EmailService email;
    private final SecretCipher cipher;
    private final LdapSettingsService ldap;

    /** Empreinte « leurre » : verifiee meme quand l'e-mail est inconnu, pour
     *  egaliser la duree de reponse (anti-enumeration de comptes). */
    private final String dummyHash;

    public AuthController(JdbcTemplate jdbc, AppProperties props, JwtService jwt,
                          PasswordService password, RateLimiter rateLimiter, AuditService audit,
                          OtpSettingsService otpSettings, EmailService email, SecretCipher cipher,
                          LdapSettingsService ldap) {
        this.jdbc = jdbc;
        this.props = props;
        this.jwt = jwt;
        this.password = password;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.otpSettings = otpSettings;
        this.email = email;
        this.cipher = cipher;
        this.ldap = ldap;
        this.dummyHash = password.hash("egalisateur-de-temps-pas-un-vrai-mot-de-passe");
    }

    /**
     * Verifie le mot de passe selon le mode d'authentification (APP_AUTH_MODE).
     * LOCAL : base. LDAP : annuaire strict. LDAP_THEN_LOCAL : annuaire pour les
     * comptes presents dans l'annuaire, repli base sinon.
     */
    private boolean verifyPassword(String email, String rawPassword, String passwordHash) {
        String mode = props.getAuthMode();
        boolean ldapActive = !"LOCAL".equals(mode) && ldap.get().enabled();
        if (ldapActive) {
            Boolean r = ldap.authenticate(email, rawPassword);
            if (r != null) return r;                           // present dans l'annuaire -> l'annuaire decide
            // absent de l'annuaire (ou indisponible) : repli local si autorise
            if ("LDAP_THEN_LOCAL".equals(mode)) return password.verify(rawPassword, passwordHash);
            return false;                                      // LDAP strict
        }
        return password.verify(rawPassword, passwordHash);
    }

    // ─────────────────────────── Requetes ───────────────────────────────────

    record LoginRequest(
            @Email(message = "format d'e-mail invalide") String email,
            @Size(min = 8, max = 128, message = "mot de passe de 8 a 128 caracteres") String password) {
    }

    record MfaVerifyRequest(
            @NotNull UUID challengeId,
            // La longueur de l'OTP est configurable par l'admin (4 à 8 chiffres,
            // cf. OtpSettings) : la validation doit couvrir toute la plage, sinon
            // tout code de longueur ≠ 6 est rejeté en 400 avant vérification.
            @Size(min = 4, max = 8, message = "le code OTP comporte de 4 à 8 chiffres") String code) {
    }

    record ChangePasswordRequest(
            @Size(min = 8, max = 128, message = "mot de passe actuel de 8 a 128 caracteres")
            String currentPassword,
            @Size(min = 8, max = 128, message = "nouveau mot de passe de 8 a 128 caracteres")
            String newPassword) {
    }

    record ForgotPasswordRequest(
            @Email(message = "format d'e-mail invalide") @NotNull String email) {
    }

    record ResetPasswordRequest(
            @NotNull String token,
            @Size(min = 8, max = 128, message = "nouveau mot de passe de 8 a 128 caracteres")
            String newPassword) {
    }

    private record MfaChallenge(UUID id, UUID userId, String codeHash,
                                OffsetDateTime expiresAt, int attempts, boolean consumed) {
    }

    private record RefreshTokenRow(UUID id, UUID userId, boolean revoked, OffsetDateTime expiresAt,
                                   OffsetDateTime rotatedAt,
                                   /** Derniere activite reelle : socle de l'expiration par inactivite. */
                                   OffsetDateTime lastUsedAt) {
    }

    // ──────────────────────────── /login ────────────────────────────────────

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req, ClientIp clientIp,
                                     @CookieValue(name = RT_COOKIE, required = false) String rtCookie,
                                     HttpServletResponse resp) {
        String ip = clientIp.value();
        // Quota par endpoint : les echecs de /login ne doivent pas epuiser le
        // quota de /mfa/verify ni de /refresh (cles distinctes par IP).
        if (!rateLimiter.check("login:" + ip)) {
            throw ApiException.tooManyRequests();
        }
        // Nom distinct du champ `email` (EmailService) pour ne pas le masquer.
        String emailNormalise = req.email().trim().toLowerCase();

        UserRow user = jdbc.query(
                        SELECT_USER_BY_EMAIL, UserRow.MAPPER, emailNormalise)
                .stream().findFirst().orElse(null);

        if (user == null) {
            // Compte inexistant : on hache quand meme pour egaliser le temps de reponse.
            password.verify(req.password(), dummyHash);
            audit.log(emailNormalise, ACTION_CONNEXION, AuditService.ECHEC, "—", ip);
            throw invalidCredentials();
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean locked = user.lockedUntil() != null && user.lockedUntil().isAfter(now);
        if (locked || !STATUT_ACTIF.equals(user.statut())) {
            password.verify(req.password(), user.passwordHash());
            audit.log(user.id().toString(), ACTION_CONNEXION, AuditService.ECHEC, "—", ip);
            throw invalidCredentials();
        }

        if (!verifyPassword(emailNormalise, req.password(), user.passwordHash())) {
            int attempts = user.failedLoginAttempts() + 1;
            if (attempts >= MAX_LOGIN_ATTEMPTS) {
                jdbc.update("UPDATE users SET failed_login_attempts = ?, locked_until = ?, "
                                + "updated_at = now() WHERE id = ?",
                        attempts, now.plusSeconds(LOCK_DURATION_SECS), user.id());
            } else {
                jdbc.update("UPDATE users SET failed_login_attempts = ?, updated_at = now() WHERE id = ?",
                        attempts, user.id());
            }
            audit.log(user.id().toString(), ACTION_CONNEXION, AuditService.ECHEC, "—", ip);
            throw invalidCredentials();
        }

        // Mot de passe correct : remise a zero du compteur d'echecs.
        jdbc.update("UPDATE users SET failed_login_attempts = 0, locked_until = NULL, "
                + "updated_at = now() WHERE id = ?", user.id());

        // SECURITE — La session encore ouverte dans CE navigateur est revoquee des
        // maintenant, avant meme que l'OTP ne soit demande.
        //
        // Sans cela, le defi MFA ne protegeait rien : un navigateur conservant un
        // cookie de rafraichissement valide (30 jours) restait rafraichissable. Il
        // suffisait donc de rester sur l'ecran OTP sans rien saisir — le premier
        // rafraichissement venu (re-ouverture de l'application, jeton d'acces
        // expire au bout de 5 min) rouvrait une session SANS que le code n'ait
        // jamais ete valide. Le second facteur etait contournable.
        //
        // On ne revoque que le jeton presente par ce navigateur : les sessions des
        // autres appareils de l'utilisateur n'ont pas a tomber parce qu'il se
        // reconnecte ici.
        revoquerSessionDuNavigateur(rtCookie, user.id(), ip);
        clearRefreshCookie(resp);

        // Defi MFA : code OTP genere aleatoirement selon les parametres « serveur
        // OTP » de l'admin (longueur, TTL), stocke sous forme d'empreinte.
        OtpSettingsService.OtpSettings otpCfg = otpSettings.get();
        // Invalide les defis MFA encore actifs de cet utilisateur : un seul defi
        // valide a la fois (empeche le brute-force parallele sur plusieurs codes).
        jdbc.update("UPDATE mfa_challenges SET consumed = TRUE "
                + "WHERE user_id = ? AND consumed = FALSE", user.id());
        // Le code de demonstration (APP_MFA_DEV_CODE) n'est utilise QUE lorsqu'aucun
        // canal de distribution reel n'est disponible (SMTP non active). Des que la
        // messagerie est configuree et que le canal OTP e-mail est actif, un code
        // aleatoire est genere et reellement envoye par e-mail.
        boolean emailDeliverable = otpCfg.emailEnabled() && email.isConfigured();
        boolean useDevCode = props.getMfaDevCode() != null && !emailDeliverable;
        String otp = useDevCode
                ? props.getMfaDevCode() : Tokens.generateOtp(otpCfg.longueur());
        // code_enc : code chiffre (AES-GCM) conserve pour la supervision back-office
        // (relais du code en cas de non-reception). Jamais stocke en clair ; la
        // verification reste basee sur l'empreinte code_hash.
        UUID challengeId = jdbc.queryForObject(
                "INSERT INTO mfa_challenges (user_id, code_hash, code_enc, expires_at) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                UUID.class, user.id(), Tokens.sha256Hex(otp), cipher.encrypt(otp),
                now.plusSeconds(otpCfg.ttlSecondes()));

        deliverOtp(otpCfg, emailNormalise, otp, useDevCode);

        // Indice de destination (numero masque) pour l'ecran OTP : affiche le vrai
        // numero du client s'il en possede un, masque ; absent sinon (message generique).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mfaRequired", true);
        body.put("challengeId", challengeId);
        body.put("expiresIn", otpCfg.ttlSecondes());
        body.put(CLE_MESSAGE, "Un code de vérification a été envoyé.");
        String phoneHint = maskPhone(user.telephone());
        if (phoneHint != null) {
            body.put("phoneHint", phoneHint);
        }
        return body;
    }

    /**
     * Masque un numero de telephone en ne laissant visibles que les 3 derniers
     * chiffres (ex. {@code +237 6•• •• •• 678}). Espaces et symboles preserves.
     * Renvoie {@code null} si aucun numero n'est enregistre.
     */
    private static String maskPhone(String tel) {
        if (tel == null) {
            return null;
        }
        String t = tel.trim();
        if (t.isEmpty()) {
            return null;
        }
        long totalDigits = t.chars().filter(Character::isDigit).count();
        long revealFrom = Math.max(0, totalDigits - 3);
        StringBuilder sb = new StringBuilder(t.length());
        long digitIdx = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(digitIdx >= revealFrom ? c : '•');
                digitIdx++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Diffuse l'OTP selon le canal configure :
     *   - E-mail : envoye via le SMTP de l'administrateur des qu'il est active et
     *     configure (canal OTP « EMAIL » ou « EMAIL_SMS »).
     *   - SMS : prepare (journalise tant qu'aucune passerelle n'est branchee).
     * Si aucun canal reel n'a pu distribuer le code et que le mode demonstration
     * est actif, le code fixe est journalise pour permettre la connexion en dev ;
     * sinon un avertissement est trace (le code n'est jamais journalise en prod).
     */
    private void deliverOtp(OtpSettingsService.OtpSettings cfg, String destEmail, String otp,
                            boolean usingDevCode) {
        // Envoi e-mail NON bloquant : la requete revient immediatement, le SMTP
        // est contacte en arriere-plan (evite d'attendre la latence du serveur).
        boolean emailQueued = cfg.emailEnabled() && email.isConfigured()
                && destEmail != null && destEmail.contains("@");
        if (emailQueued) {
            email.dispatchOne(destEmail, "Votre code de vérification",
                    "<p>Votre code de vérification est : <b>" + otp + "</b></p>"
                            + "<p>Il expire dans " + (cfg.ttlSecondes() / 60) + " minutes.</p>");
        }
        if (cfg.smsEnabled()) {
            // Passerelle SMS preparee (otp_settings.sms_api_url) — a brancher.
            log.info("SMS OTP (simulé) pour {} : code transmis.", destEmail);
        }
        if (!emailQueued && !cfg.smsEnabled()) {
            if (usingDevCode) {
                // Aucun canal reel : code de demonstration journalise (connexion en dev).
                log.info("Code OTP (dev) pour {} : {}", destEmail, otp);
            } else {
                // Aucun canal actif : on ne journalise JAMAIS l'OTP en clair en prod.
                log.warn("Aucun canal OTP actif pour {} : code non distribué (configurer le serveur OTP).",
                        destEmail);
            }
        }
    }

    // ────────────────────────── /mfa/verify ─────────────────────────────────

    @PostMapping("/mfa/verify")
    public AuthResponse mfaVerify(@Valid @RequestBody MfaVerifyRequest req, ClientIp clientIp,
                                  HttpServletResponse resp) {
        String ip = clientIp.value();
        if (!rateLimiter.check("mfa:" + ip)) {
            throw ApiException.tooManyRequests();
        }

        RowMapper<MfaChallenge> mapper = (rs, n) -> new MfaChallenge(
                rs.getObject("id", UUID.class),
                rs.getObject(COL_USER_ID, UUID.class),
                rs.getString("code_hash"),
                rs.getObject(COL_EXPIRES_AT, OffsetDateTime.class),
                rs.getInt("attempts"),
                rs.getBoolean("consumed"));

        MfaChallenge ch = jdbc.query(
                        "SELECT id, user_id, code_hash, expires_at, attempts, consumed "
                                + "FROM mfa_challenges WHERE id = ?", mapper, req.challengeId())
                .stream().findFirst().orElse(null);

        if (ch == null
                || ch.consumed()
                || ch.expiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))
                || ch.attempts() >= otpSettings.get().maxTentatives()) {
            throw invalidMfa();
        }

        if (!Tokens.sha256Hex(req.code()).equals(ch.codeHash())) {
            jdbc.update("UPDATE mfa_challenges SET attempts = attempts + 1 WHERE id = ?", ch.id());
            audit.log(ch.userId().toString(), "MFA_VERIFICATION", AuditService.ECHEC, "—", ip);
            throw invalidMfa();
        }

        // Defi consomme immediatement — un OTP ne sert qu'une fois.
        jdbc.update("UPDATE mfa_challenges SET consumed = TRUE WHERE id = ?", ch.id());

        UserRow user = jdbc.queryForObject(
                SELECT_USER_BY_ID, UserRow.MAPPER, ch.userId());

        // Re-controle de l'etat du compte entre le login et la verification MFA :
        // un compte suspendu ou verrouille entre-temps ne doit pas obtenir de jetons.
        boolean locked = user.lockedUntil() != null
                && user.lockedUntil().isAfter(OffsetDateTime.now(ZoneOffset.UTC));
        if (locked || !STATUT_ACTIF.equals(user.statut())) {
            audit.log(user.id().toString(), ACTION_CONNEXION, AuditService.ECHEC, "—", ip);
            throw invalidCredentials();
        }

        AuthResponse response = issueTokens(user, resp);
        audit.log(user.id().toString(), ACTION_CONNEXION, AuditService.SUCCES, "—", ip);
        return response;
    }

    // ─────────────────────────── /refresh ───────────────────────────────────

    @PostMapping("/refresh")
    public AuthResponse refresh(@CookieValue(name = RT_COOKIE, required = false) String rtCookie,
                                ClientIp clientIp, HttpServletResponse resp) {
        // Quota genereux : /refresh est protege par le cookie HttpOnly (pas une
        // cible de force brute) et doit tolerer des actualisations repetees.
        if (!rateLimiter.check("refresh:" + clientIp.value(), REFRESH_RATE_LIMIT)) {
            throw ApiException.tooManyRequests();
        }
        if (rtCookie == null || rtCookie.length() < 16) {
            throw sessionExpired();
        }

        String tokenHash = Tokens.sha256Hex(rtCookie);
        RowMapper<RefreshTokenRow> mapper = (rs, n) -> new RefreshTokenRow(
                rs.getObject("id", UUID.class),
                rs.getObject(COL_USER_ID, UUID.class),
                rs.getBoolean("revoked"),
                rs.getObject(COL_EXPIRES_AT, OffsetDateTime.class),
                rs.getObject("rotated_at", OffsetDateTime.class),
                rs.getObject("last_used_at", OffsetDateTime.class));

        RefreshTokenRow row = jdbc.query(
                        "SELECT id, user_id, revoked, expires_at, rotated_at, last_used_at "
                                + "FROM refresh_tokens WHERE token_hash = ?",
                        mapper, tokenHash)
                .stream().findFirst().orElse(null);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (row == null || row.expiresAt().isBefore(now)) {
            throw sessionExpired();
        }

        // ── Expiration par INACTIVITE (controlee ici, pas dans le navigateur) ──
        // Le cookie de rafraichissement vit plusieurs semaines : sans ce controle,
        // revenir sur le site apres des heures d'absence reconnectait
        // automatiquement. Passe la fenetre d'inactivite, la session est morte —
        // et on revoque TOUTE la session, pas seulement ce jeton.
        OffsetDateTime derniereActivite = row.lastUsedAt() != null ? row.lastUsedAt() : now;
        if (derniereActivite.isBefore(now.minusSeconds(props.getSessionIdleTimeout()))) {
            jdbc.update("UPDATE refresh_tokens SET revoked = TRUE, rotated_at = NULL "
                    + "WHERE user_id = ? AND revoked = FALSE", row.userId());
            audit.log(row.userId().toString(), "SESSION_EXPIREE_INACTIVITE",
                    AuditService.SUCCES, "—", clientIp.value());
            throw sessionExpired();
        }

        if (row.revoked()) {
            // Tolerance au rechargement rapide : un jeton revoque PAR ROTATION il y
            // a moins de REFRESH_GRACE_SECONDS est accepte une fois de plus (le
            // cookie successeur a ete perdu par une actualisation/requete avortee).
            // Hors fenetre, ou s'il s'agit d'une revocation de securite
            // (rotated_at NULL : deconnexion, changement de mot de passe...), c'est
            // une vraie expiration/rejeu => deconnexion.
            boolean withinGrace = row.rotatedAt() != null
                    && row.rotatedAt().isAfter(now.minusSeconds(REFRESH_GRACE_SECONDS));
            if (!withinGrace) {
                throw sessionExpired();
            }
            // Dans la fenetre de grace : on n'ecrase pas rotated_at (la fenetre
            // reste ancree sur la rotation initiale) et on reemet simplement.
        } else {
            // Rotation normale : l'ancien jeton est revoque et horodate.
            jdbc.update("UPDATE refresh_tokens SET revoked = TRUE, rotated_at = ? WHERE id = ?",
                    now, row.id());
        }

        UserRow user = jdbc.queryForObject(
                SELECT_USER_BY_ID, UserRow.MAPPER, row.userId());
        if (!STATUT_ACTIF.equals(user.statut())) {
            throw sessionExpired();
        }

        AuthResponse response = issueTokens(user, resp);
        audit.log(user.id().toString(), "RENOUVELLEMENT_JETON", AuditService.SUCCES, "—", clientIp.value());
        return response;
    }

    // ─────────────────────────── /logout ────────────────────────────────────

    @PostMapping("/logout")
    public Map<String, Object> logout(AuthUser user,
                                      @CookieValue(name = RT_COOKIE, required = false) String rtCookie,
                                      ClientIp clientIp, HttpServletResponse resp) {
        if (rtCookie != null && !rtCookie.isBlank()) {
            jdbc.update("UPDATE refresh_tokens SET revoked = TRUE WHERE token_hash = ? AND user_id = ?",
                    Tokens.sha256Hex(rtCookie), user.id());
        }
        clearRefreshCookie(resp);
        audit.log(user.id().toString(), "DECONNEXION", AuditService.SUCCES, "—", clientIp.value());
        return Map.of(CLE_MESSAGE, "Déconnexion effectuée.");
    }

    /**
     * Revoque le jeton de rafraichissement presente par le navigateur qui engage
     * une nouvelle connexion.
     *
     * <p>Sans effet si aucun cookie n'est presente (premiere connexion), ou s'il
     * appartient a un autre utilisateur : on ne revoque que ce que ce navigateur
     * detient reellement pour ce compte.</p>
     */
    private void revoquerSessionDuNavigateur(String rtCookie, UUID userId, String ip) {
        if (rtCookie == null || rtCookie.isBlank()) {
            return;
        }
        int revoques = jdbc.update(
                "UPDATE refresh_tokens SET revoked = TRUE "
                        + "WHERE token_hash = ? AND user_id = ? AND revoked = FALSE",
                Tokens.sha256Hex(rtCookie), userId);
        if (revoques > 0) {
            audit.log(userId.toString(), "SESSION_REVOQUEE_NOUVELLE_CONNEXION",
                    AuditService.SUCCES, "—", ip);
        }
    }

    // ───────────────────────────── /me ──────────────────────────────────────

    @GetMapping("/me")
    public UserProfile me(AuthUser user) {
        UserRow row = jdbc.queryForObject(
                SELECT_USER_BY_ID, UserRow.MAPPER, user.id());
        UserProfile profile = row.toProfile();
        // Les clients ne doivent voir ni leur solde (la source AIF est l'apanage du
        // back-office) ni leur compte de depot (identifiant interne au depositaire).
        return user.isClient()
                ? profile.pourClient()
                : profile;
    }

    // ──────────────────────── /change-password ──────────────────────────────

    /**
     * Changement de mot de passe par l'utilisateur connecte. Obligatoire a la
     * premiere connexion des comptes crees par l'administrateur (interne) ou par
     * l'agent (client) : leve le drapeau {@code must_change_password}.
     */
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(AuthUser user,
                                              @Valid @RequestBody ChangePasswordRequest req,
                                              ClientIp clientIp) {
        UserRow row = jdbc.queryForObject(
                SELECT_USER_BY_ID, UserRow.MAPPER, user.id());

        // Première connexion : l'utilisateur vient de s'authentifier (login + MFA)
        // avec son mot de passe provisoire ; le re-saisir serait redondant. On NE
        // vérifie donc PAS le mot de passe actuel dans ce cas. Pour un changement
        // VOLONTAIRE (drapeau retombé), le mot de passe actuel reste exigé.
        if (!row.mustChangePassword()) {
            if (req.currentPassword() == null
                    || !password.verify(req.currentPassword(), row.passwordHash())) {
                audit.log(user.id().toString(), "CHANGEMENT_MDP", AuditService.ECHEC, "—", clientIp.value());
                throw ApiException.unauthorized("Mot de passe actuel incorrect.");
            }
            ApiException.ensure(!req.newPassword().equals(req.currentPassword()),
                    "le nouveau mot de passe doit être différent de l'actuel");
        }

        jdbc.update("UPDATE users SET password_hash = ?, must_change_password = FALSE, "
                + "initial_password_enc = NULL, updated_at = now() WHERE id = ?",
                password.hash(req.newPassword()), user.id());
        // Revoque les sessions existantes : reconnexion avec le nouveau secret.
        jdbc.update("UPDATE refresh_tokens SET revoked = TRUE WHERE user_id = ?", user.id());

        audit.log(user.id().toString(), "CHANGEMENT_MDP", AuditService.SUCCES, "—", clientIp.value());
        return Map.of(CLE_MESSAGE, "Mot de passe mis à jour.");
    }

    // ──────────────────── /forgot-password & /reset-password ─────────────────

    /** Délai de validité d'un lien de réinitialisation (1 heure). */
    private static final long RESET_TOKEN_TTL_SECS = 3600;

    /** Réponse générique commune (anti-énumération de comptes). */
    private static final Map<String, Object> RESET_GENERIC_RESPONSE = Map.of(CLE_MESSAGE,
            "Si un compte est associé à cette adresse, un e-mail de réinitialisation a été envoyé.");

    /**
     * {@code POST /auth/forgot-password} — demande d'un lien de réinitialisation.
     * Renvoie toujours une réponse générique (ne révèle jamais si l'e-mail existe).
     * Si le compte existe et est actif, un jeton à usage unique (TTL 1h) est créé
     * et le lien envoyé par e-mail.
     */
    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req,
                                              ClientIp ip) {
        if (!rateLimiter.check("forgot:" + ip.value())) {
            throw ApiException.tooManyRequests();
        }
        // Nom distinct du champ `email` (EmailService) pour ne pas le masquer.
        String emailNormalise = req.email().trim().toLowerCase();
        jdbc.query("SELECT id, nom, prenom FROM users WHERE lower(email) = ? AND statut = 'ACTIF'",
                        (rs, n) -> new UUID[]{rs.getObject("id", UUID.class)},
                        emailNormalise)
                .stream().findFirst()
                .ifPresent(row -> {
                    UUID userId = row[0];
                    String token = Tokens.generateRefreshToken();
                    OffsetDateTime expires = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(RESET_TOKEN_TTL_SECS);
                    // Invalide les éventuels jetons actifs précédents puis crée le nouveau.
                    jdbc.update("UPDATE password_reset_tokens SET used_at = now() "
                            + "WHERE user_id = ? AND used_at IS NULL", userId);
                    jdbc.update("INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) "
                            + "VALUES (?,?,?)", userId, Tokens.sha256Hex(token), expires);
                    sendResetEmail(emailNormalise, token);
                    audit.log(userId.toString(), "DEMANDE_REINIT_MDP", AuditService.SUCCES, "—", ip.value());
                });
        return RESET_GENERIC_RESPONSE;
    }

    /**
     * {@code POST /auth/reset-password} — définit un nouveau mot de passe à partir
     * d'un jeton valide (non expiré, non consommé). Le jeton est invalidé et les
     * sessions existantes révoquées.
     */
    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@Valid @RequestBody ResetPasswordRequest req,
                                             ClientIp ip) {
        if (!rateLimiter.check("reset:" + ip.value())) {
            throw ApiException.tooManyRequests();
        }
        String tokenHash = Tokens.sha256Hex(req.token().trim());
        UUID userId = jdbc.query(
                        "SELECT user_id FROM password_reset_tokens "
                                + "WHERE token_hash = ? AND used_at IS NULL AND expires_at > now()",
                        (rs, n) -> rs.getObject(COL_USER_ID, UUID.class), tokenHash)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.badRequest(
                        "Lien de réinitialisation invalide ou expiré. Refaites une demande."));

        jdbc.update("UPDATE users SET password_hash = ?, must_change_password = FALSE, "
                + "initial_password_enc = NULL, updated_at = now() WHERE id = ?",
                password.hash(req.newPassword()), userId);
        jdbc.update("UPDATE password_reset_tokens SET used_at = now() WHERE token_hash = ?", tokenHash);
        // Révoque les sessions actives : reconnexion avec le nouveau secret.
        jdbc.update("UPDATE refresh_tokens SET revoked = TRUE WHERE user_id = ?", userId);

        audit.log(userId.toString(), "REINIT_MDP", AuditService.SUCCES, "—", ip.value());
        return Map.of(CLE_MESSAGE, "Mot de passe réinitialisé. Vous pouvez vous connecter.");
    }

    /** Envoie l'e-mail contenant le lien de réinitialisation (page frontend). */
    private void sendResetEmail(String to, String token) {
        String link = props.getPrimaryFrontendOrigin() + "/auth/reset?token=" + token;
        email.dispatchOne(to, "Réinitialisation de votre mot de passe",
                "<p>Vous avez demandé la réinitialisation de votre mot de passe sur la "
                        + "plateforme Valeurs du Trésor (Afriland First Bank).</p>"
                        + "<p>Cliquez sur le lien ci-dessous (valable 1 heure, usage unique) :</p>"
                        + "<p><a href=\"" + link + "\">" + link + "</a></p>"
                        + "<p>Si vous n'êtes pas à l'origine de cette demande, ignorez cet e-mail : "
                        + "votre mot de passe reste inchangé.</p>");
    }

    // ─────────────── OTP d'action (step-up) : ordres soumis/annulés/modifiés ───────────────

    /**
     * {@code POST /auth/otp/send} — envoie un code OTP à usage unique à
     * l'utilisateur connecté (par e-mail via le SMTP configuré), pour confirmer
     * une opération sensible (soumission, annulation ou modification d'ordre).
     * Réutilise le mécanisme MFA (table {@code mfa_challenges}).
     */
    @PostMapping("/otp/send")
    public Map<String, Object> sendActionOtp(AuthUser user, ClientIp ip) {
        if (!rateLimiter.check("otp-send:" + ip.value())) {
            throw ApiException.tooManyRequests();
        }
        UserRow row = jdbc.queryForObject(
                SELECT_USER_BY_ID, UserRow.MAPPER, user.id());
        OtpSettingsService.OtpSettings otpCfg = otpSettings.get();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // Un seul défi actif à la fois pour cet utilisateur.
        jdbc.update("UPDATE mfa_challenges SET consumed = TRUE "
                + "WHERE user_id = ? AND consumed = FALSE", user.id());

        boolean emailDeliverable = otpCfg.emailEnabled() && this.email.isConfigured();
        boolean useDevCode = props.getMfaDevCode() != null && !emailDeliverable;
        String otp = useDevCode ? props.getMfaDevCode() : Tokens.generateOtp(otpCfg.longueur());
        UUID challengeId = jdbc.queryForObject(
                "INSERT INTO mfa_challenges (user_id, code_hash, code_enc, expires_at) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                UUID.class, user.id(), Tokens.sha256Hex(otp), cipher.encrypt(otp),
                now.plusSeconds(otpCfg.ttlSecondes()));
        deliverOtp(otpCfg, row.email(), otp, useDevCode);
        audit.log(user.id().toString(), "OTP_ACTION_ENVOI", AuditService.SUCCES, "—", ip.value());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("challengeId", challengeId);
        body.put("expiresIn", otpCfg.ttlSecondes());
        return body;
    }

    /**
     * {@code POST /auth/otp/verify} — vérifie le code OTP d'action de
     * l'utilisateur connecté. Le défi doit lui appartenir, être actif et non
     * expiré. Consommé en cas de succès.
     */
    @PostMapping("/otp/verify")
    public Map<String, Object> verifyActionOtp(AuthUser user, ClientIp ip,
                                               @Valid @RequestBody MfaVerifyRequest req) {
        if (!rateLimiter.check("otp-verify:" + ip.value())) {
            throw ApiException.tooManyRequests();
        }
        RowMapper<MfaChallenge> mapper = (rs, n) -> new MfaChallenge(
                rs.getObject("id", UUID.class), rs.getObject(COL_USER_ID, UUID.class),
                rs.getString("code_hash"), rs.getObject(COL_EXPIRES_AT, OffsetDateTime.class),
                rs.getInt("attempts"), rs.getBoolean("consumed"));
        MfaChallenge ch = jdbc.query(
                        "SELECT id, user_id, code_hash, expires_at, attempts, consumed "
                                + "FROM mfa_challenges WHERE id = ?", mapper, req.challengeId())
                .stream().findFirst().orElse(null);

        if (ch == null || !ch.userId().equals(user.id()) || ch.consumed()
                || ch.expiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))
                || ch.attempts() >= otpSettings.get().maxTentatives()) {
            throw invalidMfa();
        }
        if (!Tokens.sha256Hex(req.code()).equals(ch.codeHash())) {
            jdbc.update("UPDATE mfa_challenges SET attempts = attempts + 1 WHERE id = ?", ch.id());
            audit.log(user.id().toString(), "OTP_ACTION_VERIF", AuditService.ECHEC, "—", ip.value());
            throw invalidMfa();
        }
        jdbc.update("UPDATE mfa_challenges SET consumed = TRUE WHERE id = ?", ch.id());
        audit.log(user.id().toString(), "OTP_ACTION_VERIF", AuditService.SUCCES, "—", ip.value());
        return Map.of("ok", true);
    }

    // ───────────────────────────── Helpers ──────────────────────────────────

    /** Nom du cookie portant le jeton de rafraichissement (HttpOnly). */
    private static final String RT_COOKIE = "afb_rt";
    /** Chemin du cookie : envoye uniquement aux routes d'authentification. */
    private static final String RT_COOKIE_PATH = "/api/v1/auth";

    /**
     * Pose le jeton de rafraichissement dans un cookie HttpOnly : inaccessible au
     * JavaScript (immunise contre le vol par XSS). {@code SameSite=Strict} pour
     * limiter le CSRF. {@code Secure} a activer derriere TLS (cf. FORCE_HTTPS).
     */
    private void setRefreshCookie(HttpServletResponse resp, String token) {
        ResponseCookie cookie = ResponseCookie.from(RT_COOKIE, token)
                .httpOnly(true)
                .sameSite("Strict")
                .path(RT_COOKIE_PATH)
                .maxAge(props.getRefreshTokenTtl())
                .secure("true".equalsIgnoreCase(System.getenv("FORCE_HTTPS")))
                .build();
        resp.addHeader("Set-Cookie", cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse resp) {
        ResponseCookie cookie = ResponseCookie.from(RT_COOKIE, "")
                .httpOnly(true).sameSite("Strict").path(RT_COOKIE_PATH).maxAge(0).build();
        resp.addHeader("Set-Cookie", cookie.toString());
    }

    /**
     * Emet les jetons : le jeton d'acces est renvoye dans le corps, le jeton de
     * rafraichissement est depose dans un cookie HttpOnly (jamais expose au JS).
     */
    private AuthResponse issueTokens(UserRow user, HttpServletResponse resp) {
        String accessToken = jwt.issue(user.id(), user.email(), user.role(), user.mustChangePassword());
        String refreshToken = Tokens.generateRefreshToken();
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
                user.id(), Tokens.sha256Hex(refreshToken),
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(props.getRefreshTokenTtl()));
        setRefreshCookie(resp, refreshToken);
        UserProfile profile = user.toProfile();
        // Masque le solde ET le compte de depot dans la reponse destinee aux clients.
        if (Rbac.isClient(user.role())) {
            profile = profile.pourClient();
        }
        // refreshToken = null dans le corps : il vit desormais dans le cookie.
        return new AuthResponse(accessToken, null, "Bearer",
                props.getAccessTokenTtl(), profile);
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("Identifiants invalides.");
    }

    private static ApiException invalidMfa() {
        return ApiException.unauthorized("Code de vérification invalide ou expiré.");
    }

    private static ApiException sessionExpired() {
        return ApiException.unauthorized("Session expirée, reconnectez-vous.");
    }
}
