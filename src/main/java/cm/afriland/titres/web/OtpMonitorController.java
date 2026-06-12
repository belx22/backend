package cm.afriland.titres.web;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;
import cm.afriland.titres.security.Rbac;
import cm.afriland.titres.security.SecretCipher;
import cm.afriland.titres.support.PageResponse;
import cm.afriland.titres.support.Pagination;

/**
 * Supervision des codes OTP (CSFT — relais back-office).
 *
 * <p>Permet au back-office (agent / superviseur / admin) de consulter les codes
 * de verification envoyes aux utilisateurs et aux clients, afin de relayer le
 * code par un autre canal en cas de non-reception (ou de le communiquer au
 * client present a l'agence).</p>
 *
 * <p>Securite : reserve aux acteurs internes (jamais un client) ; le code en
 * clair n'est utilise que pour cet affichage (la verification reste basee sur
 * l'empreinte) ; chaque consultation est tracee en audit.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/otp-log")
public class OtpMonitorController {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final RowMapper<OtpEntry> mapper;

    public OtpMonitorController(JdbcTemplate jdbc, AuditService audit, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.mapper = buildMapper(cipher);
    }

    record OtpEntry(
            UUID id, UUID userId, String utilisateurNom, String email, String role,
            String telephone, String code, int attempts, boolean consumed, boolean active,
            OffsetDateTime createdAt, OffsetDateTime expiresAt) {
    }

    private static final String SELECT = "SELECT m.id, m.user_id, m.code_enc, m.attempts, "
            + "m.consumed, m.created_at, m.expires_at, "
            + "u.nom, u.prenom, u.email, u.role, u.telephone "
            + "FROM mfa_challenges m JOIN users u ON u.id = m.user_id";

    /** Construit le mapper : dechiffre {@code code_enc} a la lecture (tolerant aux erreurs). */
    private static RowMapper<OtpEntry> buildMapper(SecretCipher cipher) {
        return (rs, n) -> {
            String nom = rs.getString("nom");
            String prenom = rs.getString("prenom");
            String affichage = (prenom != null && !prenom.isBlank()) ? nom + " " + prenom : nom;
            OffsetDateTime expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
            boolean consumed = rs.getBoolean("consumed");
            boolean active = !consumed && expiresAt != null && expiresAt.isAfter(OffsetDateTime.now());
            String code;
            try {
                code = cipher.decrypt(rs.getString("code_enc"));
            } catch (RuntimeException e) {
                // Valeur illisible (heritee/alteree) : on n'expose pas de code plutot que d'echouer.
                code = null;
            }
            return new OtpEntry(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    affichage,
                    rs.getString("email"),
                    rs.getString("role"),
                    rs.getString("telephone"),
                    code,
                    rs.getInt("attempts"),
                    consumed,
                    active,
                    rs.getObject("created_at", OffsetDateTime.class),
                    expiresAt);
        };
    }

    /**
     * {@code GET /admin/otp-log} — codes OTP recents (utilisateurs + clients).
     *
     * @param q          recherche optionnelle (e-mail, nom ou telephone)
     * @param activeOnly si vrai, ne renvoie que les codes encore valides
     */
    @GetMapping
    public PageResponse<OtpEntry> list(AuthUser user, ClientIp ip,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) Boolean activeOnly,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        // Reserve aux acteurs internes (relais guichet) — jamais un client.
        if (!Rbac.isStaff(user.role())) {
            throw ApiException.forbidden(
                    "La supervision des codes OTP est réservée au back-office.");
        }
        Pagination pg = Pagination.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.append(" AND (lower(u.email) LIKE ? OR lower(u.nom) LIKE ? "
                    + "OR lower(coalesce(u.prenom,'')) LIKE ? OR coalesce(u.telephone,'') LIKE ?)");
            String like = "%" + q.trim().toLowerCase() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add("%" + q.trim() + "%");
        }
        if (Boolean.TRUE.equals(activeOnly)) {
            where.append(" AND m.consumed = FALSE AND m.expires_at > now()");
        }

        long total = jdbc.queryForObject(
                "SELECT count(*) FROM mfa_challenges m JOIN users u ON u.id = m.user_id" + where,
                Long.class, args.toArray());

        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add(pg.limit());
        dataArgs.add(pg.offset());
        List<OtpEntry> data = jdbc.query(
                SELECT + where + " ORDER BY m.created_at DESC LIMIT ? OFFSET ?",
                mapper, dataArgs.toArray());

        // Tracabilite : la consultation de codes OTP est un acte sensible.
        audit.log(user.id().toString(), "CONSULTATION_OTP", AuditService.SUCCES,
                data.size() + " code(s)", ip.value());
        return pg.build(data, total);
    }
}
