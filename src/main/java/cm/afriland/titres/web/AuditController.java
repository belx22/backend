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

import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.Permission;
import cm.afriland.titres.support.PageResponse;
import cm.afriland.titres.support.Pagination;

/**
 * Module 10 — Journal d'audit (CSFT §M6) — consultation en lecture seule.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    /**
     * Selection avec resolution de {@code audit_log.utilisateur} (UUID de
     * l'acteur) vers la fiche {@code users}. La valeur peut etre non-UUID
     * (« inconnu », « — ») : le CASE ne tente le cast que pour un format UUID.
     */
    private static final String BASE = "SELECT a.id, a.horodatage, a.utilisateur, "
            + "NULLIF(trim(coalesce(u.nom, '') || ' ' || coalesce(u.prenom, '')), '') AS utilisateur_nom, "
            + "u.role AS utilisateur_role, "
            + "a.action, a.resultat, a.reference, a.ip "
            + "FROM audit_log a "
            + "LEFT JOIN users u ON u.id = (CASE WHEN a.utilisateur ~ "
            + "'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' "
            + "THEN a.utilisateur::uuid END)";

    private final JdbcTemplate jdbc;

    public AuditController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record AuditEntry(UUID id, OffsetDateTime horodatage, String utilisateur, String utilisateurNom,
                      String utilisateurRole, String action, String resultat, String reference,
                      String ip) {
    }

    private static final RowMapper<AuditEntry> MAPPER = (rs, n) -> new AuditEntry(
            rs.getObject("id", UUID.class),
            rs.getObject("horodatage", OffsetDateTime.class),
            rs.getString("utilisateur"),
            rs.getString("utilisateur_nom"),
            rs.getString("utilisateur_role"),
            rs.getString("action"),
            rs.getString("resultat"),
            rs.getString("reference"),
            rs.getString("ip"));

    /** {@code GET /audit} — journal filtre et pagine (AUDIT_READ). */
    @GetMapping
    public PageResponse<AuditEntry> list(AuthUser user,
                                         @RequestParam(required = false) String utilisateur,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) String reference,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        user.require(Permission.AUDIT_READ);
        Pagination pg = Pagination.of(page, size);

        StringBuilder dataSql = new StringBuilder(BASE).append(" WHERE 1 = 1");
        StringBuilder countSql = new StringBuilder("SELECT count(*) FROM audit_log a WHERE 1 = 1");
        List<Object> args = new ArrayList<>();

        if (utilisateur != null && !utilisateur.isEmpty()) {
            dataSql.append(" AND a.utilisateur = ?");
            countSql.append(" AND a.utilisateur = ?");
            args.add(utilisateur);
        }
        if (action != null && !action.isEmpty()) {
            dataSql.append(" AND a.action = ?");
            countSql.append(" AND a.action = ?");
            args.add(action);
        }
        if (reference != null && !reference.isEmpty()) {
            dataSql.append(" AND a.reference = ?");
            countSql.append(" AND a.reference = ?");
            args.add(reference);
        }

        long total = jdbc.queryForObject(countSql.toString(), Long.class, args.toArray());

        dataSql.append(" ORDER BY a.horodatage DESC LIMIT ? OFFSET ?");
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add(pg.limit());
        dataArgs.add(pg.offset());

        List<AuditEntry> data = jdbc.query(dataSql.toString(), MAPPER, dataArgs.toArray());
        return pg.build(data, total);
    }
}
