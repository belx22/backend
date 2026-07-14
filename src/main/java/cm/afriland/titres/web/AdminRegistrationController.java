package cm.afriland.titres.web;

import java.util.Base64;
import java.util.List;
import java.util.Map;
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

/**
 * Back-office — verification des dossiers d'auto-inscription, en particulier la
 * <b>capture faciale</b> : l'agent rejoue les 468 reperes (marqueurs) et la vue
 * 3D avant de valider ou rejeter l'identite. Reserve a {@code CLIENT_MANAGE}.
 */
@RestController
@RequestMapping("/api/v1/admin/registrations")
public class AdminRegistrationController {

    private final JdbcTemplate jdbc;
    private final FileStorageService storage;
    private final AuditService audit;

    public AdminRegistrationController(JdbcTemplate jdbc, FileStorageService storage,
                                       AuditService audit) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.audit = audit;
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
                + "d.created_at, t.nom, t.prenom, "
                + "f.id AS face_id, f.liveness_score, f.verification_status, f.created_at AS face_at "
                + "FROM registration_dossiers d "
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

    private static String normaliserStatut(String status) {
        if (status == null || status.isBlank()) return null;
        String s = status.trim().toUpperCase();
        return switch (s) {
            case "EN_ATTENTE", "VALIDE", "REJETE" -> s;
            default -> throw ApiException.badRequest("Statut de verification invalide.");
        };
    }
}
