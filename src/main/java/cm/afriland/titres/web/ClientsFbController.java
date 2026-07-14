package cm.afriland.titres.web;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;
import cm.afriland.titres.security.Permission;
import cm.afriland.titres.support.ClientsFbRepository;

/**
 * Back-office — referentiel des clients Afriland deja existants ({@code clients_fb}).
 *
 * <p>Le fichier « BASE CLIENTS » (Excel/CSV) est analyse par le navigateur, qui
 * poste ici les lignes normalisees. L'import est <b>idempotent</b> : re-jouer le
 * meme fichier rafraichit les lignes au lieu de les dupliquer.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/clients-fb")
public class ClientsFbController {

    /** Garde-fou : un import n'avale pas un fichier de taille arbitraire. */
    private static final int MAX_LIGNES = 20_000;

    private final JdbcTemplate jdbc;
    private final ClientsFbRepository referentiel;
    private final AuditService audit;

    public ClientsFbController(JdbcTemplate jdbc, ClientsFbRepository referentiel,
                               AuditService audit) {
        this.jdbc = jdbc;
        this.referentiel = referentiel;
        this.audit = audit;
    }

    record ImportRequest(List<Map<String, Object>> lignes) {
    }

    /** Importe (ou rafraichit) les lignes du fichier « BASE CLIENTS ». */
    @PostMapping("/import")
    public Map<String, Object> importer(AuthUser user, @RequestBody ImportRequest req,
                                        ClientIp clientIp) {
        user.require(Permission.CLIENT_MANAGE);
        if (req == null || req.lignes() == null || req.lignes().isEmpty()) {
            throw ApiException.badRequest("Aucune ligne a importer.");
        }
        if (req.lignes().size() > MAX_LIGNES) {
            throw ApiException.badRequest(
                    "Fichier trop volumineux (" + MAX_LIGNES + " lignes au maximum).");
        }

        int importees = 0;
        int ignorees = 0;
        for (Map<String, Object> ligne : req.lignes()) {
            if (referentiel.upsert(ligne, user.id())) {
                importees++;
            } else {
                ignorees++;   // numero de compte absent ou mal forme
            }
        }

        audit.log(user.id().toString(), "IMPORT_BASE_CLIENTS", AuditService.SUCCES,
                importees + " ligne(s)", clientIp.value());
        return Map.of("importees", importees, "ignorees", ignorees,
                "total", req.lignes().size());
    }

    /** Consultation du referentiel (recherche par nom ou numero de compte). */
    @GetMapping
    public List<Map<String, Object>> list(AuthUser user,
                                          @RequestParam(required = false) String q) {
        user.require(Permission.CLIENT_MANAGE);
        String recherche = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        String sql = "SELECT id, nom_prenom, numero_compte, compte_especes, agence, matricule, "
                + "categorie, compte_depot, assujetti_taxes, localisation, dirigeant, "
                + "telephone1, telephone2, email FROM clients_fb "
                + (recherche != null
                        ? "WHERE lower(nom_prenom) LIKE ? OR numero_compte LIKE ? OR compte_especes LIKE ? "
                        : "")
                + "ORDER BY nom_prenom LIMIT 200";
        return recherche != null
                ? jdbc.queryForList(sql, recherche, recherche, recherche)
                : jdbc.queryForList(sql);
    }
}
