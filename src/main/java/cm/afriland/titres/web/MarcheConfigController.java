package cm.afriland.titres.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;
import cm.afriland.titres.security.Permission;

/**
 * Configuration de l'affichage du marche primaire public (CSFT §M1 — CONFIG_MARCHE).
 *
 * <p>Determine quels statuts d'emission sont visibles cote public et, le cas
 * echeant, la fenetre de dates d'emission. Persistee en base (table singleton
 * {@code marche_config}) afin d'etre partagee entre acteurs et de survivre aux
 * rechargements. Lecture ouverte au back-office ; modification reservee aux
 * acteurs disposant de la permission {@code CONFIG_MARCHE}.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/marche-config")
public class MarcheConfigController {

    /** Statuts d'emission autorises (CSFT §M1-F04). */
    private static final Set<String> ALLOWED_STATUSES =
            Set.of("BROUILLON", "PUBLIE", "CLOTURE", "ARCHIVE");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public MarcheConfigController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Corps de mise a jour. {@code dateDu}/{@code dateAu} : "" ou null = pas de borne. */
    public record UpdateRequest(List<String> statuses, String dateDu, String dateAu) {
    }

    /** Reponse alignee sur le modele frontend {@code marcheConfig}. */
    public record MarcheConfigResponse(List<String> statuses, String dateDu, String dateAu) {
    }

    /**
     * {@code GET /admin/marche-config} — configuration courante.
     * Lisible par tout utilisateur authentifie : elle determine le filtrage de
     * l'affichage du marche primaire, y compris cote client. La modification
     * reste reservee aux profils {@code CONFIG_MARCHE}.
     */
    @GetMapping
    public MarcheConfigResponse get(AuthUser user) {
        return load();
    }

    /** {@code PUT /admin/marche-config} — enregistre la configuration (CONFIG_MARCHE). */
    @PutMapping
    public MarcheConfigResponse update(AuthUser user, ClientIp ip, @RequestBody UpdateRequest req) {
        user.require(Permission.CONFIG_MARCHE);

        List<String> statuses = sanitizeStatuses(req.statuses());
        ApiException.ensure(!statuses.isEmpty(),
                "Selectionnez au moins un statut d'emission a afficher.");
        LocalDate dateDu = parseDate(req.dateDu(), "date de debut");
        LocalDate dateAu = parseDate(req.dateAu(), "date de fin");
        ApiException.ensure(dateDu == null || dateAu == null || !dateDu.isAfter(dateAu),
                "La date de debut doit preceder la date de fin.");

        jdbc.update(
                "UPDATE marche_config SET statuses = ?, date_du = ?, date_au = ?, "
                        + "updated_at = now(), updated_by = ? WHERE id = TRUE",
                String.join(",", statuses), dateDu, dateAu, user.id());

        audit.log(user.id().toString(), "CONFIG_AFFICHAGE_MARCHE", AuditService.SUCCES,
                String.join("|", statuses), ip.value());
        return load();
    }

    // ───────────────────────────── Helpers ──────────────────────────────────

    private MarcheConfigResponse load() {
        return jdbc.queryForObject(
                "SELECT statuses, date_du, date_au FROM marche_config WHERE id = TRUE",
                (rs, n) -> {
                    List<String> statuses = sanitizeStatuses(
                            List.of(rs.getString("statuses").split(",")));
                    LocalDate du = rs.getObject("date_du", LocalDate.class);
                    LocalDate au = rs.getObject("date_au", LocalDate.class);
                    return new MarcheConfigResponse(
                            statuses,
                            du == null ? "" : du.toString(),
                            au == null ? "" : au.toString());
                });
    }

    /** Conserve uniquement les statuts connus, sans doublon, dans l'ordre fourni. */
    private static List<String> sanitizeStatuses(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String s : raw) {
                String v = s == null ? "" : s.trim().toUpperCase();
                if (ALLOWED_STATUSES.contains(v) && !out.contains(v)) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /** Analyse une date ISO (YYYY-MM-DD) ; "" ou null = pas de borne (null). */
    private static LocalDate parseDate(String value, String label) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Format de " + label + " invalide (attendu AAAA-MM-JJ).");
        }
    }
}
