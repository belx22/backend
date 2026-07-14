package cm.afriland.titres.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.OptionalAuthUser;
import cm.afriland.titres.security.Permission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Signatures des dirigeants apposees sur les documents generes par la
 * plateforme (avis d'opere, attestations).
 *
 * <p>Administration reservee ({@code CONFIG_MARCHE}). La LECTURE est ouverte aux
 * utilisateurs authentifies : le document est rendu cote navigateur, il doit
 * donc pouvoir recuperer les signatures a apposer. Seules les signatures
 * <b>actives</b> sont renvoyees a ce titre.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class SignatureDirigeantController {

    /** Taille max d'une image de signature (data URI), 512 Ko. */
    private static final int MAX_LONGUEUR = 512 * 1024;

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public SignatureDirigeantController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    record SignatureRequest(
            @NotBlank @Size(max = 120) String nom,
            @NotBlank @Size(max = 120) String fonction,
            @NotBlank String signatureData,
            Boolean actif,
            Integer ordre) {
    }

    // ─────────────────── Lecture (apposition sur les documents) ──────────────

    /** Signatures ACTIVES, dans l'ordre d'affichage — utilisees par les documents. */
    @GetMapping("/signatures-dirigeants")
    public List<Map<String, Object>> actives(OptionalAuthUser user) {
        if (!user.isPresent()) {
            throw ApiException.unauthorized("Authentification requise.");
        }
        // Alias camelCase : la reponse JSON est consommee telle quelle par le front.
        return jdbc.queryForList(
                "SELECT id, nom, fonction, signature_data AS \"signatureData\" "
                        + "FROM signatures_dirigeants WHERE actif = TRUE ORDER BY ordre, created_at");
    }

    // ─────────────────────────── Administration ─────────────────────────────

    /** Toutes les signatures (actives ou non) — espace d'administration. */
    @GetMapping("/admin/signatures-dirigeants")
    public List<Map<String, Object>> list(AuthUser user) {
        user.require(Permission.CONFIG_MARCHE);
        return jdbc.queryForList(
                "SELECT id, nom, fonction, signature_data AS \"signatureData\", actif, ordre, "
                        + "created_at AS \"createdAt\" FROM signatures_dirigeants "
                        + "ORDER BY ordre, created_at");
    }

    @PostMapping("/admin/signatures-dirigeants")
    public Map<String, Object> create(AuthUser user, @Valid @RequestBody SignatureRequest req) {
        user.require(Permission.CONFIG_MARCHE);
        String image = validerImage(req.signatureData());

        UUID id = jdbc.queryForObject(
                "INSERT INTO signatures_dirigeants (nom, fonction, signature_data, actif, ordre, "
                        + "created_by) VALUES (?,?,?,?,?,?) RETURNING id",
                UUID.class, req.nom().trim(), req.fonction().trim(), image,
                req.actif() == null || req.actif(), req.ordre() == null ? 0 : req.ordre(),
                user.id());

        audit.log(user.id().toString(), "AJOUT_SIGNATURE_DIRIGEANT", AuditService.SUCCES,
                req.nom().trim(), "—");
        return Map.of("id", id);
    }

    @PatchMapping("/admin/signatures-dirigeants/{id}")
    public Map<String, Object> update(AuthUser user, @PathVariable UUID id,
                                      @Valid @RequestBody SignatureRequest req) {
        user.require(Permission.CONFIG_MARCHE);
        String image = validerImage(req.signatureData());

        int n = jdbc.update(
                "UPDATE signatures_dirigeants SET nom = ?, fonction = ?, signature_data = ?, "
                        + "actif = ?, ordre = ?, updated_at = now() WHERE id = ?",
                req.nom().trim(), req.fonction().trim(), image,
                req.actif() == null || req.actif(), req.ordre() == null ? 0 : req.ordre(), id);
        if (n == 0) {
            throw ApiException.notFound("Signature introuvable.");
        }
        audit.log(user.id().toString(), "MODIF_SIGNATURE_DIRIGEANT", AuditService.SUCCES,
                req.nom().trim(), "—");
        return Map.of("id", id);
    }

    @DeleteMapping("/admin/signatures-dirigeants/{id}")
    public Map<String, Object> delete(AuthUser user, @PathVariable UUID id) {
        user.require(Permission.CONFIG_MARCHE);
        int n = jdbc.update("DELETE FROM signatures_dirigeants WHERE id = ?", id);
        if (n == 0) {
            throw ApiException.notFound("Signature introuvable.");
        }
        audit.log(user.id().toString(), "SUPPR_SIGNATURE_DIRIGEANT", AuditService.SUCCES,
                id.toString(), "—");
        return Map.of("deleted", true);
    }

    // ─────────────────────────── Utilitaires ────────────────────────────────

    /**
     * N'accepte qu'une image en data URI (png/jpeg/webp), bornee en taille.
     * Le document est rendu en HTML : une URL arbitraire y serait une porte
     * d'entree (exfiltration, XSS via src) et serait bloquee par la CSP.
     */
    private static String validerImage(String data) {
        String s = data == null ? "" : data.trim();
        if (!s.matches("^data:image/(png|jpe?g|webp);base64,[A-Za-z0-9+/=]+$")) {
            throw ApiException.badRequest(
                    "Image de signature invalide (PNG, JPEG ou WEBP attendu).");
        }
        if (s.length() > MAX_LONGUEUR) {
            throw ApiException.badRequest("Image de signature trop volumineuse (512 Ko max).");
        }
        return s;
    }
}
