package cm.afriland.titres.web;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import cm.afriland.titres.support.FileStorageService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Back-office — verification des dossiers d'auto-inscription, en particulier la
 * <b>capture faciale</b> : l'agent rejoue les 468 reperes (marqueurs) et la vue
 * 3D avant de valider ou rejeter l'identite. Reserve a {@code CLIENT_MANAGE}.
 */
@RestController
@RequestMapping("/api/v1/admin/registrations")
public class AdminRegistrationController {

    private static final String EN_ATTENTE_VERIFICATION = "EN_ATTENTE_VERIFICATION";

    /** Catégories client valides (garde-fou avant l'INSERT : la colonne a un CHECK). */
    private static final Set<String> CATEGORIES_CLIENT = Set.of(
            "Personnes physiques", "Personne morale", "Entreprises non financières",
            "Etablissements de microfinance", "Etablissements financiers",
            "Société de Gestion", "Société de Bourse", "Investisseurs institutionnels",
            "Assurance", "Administrations privées", "Administrations publiques", "Compte propre");

    /** Attributs repris de la BASE CLIENTS (clients_fb) vers le profil plateforme. */
    private record BaseClientFields(String categorieClient, String matricule, String dirigeant,
                                    Boolean assujettiTaxes, String localisation, String telephone2) {
    }

    private final JdbcTemplate jdbc;
    private final FileStorageService storage;
    private final AuditService audit;
    private final cm.afriland.titres.notif.CredentialDelivery credentials;

    public AdminRegistrationController(JdbcTemplate jdbc, FileStorageService storage,
                                       AuditService audit,
                                       cm.afriland.titres.notif.CredentialDelivery credentials) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.audit = audit;
        this.credentials = credentials;
    }

    record VerifyRequest(
            @Pattern(regexp = "VALIDE|REJETE", message = "statut invalide (VALIDE|REJETE)")
            String status) {
    }

    /** File des dossiers disposant d'une capture faciale, filtrable par statut de verification. */
    @GetMapping
    public List<Map<String, Object>> list(AuthUser user,
                                          @RequestParam(required = false) String status) {
        user.require(Permission.CLIENT_MANAGE);
        String filtre = normaliserStatut(status);
        String sql = "SELECT d.id AS dossier_id, d.type_personne, d.statut AS dossier_statut, "
                + "d.compte_especes, d.submitted_at, d.created_at, u.email, t.nom, t.prenom, "
                + "f.id AS face_id, f.liveness_score, f.verification_status, f.created_at AS face_at "
                + "FROM registration_dossiers d "
                + "JOIN users u ON u.id = d.user_id "
                + "JOIN dossier_titulaires t ON t.dossier_id = d.id AND t.role_titulaire = 'PRINCIPAL' "
                + "JOIN face_captures f ON f.dossier_id = d.id "
                + (filtre != null ? "WHERE f.verification_status = ? " : "")
                + "ORDER BY f.created_at DESC LIMIT 100";
        return filtre != null ? jdbc.queryForList(sql, filtre) : jdbc.queryForList(sql);
    }

    /** Image + reperes 468 pts d'un dossier, pour le rejeu des marqueurs et la vue 3D. */
    @GetMapping("/{id}/face")
    public Map<String, Object> face(AuthUser user, @PathVariable UUID id) {
        user.require(Permission.CLIENT_MANAGE);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, nom_fichier, chemin, landmarks_path, liveness_score, challenge_type, "
                        + "capture_width, capture_height, verification_status "
                        + "FROM face_captures WHERE dossier_id = ? ORDER BY created_at DESC LIMIT 1", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Aucune capture faciale pour ce dossier.");
        }
        Map<String, Object> f = rows.get(0);

        byte[] image = storage.read((String) f.get("chemin"));
        String imageBase64 = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image);
        String landmarksJson = f.get("landmarks_path") != null
                ? new String(storage.read((String) f.get("landmarks_path")))
                : "[]";

        return Map.ofEntries(
                Map.entry("faceId", f.get("id")),
                Map.entry("dossierId", id),
                Map.entry("nomFichier", f.get("nom_fichier")),
                Map.entry("imageBase64", imageBase64),
                Map.entry("landmarksJson", landmarksJson),
                Map.entry("livenessScore", f.get("liveness_score")),
                Map.entry("challengeType", String.valueOf(f.get("challenge_type"))),
                Map.entry("width", f.get("capture_width") != null ? f.get("capture_width") : 0),
                Map.entry("height", f.get("capture_height") != null ? f.get("capture_height") : 0),
                Map.entry("verificationStatus", f.get("verification_status")));
    }

    /**
     * Capture faciale d'un CLIENT (par son identifiant utilisateur) — permet a
     * l'agent de verifier la photo directement depuis le dossier du client,
     * sans passer par la file d'attente des inscriptions.
     */
    @GetMapping("/by-user/{userId}/face")
    public Map<String, Object> faceByUser(AuthUser user, @PathVariable UUID userId) {
        user.require(Permission.CLIENT_MANAGE);
        List<Map<String, Object>> d = jdbc.queryForList(
                "SELECT id FROM registration_dossiers WHERE user_id = ? "
                        + "ORDER BY created_at DESC LIMIT 1", userId);
        if (d.isEmpty()) {
            throw ApiException.notFound("Aucun dossier d'inscription en ligne pour ce client.");
        }
        return face(user, (UUID) d.get(0).get("id"));
    }

    /** Valide ou rejette la capture faciale (verification humaine). */
    @PostMapping("/{id}/face/verify")
    public Map<String, Object> verify(AuthUser user, @PathVariable UUID id,
                                      @Valid @RequestBody VerifyRequest req, ClientIp clientIp) {
        user.require(Permission.CLIENT_MANAGE);
        int updated = jdbc.update(
                "UPDATE face_captures SET verification_status = ?, verified_by = ?, verified_at = now() "
                        + "WHERE dossier_id = ?", req.status(), user.id(), id);
        if (updated == 0) {
            throw ApiException.notFound("Aucune capture faciale pour ce dossier.");
        }
        String action = "VALIDE".equals(req.status()) ? "VERIF_FACIALE_OK" : "VERIF_FACIALE_KO";
        audit.log(user.id().toString(), action, AuditService.SUCCES, id.toString(), clientIp.value());
        return Map.of("dossierId", id, "verificationStatus", req.status());
    }

    // ═══════════════ Validation du dossier → creation du client ═══════════════

    record DecisionRequest(String motif) {
    }

    /** Le compte-titres est saisi par l'agent — facultatif : il peut le poser plus tard. */
    record ApproveRequest(
            @Size(max = 34, message = "compte-titres trop long") String compteTitres) {
    }

    /** Dossier complet : identite, compte especes demande, pieces deposees, etat du visage. */
    @GetMapping("/{id}/dossier")
    public Map<String, Object> dossier(AuthUser user, @PathVariable UUID id) {
        user.require(Permission.CLIENT_MANAGE);
        Map<String, Object> d = chargerDossier(id);

        List<Map<String, Object>> pieces = jdbc.queryForList(
                "SELECT id, cote, document_type, ocr_texte, nettete, largeur, hauteur, chemin "
                        + "FROM justificatifs WHERE dossier_id = ? AND type = 'PIECE_IDENTITE' "
                        + "ORDER BY cote", id);
        for (Map<String, Object> p : pieces) {
            String chemin = (String) p.remove("chemin");
            p.put("imageBase64", "data:image/jpeg;base64,"
                    + Base64.getEncoder().encodeToString(storage.read(chemin)));
        }

        List<Map<String, Object>> visage = jdbc.queryForList(
                "SELECT verification_status, liveness_score FROM face_captures "
                        + "WHERE dossier_id = ? ORDER BY created_at DESC LIMIT 1", id);

        // Compte-titres deja connu du referentiel « base clients » (clients_fb.compte_depot) :
        // permet de pre-remplir le champ d'attribution quand le prospect est deja client.
        UUID clientFbId = (UUID) d.get("client_fb_id");
        String compteTitresBase = clientFbId == null ? null : jdbc.query(
                "SELECT compte_depot FROM clients_fb WHERE id = ?",
                rs -> rs.next() ? rs.getString("compte_depot") : null, clientFbId);

        Map<String, Object> reponse = new LinkedHashMap<>();
        reponse.put("dossierId", id);
        reponse.put("statut", d.get("statut"));
        reponse.put("typePersonne", d.get("type_personne"));
        reponse.put("compteEspeces", d.get("compte_especes"));
        reponse.put("email", d.get("email"));
        reponse.put("nom", String.valueOf(d.get("nom")));
        reponse.put("prenom", d.get("prenom") == null ? "" : d.get("prenom"));
        reponse.put("telephone", d.get("telephone") == null ? "" : d.get("telephone"));
        reponse.put("compteTitresBase", compteTitresBase == null ? "" : compteTitresBase);
        reponse.put("pieces", pieces);
        reponse.put("visage", visage.isEmpty() ? Map.of() : visage.get(0));
        return reponse;
    }

    /**
     * Valide le dossier et ouvre le compte du client.
     *
     * <p>Le <b>compte-titres est saisi par l'agent</b> : le client, lui, n'a
     * renseigne que son compte especes a l'inscription. L'agent peut le laisser
     * vide et le renseigner plus tard (le client apparait alors en rouge dans les
     * listes) — cela n'empeche pas le client de soumettre des ordres.</p>
     *
     * <p>En revanche la <b>capture faciale doit avoir ete verifiee</b> : c'est le
     * controle d'identite, et il conditionne la validation du dossier.</p>
     */
    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(AuthUser user, @PathVariable UUID id,
                                       @Valid @RequestBody(required = false) ApproveRequest req,
                                       ClientIp clientIp) {
        user.require(Permission.CLIENT_MANAGE);
        Map<String, Object> d = chargerDossier(id);
        exigerEnAttente(d);

        String visage = statutVisage(id);
        if (!"VALIDE".equals(visage)) {
            throw ApiException.badRequest("Verifiez d'abord la capture faciale du client "
                    + "(statut actuel : " + visage + ").");
        }

        UUID userId = (UUID) d.get("user_id");
        String compteEspeces = (String) d.get("compte_especes");
        String compteTitres = req == null ? null : trimOuNull(req.compteTitres());
        String typePersonne = (String) d.get("type_personne");
        boolean pm = "PM".equals(typePersonne);

        // Reprise des attributs de la BASE CLIENTS quand le compte est connu du
        // referentiel : le profil plateforme reflete alors la fiche banque
        // (categorie metier, matricule, dirigeant, assujettissement, localisation).
        UUID clientFbId = (UUID) d.get("client_fb_id");
        BaseClientFields base = clientFbId != null ? chargerBaseClient(clientFbId) : null;

        if (compteTitres != null && estDejaAttribue(compteTitres, userId)) {
            throw ApiException.conflict("Ce compte-titres est deja attribue a un autre client.");
        }

        // COALESCE : un compte-titres omis ne doit pas effacer celui deja saisi.
        jdbc.update("UPDATE users SET compte_titres = COALESCE(?, compte_titres), "
                        + "compte_especes = COALESCE(compte_especes, ?), solde = COALESCE(solde, 0), "
                        + "categorie = COALESCE(categorie, 'NON_QUALIFIE'), "
                        + "type_compte = COALESCE(type_compte, ?), role = ? WHERE id = ?",
                compteTitres, compteEspeces, d.get("type_compte"),
                pm ? "CLIENT_PM" : "CLIENT_PP", userId);

        // Profil client — deja present si le dossier avait ete valide puis rejoue.
        String raisonSociale = (String) d.get("nom");
        if (!pm && d.get("prenom") != null) {
            raisonSociale = raisonSociale + " " + d.get("prenom");
        }
        jdbc.update("INSERT INTO client_profiles (user_id, type_personne, raison_sociale, "
                        + "categorie_client, matricule, dirigeant, assujetti_taxes, localisation, "
                        + "telephone2, compte_statut, created_by) "
                        + "VALUES (?,?,?,?,?,?,?,?,?, 'ACTIF', ?) "
                        + "ON CONFLICT (user_id) DO NOTHING",
                userId, typePersonne, raisonSociale,
                base != null ? base.categorieClient() : null,
                base != null ? base.matricule() : null,
                base != null ? base.dirigeant() : null,
                base != null ? base.assujettiTaxes() : null,
                base != null ? base.localisation() : null,
                base != null ? base.telephone2() : null,
                user.id());

        jdbc.update("UPDATE registration_dossiers SET statut = 'VALIDE', validated_by = ?, "
                + "validated_at = now(), updated_at = now() WHERE id = ?", user.id(), id);

        audit.log(user.id().toString(), "DOSSIER_VALIDE", AuditService.SUCCES,
                id.toString(), clientIp.value());

        // Confirmation d'ouverture du compte-titres envoyee au client (salutation
        // PP/PM + numero de compte masque). Non bloquant, avec reprise (cf. OTP).
        String compteTitresEffectif = jdbc.queryForObject(
                "SELECT compte_titres FROM users WHERE id = ?", String.class, userId);
        credentials.sendAccountOpened((String) d.get("email"), raisonSociale,
                compteTitresEffectif, pm);

        Map<String, Object> reponse = new LinkedHashMap<>();
        reponse.put("dossierId", id);
        reponse.put("statut", "VALIDE");
        reponse.put("userId", userId);
        reponse.put("compteTitres", compteTitres);   // null = a renseigner (client en rouge)
        reponse.put("compteEspeces", compteEspeces);
        return reponse;
    }

    private boolean estDejaAttribue(String compte, UUID sauf) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE (compte_titres = ? OR compte_especes = ?) "
                        + "AND id <> ?", Long.class, compte, compte, sauf);
        return n != null && n > 0;
    }

    private static String trimOuNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Rejette le dossier avec un motif ; le prospect garde son compte mais pas de compte-titres. */
    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(AuthUser user, @PathVariable UUID id,
                                      @RequestBody DecisionRequest req, ClientIp clientIp) {
        user.require(Permission.CLIENT_MANAGE);
        Map<String, Object> d = chargerDossier(id);
        exigerEnAttente(d);

        if (req == null || req.motif() == null || req.motif().isBlank()) {
            throw ApiException.badRequest("Le motif de rejet est obligatoire.");
        }
        jdbc.update("UPDATE registration_dossiers SET statut = 'REJETE', motif_rejet = ?, "
                        + "validated_by = ?, validated_at = now(), updated_at = now() WHERE id = ?",
                req.motif().trim(), user.id(), id);

        audit.log(user.id().toString(), "DOSSIER_REJETE", AuditService.SUCCES,
                id.toString(), clientIp.value());
        return Map.of("dossierId", id, "statut", "REJETE", "motif", req.motif().trim());
    }

    private Map<String, Object> chargerDossier(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.id, d.user_id, d.type_personne, d.compte_especes, d.type_compte, d.statut, "
                        + "d.client_fb_id, u.email, u.telephone, t.nom, t.prenom "
                        + "FROM registration_dossiers d "
                        + "JOIN users u ON u.id = d.user_id "
                        + "JOIN dossier_titulaires t ON t.dossier_id = d.id "
                        + "  AND t.role_titulaire = 'PRINCIPAL' "
                        + "WHERE d.id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Dossier introuvable.");
        }
        return rows.get(0);
    }

    /**
     * Charge les attributs repris de la BASE CLIENTS pour un compte connu.
     * La catégorie hors des 12 valeurs référencées est neutralisée (NULL) pour
     * ne pas heurter le CHECK de {@code client_profiles.categorie_client}.
     */
    private BaseClientFields chargerBaseClient(UUID clientFbId) {
        List<Map<String, Object>> r = jdbc.queryForList(
                "SELECT categorie, matricule, dirigeant, assujetti_taxes, localisation, telephone2 "
                        + "FROM clients_fb WHERE id = ?", clientFbId);
        if (r.isEmpty()) {
            return null;
        }
        Map<String, Object> m = r.get(0);
        String cat = (String) m.get("categorie");
        if (cat != null && !CATEGORIES_CLIENT.contains(cat)) {
            cat = null;
        }
        return new BaseClientFields(cat, (String) m.get("matricule"), (String) m.get("dirigeant"),
                (Boolean) m.get("assujetti_taxes"), (String) m.get("localisation"),
                (String) m.get("telephone2"));
    }

    /** Seul un dossier soumis se valide : ni un brouillon, ni un dossier deja tranche. */
    private static void exigerEnAttente(Map<String, Object> dossier) {
        String statut = String.valueOf(dossier.get("statut"));
        if (!EN_ATTENTE_VERIFICATION.equals(statut) && !"EN_VERIFICATION".equals(statut)) {
            throw ApiException.badRequest(
                    "Dossier non soumis a la verification (statut : " + statut + ").");
        }
    }

    private String statutVisage(UUID dossierId) {
        List<Map<String, Object>> f = jdbc.queryForList(
                "SELECT verification_status FROM face_captures WHERE dossier_id = ? "
                        + "ORDER BY created_at DESC LIMIT 1", dossierId);
        if (f.isEmpty()) {
            throw ApiException.badRequest("Aucune capture faciale pour ce dossier.");
        }
        return String.valueOf(f.get(0).get("verification_status"));
    }

    private static String normaliserStatut(String status) {
        if (status == null || status.isBlank()) return null;
        String s = status.trim().toUpperCase();
        return switch (s) {
            case "EN_ATTENTE", "VALIDE", "REJETE" -> s;
            default -> throw ApiException.badRequest("Statut de verification invalide.");
        };
    }
}
