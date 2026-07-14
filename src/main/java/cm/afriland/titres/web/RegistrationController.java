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
import cm.afriland.titres.dto.AuthResponse;
import cm.afriland.titres.dto.UserRow;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.security.AuthSessionService;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;
import cm.afriland.titres.security.PasswordService;
import cm.afriland.titres.security.RateLimiter;
import cm.afriland.titres.support.FaceMatcher;
import cm.afriland.titres.support.FileStorageService;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Module d'auto-inscription — ouverture de compte-titres en ligne (Annexe 1).
 *
 * <p>Le prospect cree son compte ({@code POST /register}), obtient une session,
 * puis pilote son dossier (formulaire, capture faciale, convention). Le dossier
 * reste en {@code BROUILLON} jusqu'a soumission, et n'alimente les tables clients
 * qu'apres validation par le back-office.</p>
 */
@RestController
@RequestMapping("/api/v1/registration")
public class RegistrationController {

    private static final String ROLE_CLIENT_PP = "CLIENT_PP";
    private static final String SELECT_USER_BY_ID =
            "SELECT " + UserRow.COLUMNS + " FROM users WHERE id = ?";
    private static final String STATUT_BROUILLON = "BROUILLON";
    /** Taille max d'une image de capture faciale (base64 decode), 6 Mo. */
    private static final int MAX_FACE_BYTES = 6 * 1024 * 1024;
    /** Score de vivacite minimal accepte cote serveur. */
    private static final double LIVENESS_MIN = 0.5;

    private final JdbcTemplate jdbc;
    private final PasswordService password;
    private final AuthSessionService session;
    private final FileStorageService storage;
    private final RateLimiter rateLimiter;
    private final AuditService audit;

    public RegistrationController(JdbcTemplate jdbc, PasswordService password,
                                  AuthSessionService session, FileStorageService storage,
                                  RateLimiter rateLimiter, AuditService audit) {
        this.jdbc = jdbc;
        this.password = password;
        this.session = session;
        this.storage = storage;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
    }

    // ─────────────────────────────── DTO ────────────────────────────────────

    record RegisterRequest(
            @Email(message = "format d'e-mail invalide") @NotNull String email,
            @NotBlank @Size(min = 8, max = 128, message = "mot de passe de 8 a 128 caracteres")
            String password,
            @NotBlank(message = "le nom est requis") String nom,
            String prenom,
            String telephone,
            @Pattern(regexp = "PP|PM", message = "type de personne invalide (PP|PM)") String typePersonne,
            // Compte especes = 22 chiffres, format « 10005 0001 xxxxxxxxxxx xx »
            // (banque 5 + guichet 4 + compte 11 + cle 2). Stocke sans espaces.
            @NotBlank @Pattern(regexp = "\\d{22}", message = "le compte especes doit comporter 22 chiffres")
            String compteEspeces) {
    }

    record RegisterResponse(UUID dossierId, UUID titulaireId, AuthResponse auth) {
    }

    record FaceCaptureRequest(
            /** Image encodee base64 (data URL « data:image/…;base64,… » ou base64 brut). */
            @NotBlank String imageBase64,
            /** JSON des 468 points 3D (rejeu des marqueurs au back-office). */
            String landmarksJson,
            @NotNull Double livenessScore,
            boolean livenessPassed,
            @Pattern(regexp = "BLINK|TURN_HEAD|SMILE") String challengeType,
            Integer width,
            Integer height,
            /** Empreinte faciale 128-d — reference pour la comparaison a chaque ordre. */
            List<Double> descriptor) {
    }

    // ─────────────────────── Etape 1 — compte + dossier ─────────────────────

    /** Cree le compte utilisateur (CLIENT_PP) et un dossier BROUILLON, puis ouvre une session. */
    @PostMapping("/register")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest req, ClientIp clientIp,
                                     HttpServletResponse resp) {
        String ip = clientIp.value();
        if (!rateLimiter.check("register:" + ip)) {
            throw ApiException.tooManyRequests();
        }

        String email = req.email().trim().toLowerCase();
        Long exists = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Long.class, email);
        if (exists != null && exists > 0) {
            throw ApiException.conflict("Un compte existe deja pour cet e-mail.");
        }

        String typePersonne = req.typePersonne() == null ? "PP" : req.typePersonne();
        String hash = password.hash(req.password());

        UUID userId = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, role, nom, prenom, telephone, "
                        + "must_change_password) VALUES (?,?,?,?,?,?, FALSE) RETURNING id",
                UUID.class, email, hash, ROLE_CLIENT_PP,
                req.nom().trim(), trimOrNull(req.prenom()), trimOrNull(req.telephone()));

        UUID dossierId = jdbc.queryForObject(
                "INSERT INTO registration_dossiers (user_id, type_personne, compte_especes, statut) "
                        + "VALUES (?,?,?, '" + STATUT_BROUILLON + "') RETURNING id",
                UUID.class, userId, typePersonne, req.compteEspeces().trim());

        // Titulaire principal pre-rempli depuis le compte (co-titulaires ajoutes plus tard).
        UUID titulaireId = jdbc.queryForObject(
                "INSERT INTO dossier_titulaires (dossier_id, role_titulaire, nom, prenom, ordre) "
                        + "VALUES (?, 'PRINCIPAL', ?, ?, 0) RETURNING id",
                UUID.class, dossierId, req.nom().trim(), trimOrNull(req.prenom()));

        UserRow user = jdbc.queryForObject(SELECT_USER_BY_ID, UserRow.MAPPER, userId);
        AuthResponse auth = session.issueTokens(user, resp);
        audit.log(userId.toString(), "INSCRIPTION", AuditService.SUCCES, dossierId.toString(), ip);

        return new RegisterResponse(dossierId, titulaireId, auth);
    }

    /** Etat courant du dossier (reprise du wizard). Reserve a son proprietaire. */
    @GetMapping("/dossiers/{id}")
    public Map<String, Object> getDossier(AuthUser user, @PathVariable UUID id) {
        Map<String, Object> d = loadOwnedDossier(id, user.id());
        Long faces = jdbc.queryForObject(
                "SELECT count(*) FROM face_captures WHERE dossier_id = ?", Long.class, id);
        d.put("faceCaptured", faces != null && faces > 0);
        Long pieces = jdbc.queryForObject(
                "SELECT count(*) FROM justificatifs WHERE dossier_id = ? AND type = 'PIECE_IDENTITE'",
                Long.class, id);
        d.put("piecesIdentite", pieces == null ? 0 : pieces);
        return d;
    }

    // ───────────────────────── Etape 3 — capture faciale ────────────────────

    /**
     * Depose la capture faciale (image + repere 468 pts + vivacite). Stocke le
     * NOM et le CHEMIN du fichier en base. La vivacite est re-controlee cote
     * serveur : le front ne peut pas « forcer » une capture.
     */
    @PostMapping("/dossiers/{id}/face")
    public Map<String, Object> uploadFace(AuthUser user, @PathVariable UUID id,
                                          @Valid @RequestBody FaceCaptureRequest req) {
        loadOwnedDossier(id, user.id());

        if (!req.livenessPassed() || req.livenessScore() == null || req.livenessScore() < LIVENESS_MIN) {
            throw ApiException.badRequest(
                    "Verification de vivacite insuffisante : recommencez la capture faciale.");
        }
        if (req.challengeType() == null) {
            throw ApiException.badRequest("Challenge de vivacite manquant.");
        }

        byte[] image = decodeImage(req.imageBase64());
        FileStorageService.Stored img = storage.store("faces", "jpg", image);

        String landmarksPath = null;
        if (req.landmarksJson() != null && !req.landmarksJson().isBlank()) {
            FileStorageService.Stored lm =
                    storage.store("faces", "json", req.landmarksJson().getBytes());
            landmarksPath = lm.relativePath();
        }

        UUID titulaireId = principalTitulaire(id);
        String nomFichier = "capture-" + id + ".jpg";

        UUID faceId = jdbc.queryForObject(
                "INSERT INTO face_captures (dossier_id, titulaire_id, nom_fichier, chemin, "
                        + "landmarks_path, liveness_score, liveness_passed, challenge_type, "
                        + "capture_width, capture_height, sha256, descriptor) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                UUID.class, id, titulaireId, nomFichier, img.relativePath(), landmarksPath,
                req.livenessScore(), true, req.challengeType(), req.width(), req.height(),
                img.sha256(), FaceMatcher.encode(req.descriptor()));

        audit.log(user.id().toString(), "CAPTURE_FACIALE", AuditService.SUCCES, id.toString(), "—");
        return Map.of("faceId", faceId, "nomFichier", nomFichier, "verificationStatus", "EN_ATTENTE");
    }

    // ─────────────────────── Piece d'identite (CNI / passeport) ──────────────

    record PieceIdentiteRequest(
            @NotBlank String imageBase64,
            @Pattern(regexp = "RECTO|VERSO", message = "cote invalide (RECTO|VERSO)") String cote,
            /** Type reconnu par l'OCR, ou null : purement INDICATIF. La detection
             *  automatique refusait des pieces valables — c'est le back-office qui
             *  juge, en voyant l'image et le texte lu. */
            @Pattern(regexp = "CNI|PASSEPORT|CARTE_SEJOUR", message = "type de piece invalide")
            String type,
            /** Texte lu sur le document (OCR) — conserve pour le controle du back-office. */
            String texte,
            Integer nettete,
            Integer largeur,
            Integer hauteur) {
    }

    /**
     * Depose une piece d'identite (CNI recto/verso, carte de sejour, passeport).
     *
     * <p>Le navigateur a deja verifie la qualite de l'image et lu le document
     * (OCR) pour s'assurer que c'est bien une piece d'identite. Le serveur
     * conserve l'image ET le texte extrait : <b>l'autorite reste le back-office</b>,
     * qui voit le document et ce qui en a ete lu avant de valider le dossier.</p>
     */
    @PostMapping("/dossiers/{id}/piece-identite")
    public Map<String, Object> uploadPiece(AuthUser user, @PathVariable UUID id,
                                           @Valid @RequestBody PieceIdentiteRequest req) {
        loadOwnedDossier(id, user.id());

        byte[] image = decodeImage(req.imageBase64());
        FileStorageService.Stored fichier = storage.store("pieces", "jpg", image);
        UUID titulaireId = principalTitulaire(id);
        String nomFichier = req.type() + "-" + req.cote() + ".jpg";

        // Une seule piece par cote : un nouveau depot remplace le precedent.
        jdbc.update("DELETE FROM justificatifs WHERE dossier_id = ? AND type = 'PIECE_IDENTITE' "
                + "AND cote = ?", id, req.cote());

        UUID pieceId = jdbc.queryForObject(
                "INSERT INTO justificatifs (dossier_id, titulaire_id, type, nom_fichier, chemin, "
                        + "mime_type, taille_octets, sha256, document_type, cote, ocr_texte, "
                        + "nettete, largeur, hauteur) "
                        + "VALUES (?,?, 'PIECE_IDENTITE', ?,?, 'image/jpeg', ?,?,?,?,?,?,?,?) RETURNING id",
                UUID.class, id, titulaireId, nomFichier, fichier.relativePath(),
                fichier.size(), fichier.sha256(), req.type(), req.cote(),
                req.texte(), req.nettete(), req.largeur(), req.hauteur());

        audit.log(user.id().toString(), "DEPOT_PIECE_IDENTITE", AuditService.SUCCES,
                id.toString(), "—");
        return Map.of("pieceId", pieceId, "type", req.type(), "cote", req.cote(),
                "verificationStatus", "EN_ATTENTE");
    }

    // ───────────────────────── Convention (public) ──────────────────────────

    /** Convention courante (HTML + version) pour la langue demandee. */
    @GetMapping("/convention")
    public Map<String, Object> convention(@RequestParam(defaultValue = "FR") String langue) {
        String lang = "EN".equalsIgnoreCase(langue) ? "EN" : "FR";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, titre, contenu_html FROM convention_versions "
                        + "WHERE langue = ? AND is_current = TRUE", lang);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Aucune convention publiee pour cette langue.");
        }
        Map<String, Object> r = rows.get(0);
        return Map.of("version", r.get("version"), "titre", r.get("titre"),
                "contenuHtml", r.get("contenu_html"), "langue", lang);
    }

    // ─────────────────────────── Utilitaires ────────────────────────────────

    /** Charge le dossier en verifiant qu'il appartient bien a l'appelant (403 sinon). */
    private Map<String, Object> loadOwnedDossier(UUID dossierId, UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, user_id, type_personne, compte_especes, type_compte, statut, langue, "
                        + "convention_version FROM registration_dossiers WHERE id = ?", dossierId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Dossier introuvable.");
        }
        Map<String, Object> d = rows.get(0);
        if (!userId.equals(d.get("user_id"))) {
            throw ApiException.forbidden("Ce dossier ne vous appartient pas.");
        }
        return d;
    }

    private UUID principalTitulaire(UUID dossierId) {
        return jdbc.queryForObject(
                "SELECT id FROM dossier_titulaires WHERE dossier_id = ? AND role_titulaire = 'PRINCIPAL' "
                        + "ORDER BY ordre LIMIT 1", UUID.class, dossierId);
    }

    /** Decode une image base64 (data URL ou brut) en bornant la taille. */
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
        if (bytes.length == 0 || bytes.length > MAX_FACE_BYTES) {
            throw ApiException.badRequest("Image vide ou trop volumineuse.");
        }
        return bytes;
    }

    private static String trimOrNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
