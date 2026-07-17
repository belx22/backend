package cm.afriland.titres.web;

import java.util.ArrayList;
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
import cm.afriland.titres.support.OrderFaceCheckService;

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

    private final JdbcTemplate jdbc;
    private final OrderFaceCheckService faces;
    private final AuditService audit;

    public OrderFaceCheckController(JdbcTemplate jdbc, OrderFaceCheckService faces,
                                    AuditService audit) {
        this.jdbc = jdbc;
        this.faces = faces;
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

        FaceMatcher.Resultat r = faces.enregistrer(orderId, user.id(),
                new OrderFaceCheckService.Capture(req.imageBase64(), req.livenessScore(),
                        req.challengeType(), req.descriptor()));

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

        return Map.ofEntries(
                Map.entry("orderId", orderId),
                Map.entry("photoOrdre", faces.dataUrl((String) c.get("chemin"))),
                Map.entry("photoOuverture",
                        faces.dataUrl(faces.cheminPhotoOuverture((UUID) c.get("user_id")))),
                Map.entry("distance", c.get("distance") == null ? -1 : c.get("distance")),
                Map.entry("seuil", FaceMatcher.SEUIL),
                Map.entry("matchStatus", c.get("match_status")),
                Map.entry("livenessScore",
                        c.get("liveness_score") == null ? 0 : c.get("liveness_score")),
                Map.entry("verificationStatus", c.get("verification_status")));
    }

    /**
     * {@code GET /admin/orders/:id/signatures} — vue « compte joint » de l'ordre :
     * <b>toutes</b> les personnes liees au compte, qui a valide et qui est encore
     * en attente, avec pour chacune sa photo d'OUVERTURE et sa photo au moment de
     * sa validation, plus la distance calculee par le serveur.
     *
     * <p>L'emetteur figure en tete (role EMETTEUR) : il n'a pas de ligne dans
     * {@code order_signatures} — il consent par sa soumission — mais l'agent doit
     * malgre tout voir son visage et sa signature.</p>
     */
    @GetMapping("/admin/orders/{orderId}/signatures")
    public Map<String, Object> signatures(AuthUser user, @PathVariable UUID orderId) {
        user.require(Permission.CLIENT_MANAGE);

        Map<String, Object> order = jdbc.queryForList(
                        "SELECT o.id, o.reference, o.status, o.client_id, o.signature_data, "
                                + "o.signatures_bypassed_at, o.signatures_bypass_motif, "
                                + "b.nom AS bypass_nom, b.prenom AS bypass_prenom "
                                + "FROM orders o LEFT JOIN users b ON b.id = o.signatures_bypassed_by "
                                + "WHERE o.id = ?", orderId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Ordre introuvable."));
        UUID account = (UUID) order.get("client_id");

        // Toutes les personnes du compte : titulaire principal + co-signataires.
        List<Map<String, Object>> personnes = jdbc.queryForList(
                "SELECT u.id, u.nom, u.prenom, u.email, u.statut, "
                        + "s.status AS sig_status, s.decided_at, s.expires_at, s.signature_data "
                        + "FROM users u "
                        + "LEFT JOIN order_signatures s ON s.signatory_id = u.id AND s.order_id = ? "
                        + "WHERE u.id = ? OR u.account_holder_id = ? "
                        + "ORDER BY (u.account_holder_id IS NULL) DESC, u.nom, u.prenom",
                orderId, account, account);

        List<Map<String, Object>> vue = new ArrayList<>();
        for (Map<String, Object> p : personnes) {
            UUID pid = (UUID) p.get("id");
            boolean emetteur = p.get("sig_status") == null;

            Map<String, Object> check = jdbc.queryForList(
                            "SELECT chemin, distance, match_status, liveness_score, verification_status "
                                    + "FROM order_face_checks WHERE order_id = ? AND user_id = ?",
                            orderId, pid)
                    .stream().findFirst().orElse(Map.of());

            Map<String, Object> ligne = new java.util.HashMap<>();
            ligne.put("userId", pid);
            ligne.put("nom", nomComplet(p.get("nom"), p.get("prenom")));
            ligne.put("email", p.get("email"));
            // L'emetteur n'a pas de demande de signature : il a consenti en soumettant.
            ligne.put("role", emetteur ? "EMETTEUR" : "COSIGNATAIRE");
            ligne.put("statut", emetteur ? "EMETTEUR" : p.get("sig_status"));
            ligne.put("decidedAt", p.get("decided_at"));
            ligne.put("expiresAt", p.get("expires_at"));
            ligne.put("signature", emetteur ? order.get("signature_data") : p.get("signature_data"));
            ligne.put("photoOuverture", faces.dataUrl(faces.cheminPhotoOuverture(pid)));
            ligne.put("photoSoumission", faces.dataUrl((String) check.get("chemin")));
            ligne.put("distance", check.get("distance") == null ? -1 : check.get("distance"));
            ligne.put("matchStatus",
                    check.get("match_status") == null ? "NON_COMPARABLE" : check.get("match_status"));
            ligne.put("livenessScore",
                    check.get("liveness_score") == null ? 0 : check.get("liveness_score"));
            ligne.put("verificationStatus", check.get("verification_status"));
            vue.add(ligne);
        }

        long attendues = vue.stream().filter(l -> !"EMETTEUR".equals(l.get("statut"))).count();
        long obtenues = vue.stream().filter(l -> "SIGNED".equals(l.get("statut"))).count();

        Map<String, Object> out = new java.util.HashMap<>();
        out.put("orderId", orderId);
        out.put("reference", order.get("reference"));
        out.put("status", order.get("status"));
        // joint = le compte porte plus d'une personne ; sinon la vue n'a qu'une ligne.
        out.put("joint", vue.size() > 1);
        out.put("signaturesAttendues", attendues);
        out.put("signaturesObtenues", obtenues);
        out.put("seuil", FaceMatcher.SEUIL);
        out.put("personnes", vue);
        out.put("bypassedAt", order.get("signatures_bypassed_at"));
        out.put("bypassMotif", order.get("signatures_bypass_motif"));
        out.put("bypassPar", order.get("bypass_nom") == null ? null
                : nomComplet(order.get("bypass_nom"), order.get("bypass_prenom")));
        return out;
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

    private static String nomComplet(Object nom, Object prenom) {
        String n = nom == null ? "" : nom.toString();
        String p = prenom == null ? "" : prenom.toString();
        return p.isBlank() ? n : (n + " " + p).trim();
    }
}
