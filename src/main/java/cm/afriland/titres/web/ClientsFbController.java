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
import cm.afriland.titres.support.PageResponse;
import cm.afriland.titres.support.Pagination;
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
        int doublons = 0;
        for (Map<String, Object> ligne : req.lignes()) {
            switch (referentiel.upsert(ligne, user.id())) {
                case IMPORTE -> importees++;
                case DOUBLON_IDENTITE -> doublons++;
                case NUMERO_INVALIDE -> ignorees++;
            }
        }

        audit.log(user.id().toString(), "IMPORT_BASE_CLIENTS", AuditService.SUCCES,
                importees + " ligne(s), " + doublons + " doublon(s) ecarte(s)", clientIp.value());
        return Map.of("importees", importees, "ignorees", ignorees, "doublons", doublons,
                "total", req.lignes().size());
    }

    /**
     * Consultation paginee du referentiel (recherche par nom ou numero de compte).
     *
     * <p>Le referentiel compte plusieurs milliers de lignes : les renvoyer d'un
     * bloc etait intenable. Une troncature muette (« les 200 premieres ») serait
     * pire encore — l'agent croirait avoir tout vu. On pagine donc, et on rend le
     * <b>total</b> pour qu'il sache ce qu'il reste.</p>
     */
    @GetMapping
    public PageResponse<Map<String, Object>> list(AuthUser user,
                                                  @RequestParam(required = false) String q,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        user.require(Permission.CLIENT_MANAGE);
        String recherche = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        String filtre = recherche != null
                ? "WHERE lower(nom_prenom) LIKE ? OR numero_compte LIKE ? OR compte_especes LIKE ? "
                : "";
        Object[] critere = recherche != null
                ? new Object[]{recherche, recherche, recherche}
                : new Object[0];

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM clients_fb " + filtre, Long.class, critere);

        Pagination pg = Pagination.of(page, size);
        Object[] args = new Object[critere.length + 2];
        System.arraycopy(critere, 0, args, 0, critere.length);
        args[critere.length] = pg.limit();
        args[critere.length + 1] = pg.offset();

        List<Map<String, Object>> data = jdbc.queryForList(
                "SELECT id, nom_prenom, numero_compte, compte_especes, agence, matricule, "
                        + "categorie, compte_depot, assujetti_taxes, localisation, dirigeant, "
                        + "telephone1, telephone2, email FROM clients_fb " + filtre
                        + "ORDER BY nom_prenom, numero_compte LIMIT ? OFFSET ?", args);

        return pg.build(data, total == null ? 0 : total);
    }

    /**
     * Référentiel complet, réduit aux champs d'identité — alimente le
     * <b>modèle d'import de portefeuille</b> pré-rempli côté navigateur (une ligne
     * par client de la base, colonnes titres à compléter). Borné à {@link #MAX_LIGNES}.
     */
    @GetMapping("/all")
    public List<Map<String, Object>> all(AuthUser user) {
        user.require(Permission.CLIENT_MANAGE);
        return jdbc.queryForList(
                "SELECT nom_prenom, matricule, compte_depot, numero_compte, compte_especes "
                        + "FROM clients_fb ORDER BY nom_prenom, numero_compte LIMIT " + MAX_LIGNES);
    }
}
