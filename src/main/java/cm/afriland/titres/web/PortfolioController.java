package cm.afriland.titres.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;
import cm.afriland.titres.security.Permission;
import cm.afriland.titres.support.Countries;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Module 7 — Portefeuille & positions titres (CSFT §M2-P01).
 *
 * Les positions sont derivees en base a partir des ordres effectivement
 * adjuges, agreges par emission. Le solde espece provient de la fiche users.
 *
 * <p>Endpoint supplementaire : {@code POST /portfolio/import} — import batch de
 * positions depuis un releve Excel (Situation de Portefeuille). Chaque ligne
 * devient un ordre synthetique en statut {@code TOTALEMENT_RETENU}, visible
 * immediatement dans le portefeuille du client.
 */
@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioController {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public PortfolioController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ── Lecture portefeuille ─────────────────────────────────────────────────

    record Position(
            String emissionCode, String isin, String nature, String emetteur,
            LocalDate dateEmission, LocalDate dateEcheance, long volume, long valeurNominale,
            long valeurTotale, double tauxNominal) {
    }

    record PortfolioResponse(
            UUID clientId, List<Position> positions, long lignesActives,
            long valeurTotale, Long soldeEspeces) {
    }

    private static final RowMapper<Position> POSITION_MAPPER = (rs, n) -> new Position(
            rs.getString("emission_code"),
            rs.getString("isin"),
            rs.getString("nature"),
            Countries.label(rs.getString("pays_code")),
            rs.getObject("date_emission", LocalDate.class),
            rs.getObject("date_echeance", LocalDate.class),
            rs.getLong("volume"),
            rs.getLong("valeur_nominale"),
            rs.getLong("valeur_totale"),
            rs.getDouble("taux_nominal"));

    /** {@code GET /portfolio} — positions du client connecte (lecture seule). */
    @GetMapping
    public PortfolioResponse myPortfolio(AuthUser user) {
        return buildPortfolio(user.id());
    }

    /** {@code GET /portfolio/:clientId} — positions d'un client donne. */
    @GetMapping("/{clientId}")
    public PortfolioResponse clientPortfolio(AuthUser user, @PathVariable UUID clientId) {
        boolean staff = !user.id().equals(clientId);
        if (staff) {
            user.require(Permission.CLIENT_MANAGE);
        }
        return buildPortfolio(clientId, staff);
    }

    private PortfolioResponse buildPortfolio(UUID clientId) {
        return buildPortfolio(clientId, false);
    }

    private PortfolioResponse buildPortfolio(UUID clientId, boolean includeBalance) {
        List<Position> positions = jdbc.query(
                "SELECT e.code AS emission_code, o.isin, e.nature, e.pays_code, "
                        + "e.date_emission, e.date_echeance, "
                        + "sum(coalesce(o.volume_alloue, 0))::bigint AS volume, "
                        + "e.valeur_nominale_unitaire AS valeur_nominale, "
                        + "sum(coalesce(o.montant_adjuge, 0))::bigint AS valeur_totale, "
                        + "e.taux_nominal "
                        + "FROM orders o JOIN emissions e ON e.id = o.emission_id "
                        + "WHERE o.client_id = ? "
                        + "  AND o.status IN ('TOTALEMENT_RETENU', 'PARTIELLEMENT_RETENU') "
                        + "GROUP BY e.id, o.isin, e.code, e.nature, e.pays_code, e.date_emission, "
                        + "  e.date_echeance, e.valeur_nominale_unitaire, e.taux_nominal "
                        + "HAVING sum(coalesce(o.volume_alloue, 0)) > 0 "
                        + "ORDER BY e.date_echeance",
                POSITION_MAPPER, clientId);

        Long soldeEspeces = null;
        if (includeBalance) {
            soldeEspeces = jdbc.query("SELECT solde FROM users WHERE id = ?",
                            (rs, n) -> rs.getObject("solde", Long.class), clientId)
                    .stream().findFirst().orElse(null);
        }

        long valeurTotale = positions.stream().mapToLong(Position::valeurTotale).sum();
        return new PortfolioResponse(clientId, positions, positions.size(), valeurTotale, soldeEspeces);
    }

    // ── Import batch ─────────────────────────────────────────────────────────

    /** Ligne d'import transmise par le frontend (issue du parsing Excel). */
    record ImportRow(
            String emissionId,
            @NotBlank String isin,
            String designation,
            String emetteur,
            String clientId,
            String clientNom,
            String matriculeClient,
            String numeroCompte,
            @NotNull @Min(1) long volume,
            long montantAdjuge,
            double tauxNominal) {
    }

    record ImportRequest(@NotNull List<@NotNull ImportRow> rows) {
    }

    record ImportError(String isin, String clientNom, String error) {
    }

    record ImportResult(int imported, List<ImportError> errors) {
    }

    /**
     * {@code POST /portfolio/import} — importe un batch de positions de
     * portefeuille issues d'un releve Excel (Situation de Portefeuille SVT).
     *
     * <p>Chaque ligne devient un ordre synthetique en statut
     * {@code TOTALEMENT_RETENU}, ce qui le rend immediatement visible dans
     * le portefeuille du client ({@code GET /portfolio/:clientId}).
     *
     * <p>Permission requise : {@code CLIENT_MANAGE} (agents / superviseurs).
     */
    @PostMapping("/import")
    @Transactional
    public ImportResult importPortfolio(
            AuthUser user,
            ClientIp ip,
            @RequestBody @Valid ImportRequest req) {

        user.require(Permission.CLIENT_MANAGE);

        int imported = 0;
        List<ImportError> errors = new ArrayList<>();

        for (ImportRow row : req.rows()) {
            try {
                // 1. Resoudre l'emission (par ID fourni ou par ISIN)
                UUID emId = resolveEmissionId(row);
                if (emId == null) {
                    errors.add(new ImportError(row.isin(), row.clientNom(),
                            "Emission introuvable : " + row.isin()));
                    continue;
                }

                // 2. Resoudre le client (par ID, sous-compte ou compte_titres)
                UUID clientId = resolveClientId(row);
                if (clientId == null) {
                    errors.add(new ImportError(row.isin(), row.clientNom(),
                            "Client introuvable (compte : " + row.numeroCompte() + ")"));
                    continue;
                }

                // 3. Donnees de l'ordre synthetique
                int volume = (int) Math.max(1L, row.volume());
                long valNom = getValeurNominale(emId);
                long montant = row.montantAdjuge() > 0
                        ? row.montantAdjuge()
                        : (long) volume * valNom;
                if (montant <= 0) montant = valNom;

                String compteTitres = (row.numeroCompte() != null && !row.numeroCompte().isBlank())
                        ? row.numeroCompte()
                        : "—";
                String compteEspeces = getCompteEspeces(clientId);
                String reference = "IMP-" + row.isin().substring(0, Math.min(6, row.isin().length()))
                        + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();

                // 4. Insertion de l'ordre en TOTALEMENT_RETENU
                jdbc.update(
                        "INSERT INTO orders "
                                + "(reference, emission_id, client_id, isin, volume, montant, "
                                + " taux_soumis, status, compte_especes, compte_titres, canal, "
                                + " montant_adjuge, taux_adjuge, volume_alloue) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'TOTALEMENT_RETENU', ?, ?, 'GUICHET', ?, ?, ?)",
                        reference, emId, clientId, row.isin(),
                        volume, montant, row.tauxNominal(),
                        compteEspeces, compteTitres,
                        montant, row.tauxNominal(), volume);

                imported++;

            } catch (Exception e) {
                errors.add(new ImportError(row.isin(), row.clientNom(),
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }

        audit.log(user.id().toString(), "IMPORT_PORTEFEUILLE", AuditService.SUCCES,
                imported + " position(s) importee(s)", ip.value());

        return new ImportResult(imported, errors);
    }

    // ── Helpers resolvers ────────────────────────────────────────────────────

    private UUID resolveEmissionId(ImportRow row) {
        // Priorite : emissionId fourni par le frontend (deja cree ou trouve)
        if (row.emissionId() != null && !row.emissionId().isBlank()) {
            try {
                UUID id = UUID.fromString(row.emissionId());
                return jdbc.query("SELECT id FROM emissions WHERE id = ?",
                        (rs, n) -> rs.getObject("id", UUID.class), id)
                        .stream().findFirst().orElse(null);
            } catch (IllegalArgumentException ignored) {
                // pas un UUID valide, on tente par ISIN
            }
        }
        // Repli : recherche par ISIN
        return jdbc.query("SELECT id FROM emissions WHERE isin = ?",
                (rs, n) -> rs.getObject("id", UUID.class), row.isin())
                .stream().findFirst().orElse(null);
    }

    private UUID resolveClientId(ImportRow row) {
        // Priorite : clientId fourni par le frontend
        if (row.clientId() != null && !row.clientId().isBlank()) {
            try {
                UUID id = UUID.fromString(row.clientId());
                return jdbc.query("SELECT id FROM users WHERE id = ?",
                        (rs, n) -> rs.getObject("id", UUID.class), id)
                        .stream().findFirst().orElse(null);
            } catch (IllegalArgumentException ignored) {
                // pas un UUID valide
            }
        }
        // Repli 1 : sous-compte titres
        if (row.numeroCompte() != null && !row.numeroCompte().isBlank()) {
            UUID found = jdbc.query(
                    "SELECT user_id FROM sous_comptes_titres WHERE numero = ? LIMIT 1",
                    (rs, n) -> rs.getObject("user_id", UUID.class), row.numeroCompte())
                    .stream().findFirst().orElse(null);
            if (found != null) return found;

            // Repli 2 : compte_titres direct sur l'utilisateur
            found = jdbc.query(
                    "SELECT id FROM users WHERE compte_titres = ? "
                            + "AND role IN ('CLIENT_PP','CLIENT_PM') LIMIT 1",
                    (rs, n) -> rs.getObject("id", UUID.class), row.numeroCompte())
                    .stream().findFirst().orElse(null);
            if (found != null) return found;
        }
        return null;
    }

    private String getCompteEspeces(UUID clientId) {
        return jdbc.query("SELECT compte_especes FROM users WHERE id = ?",
                (rs, n) -> rs.getString("compte_especes"), clientId)
                .stream().findFirst().filter(s -> s != null && !s.isBlank()).orElse("");
    }

    private long getValeurNominale(UUID emissionId) {
        return jdbc.query("SELECT valeur_nominale_unitaire FROM emissions WHERE id = ?",
                (rs, n) -> rs.getLong("valeur_nominale_unitaire"), emissionId)
                .stream().findFirst().orElse(1_000_000L);
    }
}
