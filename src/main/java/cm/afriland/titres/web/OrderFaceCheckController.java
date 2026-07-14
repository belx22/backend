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
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.Permission;
import cm.afriland.titres.support.FaceMatcher;
import cm.afriland.titres.support.FileStorageService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Controle facial rattache a un ORDRE — « est-ce bien le titulaire qui passe cet ordre ? »
 *
 * <p><b>Non bloquant par construction</b> : l'ordre est deja soumis quand ce
 * controle intervient. Le serveur (et lui seul) compare l'empreinte du jour a
 * celle enregistree a l'ouverture du compte, puis <b>consigne</b> le resultat.
 * Un visage different ne refuse pas l'ordre : il le signale au back-office, qui
 * tranche (revue humaine).</p>
 */
@RestController
@RequestMapping("/api/v1")
public class OrderFaceCheckController {

    /** Taille max de la photo d'ordre (base64 decode), 6 Mo. */
    private static final int MAX_BYTES = 6 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final FileStorageService storage;
    private final AuditService audit;

    public OrderFaceCheckController(JdbcTemplate jdbc, FileStorageService storage,
                                    AuditService audit) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.audit = audit;
    }

    record FaceCheckRequest(
            @NotBlank String imageBase64,
            Double livenessScore,
            @Pattern(regexp = "BLINK|TURN_HEAD|SMILE") String challengeType,
            /** Empreinte 128-d calculee par le navigateur ; le SERVEUR seul juge. */
            List<Double> descriptor) {
    }

    // ───────────────── Client — apres soumission de son ordre ─────────────────

    /**
     * Depose la photo prise juste apres la soumission de l'ordre et lance la
     * comparaison serveur. Repond toujours 200 (l'ordre reste soumis) : le corps
     * indique le resultat, jamais un refus.
     */
    @PostMapping("/orders/{orderId}/face-check")
    public Map<String, Object> check(AuthUser user, @PathVariable UUID orderId,
                                     @Valid @RequestBody FaceCheckRequest req) {
        UUID proprietaire = jdbc.query(
                        "SELECT client_id FROM orders WHERE id = ?",
                        (rs, n) -> rs.getObject("client_id", UUID.class), orderId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Ordre introuvable."));
        if (!proprietaire.equals(user.id())) {
            throw ApiException.forbidden("Cet ordre ne vous appartient pas.");
        }

        byte[] image = decodeImage(req.imageBase64());
        FileStorageService.Stored img = storage.store("faces", "jpg", image);

        // Empreinte de REFERENCE : celle capturee a l'ouverture du compte.
        String reference = jdbc.query(
                        "SELECT f.descriptor FROM face_captures f "
                                + "JOIN registration_dossiers d ON d.id = f.dossier_id "
                                + "WHERE d.user_id = ? AND f.descriptor IS NOT NULL "
                                + "ORDER BY f.created_at DESC LIMIT 1",
                        (rs, n) -> rs.getString("descriptor"), user.id())
                .stream().findFirst().orElse(null);

        FaceMatcher.Resultat r = FaceMatcher.comparer(reference, req.descriptor());

        jdbc.update("INSERT INTO order_face_checks (order_id, user_id, nom_fichier, chemin, "
                        + "descriptor, sha256, liveness_score, challenge_type, distance, matched, "
                        + "match_status) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                orderId, user.id(), "ordre-" + orderId + ".jpg", img.relativePath(),
                FaceMatcher.encode(req.descriptor()), img.sha256(),
                req.livenessScore(), req.challengeType(),
                r.distance(), r.matched(), r.status());

        audit.log(user.id().toString(), "CONTROLE_FACIAL_ORDRE",
                r.matched() ? AuditService.SUCCES : AuditService.ECHEC, orderId.toString(), "—");

        // Toujours 200 : le controle informe, il ne refuse pas l'ordre.
        return Map.of("matchStatus", r.status(), "matched", r.matched(),
                "distance", r.distance() == null ? -1 : r.distance(),
                "seuil", FaceMatcher.SEUIL);
    }

    // ───────────────── Back-office — comparaison des 2 photos ────────────────

    /**
     * Les DEUX photos cote a cote : celle de l'ouverture de compte et celle de
     * l'ordre, avec la distance calculee. Permet a l'agent de trancher.
     */
    @GetMapping("/admin/orders/{orderId}/face-check")
    public Map<String, Object> faceCheck(AuthUser user, @PathVariable UUID orderId) {
        user.require(Permission.CLIENT_MANAGE);

        List<Map<String, Object>> checks = jdbc.queryForList(
                "SELECT id, user_id, chemin, distance, matched, match_status, liveness_score, "
                        + "verification_status FROM order_face_checks "
                        + "WHERE order_id = ? ORDER BY created_at DESC LIMIT 1", orderId);
        if (checks.isEmpty()) {
            throw ApiException.notFound("Aucun controle facial pour cet ordre.");
        }
        Map<String, Object> c = checks.get(0);

        // Photo de reference (ouverture de compte) du meme client.
        List<Map<String, Object>> ref = jdbc.queryForList(
                "SELECT f.chemin FROM face_captures f "
                        + "JOIN registration_dossiers d ON d.id = f.dossier_id "
                        + "WHERE d.user_id = ? ORDER BY f.created_at DESC LIMIT 1",
                c.get("user_id"));

        return Map.ofEntries(
                Map.entry("orderId", orderId),
                Map.entry("photoOrdre", dataUrl((String) c.get("chemin"))),
                Map.entry("photoOuverture",
                        ref.isEmpty() ? "" : dataUrl((String) ref.get(0).get("chemin"))),
                Map.entry("distance", c.get("distance") == null ? -1 : c.get("distance")),
                Map.entry("seuil", FaceMatcher.SEUIL),
                Map.entry("matchStatus", c.get("match_status")),
                Map.entry("livenessScore",
                        c.get("liveness_score") == null ? 0 : c.get("liveness_score")),
                Map.entry("verificationStatus", c.get("verification_status")));
    }

    /** Revue humaine : l'agent valide ou rejette le controle facial de l'ordre. */
    @PostMapping("/admin/orders/{orderId}/face-check/verify")
    public Map<String, Object> verify(AuthUser user, @PathVariable UUID orderId,
                                      @Valid @RequestBody VerifyRequest req) {
        user.require(Permission.CLIENT_MANAGE);
        int updated = jdbc.update(
                "UPDATE order_face_checks SET verification_status = ?, verified_by = ?, "
                        + "verified_at = now() WHERE order_id = ?", req.status(), user.id(), orderId);
        if (updated == 0) {
            throw ApiException.notFound("Aucun controle facial pour cet ordre.");
        }
        audit.log(user.id().toString(), "REVUE_FACIALE_ORDRE", AuditService.SUCCES,
                orderId.toString(), "—");
        return Map.of("orderId", orderId, "verificationStatus", req.status());
    }

    record VerifyRequest(
            @Pattern(regexp = "VALIDE|REJETE", message = "statut invalide (VALIDE|REJETE)")
            String status) {
    }

    // ─────────────────────────── Utilitaires ────────────────────────────────

    private String dataUrl(String chemin) {
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(storage.read(chemin));
    }

    private static byte[] decodeImage(String base64) {
        String data = base64;
        int comma = data.indexOf(',');
        if (data.startsWith("data:") && comma > 0) {
            data = data.substring(comma + 1);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Image invalide (base64 attendu).");
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw ApiException.badRequest("Image vide ou trop volumineuse.");
        }
        return bytes;
    }
}
