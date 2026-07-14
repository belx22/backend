package cm.afriland.titres.web;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.dto.UserProfile;
import cm.afriland.titres.dto.UserRow;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;
import cm.afriland.titres.security.PasswordService;
import cm.afriland.titres.security.Permission;
import cm.afriland.titres.security.Rbac;
import cm.afriland.titres.security.Tokens;
import cm.afriland.titres.support.PageResponse;
import cm.afriland.titres.support.Pagination;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Module 10 — Administration : comptes utilisateurs (CSFT §M6-S03).
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final Set<String> ROLES = Set.of(Rbac.CLIENT_PP, Rbac.CLIENT_PM, Rbac.AGENT,
            Rbac.SUPERVISEUR, Rbac.ADMIN);
    private static final String INTERNAL_ROLES = "('AGENT','SUPERVISEUR','ADMIN')";
    /** Selection d'un utilisateur par identifiant — reutilisee par plusieurs endpoints. */
    private static final String SELECT_USER_BY_ID =
            "SELECT " + UserRow.COLUMNS + " FROM users WHERE id = ?";
    private static final String COMPTE_INTROUVABLE = "Compte introuvable.";

    private final JdbcTemplate jdbc;
    private final PasswordService password;
    private final AuditService audit;
    private final cm.afriland.titres.security.SecretCipher cipher;

    public UserController(JdbcTemplate jdbc, PasswordService password, AuditService audit,
                          cm.afriland.titres.security.SecretCipher cipher) {
        this.cipher = cipher;
        this.jdbc = jdbc;
        this.password = password;
        this.audit = audit;
    }

    record CreateUserRequest(
            @Email(message = "format d'e-mail invalide") String email,
            @Size(min = 8, max = 128, message = "mot de passe de 8 a 128 caracteres") String password,
            @NotNull String role,
            @Size(min = 1, max = 100) String nom,
            @Size(max = 100) String prenom,
            @Size(max = 60) String compteTitres,
            @Size(max = 60) String compteEspeces) {
    }

    record UpdateUserRequest(
            @Size(min = 1, max = 100) String nom,
            @Size(max = 100) String prenom,
            @Email(message = "format d'e-mail invalide") String email,
            String statut,
            @Size(max = 60) String compteTitres,
            @Size(max = 60) String compteEspeces) {
    }

    /** {@code GET /users} — liste des acteurs internes (USER_MANAGE). */
    @GetMapping
    public PageResponse<UserProfile> list(AuthUser user,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        user.require(Permission.USER_MANAGE);
        Pagination pg = Pagination.of(page, size);

        List<UserProfile> data = jdbc.query(
                        "SELECT " + UserRow.COLUMNS + " FROM users WHERE role IN " + INTERNAL_ROLES
                                + " ORDER BY nom, prenom LIMIT ? OFFSET ?",
                        UserRow.MAPPER, pg.limit(), pg.offset())
                .stream().map(UserRow::toProfile).toList();

        long total = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE role IN " + INTERNAL_ROLES, Long.class);
        return pg.build(data, total);
    }

    /** {@code POST /users} — creation d'un compte (USER_MANAGE, anti-elevation de privileges). */
    @PostMapping
    public ResponseEntity<UserProfile> create(AuthUser creator, ClientIp ip,
                                              @Valid @RequestBody CreateUserRequest req) {
        creator.require(Permission.USER_MANAGE);

        String role = req.role().trim().toUpperCase();
        ApiException.ensure(ROLES.contains(role), "rôle invalide");

        // Un agent ne peut pas creer de comptes a privileges superieurs aux siens.
        Set<String> allowed = switch (creator.role()) {
            case Rbac.ADMIN -> ROLES;
            case Rbac.SUPERVISEUR -> Set.of(Rbac.CLIENT_PP, Rbac.CLIENT_PM, Rbac.AGENT, Rbac.SUPERVISEUR);
            case Rbac.AGENT -> Set.of(Rbac.CLIENT_PP, Rbac.CLIENT_PM, Rbac.AGENT);
            default -> Set.of();
        };
        if (!allowed.contains(role)) {
            throw ApiException.forbidden(
                    "Vous n'êtes pas habilité à créer un compte avec ce rôle.");
        }

        String email = req.email().trim().toLowerCase();
        String hash = password.hash(req.password());

        // L'admin definit un mot de passe provisoire : l'utilisateur (agent /
        // superviseur) devra le remplacer des sa premiere connexion. Le mot de
        // passe initial est conserve chiffre (consultable par le back-office tant
        // qu'il n'a pas ete change).
        UserRow row = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, role, nom, prenom, compte_titres, "
                        + "compte_especes, must_change_password, initial_password_enc) "
                        + "VALUES (?,?,?,?,?,?,?, TRUE, ?) RETURNING " + UserRow.COLUMNS,
                UserRow.MAPPER, email, hash, role, req.nom().trim(),
                trimToNull(req.prenom()), trimToNull(req.compteTitres()), trimToNull(req.compteEspeces()),
                cipher.encrypt(req.password()));

        audit.log(creator.id().toString(), "CREATION_COMPTE", AuditService.SUCCES, email, ip.value());
        return ResponseEntity.status(HttpStatus.CREATED).body(row.toProfile());
    }

    /**
     * {@code PATCH /users/:id} — modification d'un compte, reservee a
     * l'administrateur. Aucun autre acteur ne peut modifier un profil (ni le
     * sien, ni celui d'autrui) : seul l'ADMIN en a le droit.
     */
    @PatchMapping("/{id}")
    public UserProfile update(AuthUser admin, ClientIp ip, @PathVariable UUID id,
                              @Valid @RequestBody UpdateUserRequest req) {
        if (!Rbac.ADMIN.equals(admin.role())) {
            throw ApiException.forbidden(
                    "Seul un administrateur peut modifier un profil utilisateur.");
        }
        if (req.statut() != null) {
            ApiException.ensure("ACTIF".equals(req.statut()) || "SUSPENDU".equals(req.statut()),
                    "statut invalide (ACTIF ou SUSPENDU)");
        }

        UserRow current = jdbc.query(
                        SELECT_USER_BY_ID, UserRow.MAPPER, id)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound(COMPTE_INTROUVABLE));

        String nom = req.nom() != null ? req.nom().trim() : current.nom();
        String prenom = req.prenom() != null ? req.prenom().trim() : current.prenom();
        String email = req.email() != null ? req.email().trim().toLowerCase() : current.email();
        String statut = req.statut() != null ? req.statut() : current.statut();
        String compteTitres = req.compteTitres() != null ? req.compteTitres() : current.compteTitres();
        String compteEspeces = req.compteEspeces() != null
                ? req.compteEspeces() : current.compteEspeces();

        jdbc.update("UPDATE users SET nom=?, prenom=?, email=?, statut=?, compte_titres=?, "
                        + "compte_especes=?, updated_at=now() WHERE id=?",
                nom, prenom, email, statut, compteTitres, compteEspeces, id);

        UserRow updated = jdbc.queryForObject(
                SELECT_USER_BY_ID, UserRow.MAPPER, id);
        audit.log(admin.id().toString(), "MODIFICATION_COMPTE", AuditService.SUCCES,
                updated.email(), ip.value());
        return updated.toProfile();
    }

    /** Corps optionnel : {@code password} = mot de passe choisi (sinon aléatoire). */
    record ResetPasswordRequest(@Size(max = 128) String password) {
    }

    /**
     * {@code POST /users/:id/reset-password} — réinitialise le mot de passe d'un
     * compte (interne OU client), réservé à l'ADMIN. Si {@code password} est
     * fourni dans le corps, ce mot de passe est appliqué (≥ 8 caractères) ; sinon
     * un mot de passe provisoire robuste est généré. Dans les deux cas, le compte
     * doit le changer à la prochaine connexion et ses sessions sont révoquées.
     * Le mot de passe en clair est renvoyé une seule fois pour être communiqué.
     */
    @PostMapping("/{id}/reset-password")
    public java.util.Map<String, Object> resetPassword(AuthUser admin, ClientIp ip,
                                                        @PathVariable UUID id,
                                                        @RequestBody(required = false) ResetPasswordRequest req) {
        if (!Rbac.ADMIN.equals(admin.role())) {
            throw ApiException.forbidden(
                    "Seul un administrateur peut réinitialiser un mot de passe.");
        }
        UserRow target = jdbc.query(
                        SELECT_USER_BY_ID, UserRow.MAPPER, id)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound(COMPTE_INTROUVABLE));

        String chosen = req == null ? null : trimToNull(req.password());
        if (chosen != null) {
            ApiException.ensure(chosen.length() >= 8,
                    "Le mot de passe doit comporter au moins 8 caractères.");
        }
        String newPassword = chosen != null ? chosen : Tokens.generatePassword();
        jdbc.update("UPDATE users SET password_hash = ?, must_change_password = TRUE, "
                + "initial_password_enc = ?, updated_at = now() WHERE id = ?",
                password.hash(newPassword), cipher.encrypt(newPassword), id);
        jdbc.update("UPDATE refresh_tokens SET revoked = TRUE WHERE user_id = ?", id);

        audit.log(admin.id().toString(), "REINIT_MDP_ADMIN", AuditService.SUCCES,
                target.email(), ip.value());
        return java.util.Map.of("temporaryPassword", newPassword);
    }

    /**
     * {@code GET /users/:id/initial-password} — renvoie le mot de passe
     * INITIAL/PROVISOIRE d'un compte, en clair, tant que l'utilisateur ne l'a pas
     * change ({@code must_change_password = TRUE}). Permet à l'acteur back-office
     * (agent / superviseur / admin) qui a créé le compte de le communiquer.
     * Une fois le mot de passe changé, la valeur n'est plus disponible.
     */
    @GetMapping("/{id}/initial-password")
    public java.util.Map<String, Object> initialPassword(AuthUser user, @PathVariable UUID id) {
        ApiException.ensure(user.isStaff(),
                "Consultation reservee au back-office.");
        java.util.Map<String, Object> row = jdbc.query(
                "SELECT must_change_password, initial_password_enc FROM users WHERE id = ?",
                rs -> {
                    if (!rs.next()) return null;
                    java.util.Map<String, Object> m = new java.util.HashMap<>();
                    m.put("must", rs.getBoolean("must_change_password"));
                    m.put("enc", rs.getString("initial_password_enc"));
                    return m;
                }, id);
        if (row == null) {
            throw ApiException.notFound(COMPTE_INTROUVABLE);
        }
        boolean must = Boolean.TRUE.equals(row.get("must"));
        String enc = (String) row.get("enc");
        String clear = (must && enc != null && !enc.isBlank()) ? safeDecrypt(enc) : null;
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("password", clear);
        // false => déjà changé (ou indisponible) : le front masque alors l'action.
        out.put("available", clear != null);
        return out;
    }

    private String safeDecrypt(String enc) {
        try {
            return cipher.decrypt(enc);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * {@code DELETE /users/:id} — supprime un compte (interne OU client), réservé
     * à l'ADMIN. Garde-fous : on ne peut pas supprimer son propre compte ni le
     * dernier administrateur actif. Si le compte porte des opérations liées
     * (ordres, dossier, co-signataires…), la suppression est refusée et l'on
     * invite à le SUSPENDRE (modification du statut) plutôt qu'à le supprimer.
     */
    @DeleteMapping("/{id}")
    public java.util.Map<String, Object> delete(AuthUser admin, ClientIp ip, @PathVariable UUID id) {
        if (!Rbac.ADMIN.equals(admin.role())) {
            throw ApiException.forbidden("Seul un administrateur peut supprimer un compte.");
        }
        if (admin.id().equals(id)) {
            throw ApiException.badRequest("Vous ne pouvez pas supprimer votre propre compte.");
        }
        UserRow target = jdbc.query(
                        SELECT_USER_BY_ID, UserRow.MAPPER, id)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound(COMPTE_INTROUVABLE));

        if (Rbac.ADMIN.equals(target.role())) {
            Long actifs = jdbc.queryForObject(
                    "SELECT count(*) FROM users WHERE role = 'ADMIN' AND statut = 'ACTIF'", Long.class);
            ApiException.ensure(actifs != null && actifs > 1,
                    "Impossible de supprimer le dernier administrateur actif.");
        }
        try {
            jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM password_reset_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("Ce compte est lié à des opérations (ordres, dossier, "
                    + "co-signataires…). Suspendez-le au lieu de le supprimer.");
        }
        audit.log(admin.id().toString(), "SUPPRESSION_COMPTE", AuditService.SUCCES,
                target.email(), ip.value());
        return java.util.Map.of("deleted", true);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
