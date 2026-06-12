package cm.afriland.titres.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.Permission;
import cm.afriland.titres.support.Countries;

/**
 * Module 7 — Portefeuille & positions titres (CSFT §M2-P01).
 *
 * Les positions sont derivees en base a partir des ordres effectivement
 * adjuges, agreges par emission. Le solde espece provient de la fiche users.
 */
@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioController {

    private final JdbcTemplate jdbc;

    public PortfolioController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
        // Seuls les acteurs back-office voient le solde (lecture cache base).
        // Pour le solde temps reel, ils consultent l'endpoint /account-balance.
        return buildPortfolio(clientId, staff);
    }

    private PortfolioResponse buildPortfolio(UUID clientId) {
        return buildPortfolio(clientId, false);
    }

    /**
     * Construit le portefeuille d'un client. Quand {@code includeBalance} est
     * faux (consultation par le client lui-meme), le solde espece est
     * volontairement nul : seuls les acteurs back-office peuvent connaitre
     * la tresorerie, et la source de verite est le serveur Amplitude/AIF.
     */
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

        return new PortfolioResponse(clientId, positions, positions.size(),
                valeurTotale, soldeEspeces);
    }
}
