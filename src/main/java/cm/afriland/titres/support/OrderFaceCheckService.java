package cm.afriland.titres.support;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import cm.afriland.titres.error.ApiException;

/**
 * Enregistrement d'un controle facial rattache a un ordre — logique partagee.
 *
 * <p>Deux points d'entree l'utilisent : la photo prise par l'EMETTEUR juste
 * apres sa soumission ({@code POST /orders/:id/face-check}) et celle prise par
 * chaque CO-SIGNATAIRE d'un compte joint au moment ou il valide
 * ({@code POST /orders/:id/cosign}). Le traitement est identique dans les deux
 * cas — stocker l'image hors webroot, puis comparer cote SERVEUR l'empreinte du
 * jour a celle capturee a l'ouverture du compte de <b>cette</b> personne — d'ou
 * la mise en commun ici plutot qu'une duplication entre les deux controleurs.</p>
 *
 * <p><b>Jamais bloquant</b> : un visage different ne refuse pas l'operation, il
 * la signale au back-office qui tranche (revue humaine).</p>
 */
@Service
public class OrderFaceCheckService {

    /** Taille max d'une photo (apres decodage base64), 6 Mo. */
    private static final int MAX_BYTES = 6 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final FileStorageService storage;

    public OrderFaceCheckService(JdbcTemplate jdbc, FileStorageService storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    /** Photo du jour presentee par une personne pour un ordre. */
    public record Capture(String imageBase64, Double livenessScore, String challengeType,
                          List<Double> descriptor) {
    }

    /**
     * Stocke la photo et consigne la comparaison avec l'empreinte d'ouverture de
     * {@code userId}. Une seule ligne par (ordre, personne) : une nouvelle
     * capture remplace la precedente (l'index unique de V32 le garantit).
     *
     * @return le resultat de la comparaison serveur.
     */
    public FaceMatcher.Resultat enregistrer(UUID orderId, UUID userId, Capture capture) {
        byte[] image = decodeImage(capture.imageBase64());
        FileStorageService.Stored img = storage.store("faces", "jpg", image);

        FaceMatcher.Resultat r = FaceMatcher.comparer(empreinteOuverture(userId), capture.descriptor());

        jdbc.update("INSERT INTO order_face_checks (order_id, user_id, nom_fichier, chemin, "
                        + "descriptor, sha256, liveness_score, challenge_type, distance, matched, "
                        + "match_status) VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (order_id, user_id) DO UPDATE SET "
                        + "nom_fichier = EXCLUDED.nom_fichier, chemin = EXCLUDED.chemin, "
                        + "descriptor = EXCLUDED.descriptor, sha256 = EXCLUDED.sha256, "
                        + "liveness_score = EXCLUDED.liveness_score, "
                        + "challenge_type = EXCLUDED.challenge_type, distance = EXCLUDED.distance, "
                        + "matched = EXCLUDED.matched, match_status = EXCLUDED.match_status, "
                        + "verification_status = 'EN_ATTENTE', verified_by = NULL, verified_at = NULL, "
                        + "created_at = now()",
                orderId, userId, "ordre-" + orderId + "-" + userId + ".jpg", img.relativePath(),
                FaceMatcher.encode(capture.descriptor()), img.sha256(),
                capture.livenessScore(), capture.challengeType(),
                r.distance(), r.matched(), r.status());
        return r;
    }

    /**
     * Empreinte de reference capturee a l'ouverture du compte de cette personne.
     *
     * <p>Chaque co-signataire d'un compte joint a passe sa propre inscription :
     * on cherche donc le dossier dont il est l'utilisateur, jamais celui du
     * titulaire principal — sans quoi on comparerait le visage du co-signataire
     * a celui d'une autre personne.</p>
     */
    public String empreinteOuverture(UUID userId) {
        return jdbc.query(
                        "SELECT f.descriptor FROM face_captures f "
                                + "JOIN registration_dossiers d ON d.id = f.dossier_id "
                                + "WHERE d.user_id = ? AND f.descriptor IS NOT NULL "
                                + "ORDER BY f.created_at DESC LIMIT 1",
                        (rs, n) -> rs.getString("descriptor"), userId)
                .stream().findFirst().orElse(null);
    }

    /** Chemin de la photo d'ouverture de compte de cette personne ({@code null} si aucune). */
    public String cheminPhotoOuverture(UUID userId) {
        return jdbc.query(
                        "SELECT f.chemin FROM face_captures f "
                                + "JOIN registration_dossiers d ON d.id = f.dossier_id "
                                + "WHERE d.user_id = ? ORDER BY f.created_at DESC LIMIT 1",
                        (rs, n) -> rs.getString("chemin"), userId)
                .stream().findFirst().orElse(null);
    }

    /** Image encodee en {@code data:} URL, ou chaine vide si le chemin est absent. */
    public String dataUrl(String chemin) {
        if (chemin == null || chemin.isBlank()) {
            return "";
        }
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(storage.read(chemin));
    }

    /** Decode une image base64 (avec ou sans prefixe {@code data:}) et borne sa taille. */
    public static byte[] decodeImage(String base64) {
        String data = base64 == null ? "" : base64;
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
