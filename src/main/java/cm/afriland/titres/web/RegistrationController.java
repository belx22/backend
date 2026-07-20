package cm.afriland.titres.web;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import cm.afriland.titres.notif.EmailService;
import cm.afriland.titres.notif.NotificationService;
import cm.afriland.titres.dto.AuthResponse;
import cm.afriland.titres.dto.UserRow;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.security.AuthSessionService;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;
import cm.afriland.titres.security.PasswordService;
import cm.afriland.titres.security.RateLimiter;
import cm.afriland.titres.security.Rbac;
import cm.afriland.titres.support.ClientsFbRepository;
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
    private static final String STATUT_EN_ATTENTE = "EN_ATTENTE_VERIFICATION";
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
    private final ClientsFbRepository referentiel;
    private final NotificationService notifications;
    private final EmailService emails;

    public RegistrationController(JdbcTemplate jdbc, PasswordService password,
                                  AuthSessionService session, FileStorageService storage,
                                  RateLimiter rateLimiter, AuditService audit,
                                  ClientsFbRepository referentiel,
                                  NotificationService notifications, EmailService emails) {
        this.jdbc = jdbc;
        this.password = password;
        this.session = session;
        this.storage = storage;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.referentiel = referentiel;
        this.notifications = notifications;
        this.emails = emails;
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
            // Un compte JOINT soumet chaque ordre a la cosignature (signature +
            // visage) des autres titulaires, rattaches par le back-office lors de
            // la validation du dossier. Les autres valeurs de l'Annexe 1
            // (INDIVISION, DEMEMBRE) restent saisies par le back-office.
            @Pattern(regexp = "INDIVIDUEL|JOINT", message = "type de compte invalide (INDIVIDUEL|JOINT)")
            String typeCompte,
            // Compte especes = 23 chiffres (RIB) : code banque 10005 + le numero tel
            // qu'il figure dans la base clients — agence 5 + compte 11 + cle 2.
            // Ex. « 00090 56010090000 48 » -> « 10005 00090 56010090000 48 ».
            @NotBlank @Pattern(regexp = "\\d{23}", message = "le compte especes doit comporter 23 chiffres")
            String compteEspeces) {
    }

    /** {@code clientConnu} = le numero de compte figurait dans la base clients importee. */
    record RegisterResponse(UUID dossierId, UUID titulaireId, AuthResponse auth,
                            boolean clientConnu) {
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
        String compteEspeces = req.compteEspeces().trim();
        String typeCompte = req.typeCompte() == null ? "INDIVIDUEL" : req.typeCompte();

        // Unicite du numero de compte especes : un meme numero ne peut appartenir
        // qu'a UN titulaire, SAUF pour un compte JOINT — ou les co-titulaires
        // partagent legitimement le meme compte. En compte individuel, un doublon
        // est refuse et signale au prospect.
        if (!"JOINT".equals(typeCompte)) {
            Long dejaPris = jdbc.queryForObject(
                    "SELECT count(*) FROM users WHERE compte_especes = ? "
                            + "AND role IN ('CLIENT_PP', 'CLIENT_PM')", Long.class, compteEspeces);
            if (dejaPris != null && dejaPris > 0) {
                throw ApiException.conflict("Ce numéro de compte existe déjà.");
            }
        }

        // ── Rapprochement avec le referentiel de la banque ────────────────────
        // Le numero de compte est la clef : s'il figure dans la « BASE CLIENTS »
        // importee, le prospect est DEJA client — on reprend ses informations et
        // son compte de depot. Sinon c'est un NOUVEAU CLIENT : il n'a pas de
        // compte-titres, on previent l'administrateur et on l'invite en agence.
        // NB : la « categorie » du fichier (Personnes physiques, Societe de Bourse, …)
        // n'est PAS celle de users (QUALIFIE / NON_QUALIFIE) — on ne la recopie pas.
        Optional<ClientsFbRepository.ClientFb> connu = referentiel.parCompte(compteEspeces);
        // Le compte de depot n'est JAMAIS montre au client : il ne sert qu'au back-office.
        // Il est UNIQUE en base : si un premier titulaire l'a deja pris (compte joint,
        // ou re-inscription), on ne le rattache pas au second — sans cela l'inscription
        // echouerait sur une violation de contrainte. Le back-office tranchera.
        String compteDepot = connu.map(ClientsFbRepository.ClientFb::compteDepot)
                .filter(c -> !compteTitresDejaPris(c))
                .orElse(null);
        String telephone = trimOrNull(req.telephone());
        if (telephone == null) {
            telephone = connu.map(ClientsFbRepository.ClientFb::telephone1).orElse(null);
        }

        UUID userId = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, role, nom, prenom, telephone, "
                        + "compte_especes, compte_titres, solde, categorie, must_change_password) "
                        + "VALUES (?,?,?,?,?,?,?,?, 0, 'NON_QUALIFIE', FALSE) RETURNING id",
                UUID.class, email, hash,
                // Le role suit la qualite declaree des l'inscription. La validation
                // du dossier le reaffirme (AdminRegistrationController) : le poser
                // ici evite qu'une personne morale vive en CLIENT_PP entre-temps.
                "PM".equals(typePersonne) ? Rbac.CLIENT_PM : ROLE_CLIENT_PP,
                req.nom().trim(), trimOrNull(req.prenom()), telephone,
                compteEspeces, compteDepot);

        UUID dossierId = jdbc.queryForObject(
                "INSERT INTO registration_dossiers (user_id, type_personne, type_compte, "
                        + "compte_especes, statut, client_fb_id, client_connu) "
                        + "VALUES (?,?,?,?, '" + STATUT_BROUILLON + "', ?,?) RETURNING id",
                UUID.class, userId, typePersonne, typeCompte, compteEspeces,
                connu.map(ClientsFbRepository.ClientFb::id).orElse(null), connu.isPresent());

        // Titulaire principal pre-rempli depuis le compte (co-titulaires ajoutes plus tard).
        UUID titulaireId = jdbc.queryForObject(
                "INSERT INTO dossier_titulaires (dossier_id, role_titulaire, nom, prenom, ordre) "
                        + "VALUES (?, 'PRINCIPAL', ?, ?, 0) RETURNING id",
                UUID.class, dossierId, req.nom().trim(), trimOrNull(req.prenom()));

        if (connu.isEmpty()) {
            signalerNouveauClient(userId, dossierId, email, req.nom().trim(), compteEspeces);
        }

        UserRow user = jdbc.queryForObject(SELECT_USER_BY_ID, UserRow.MAPPER, userId);
        // Jeton RESTREINT au parcours d'inscription, sans jeton de rafraichissement :
        // le prospect televerse son dossier maintenant, mais n'ouvre pas de session
        // persistante (impossible d'etre connecte sans avoir passe l'OTP).
        AuthResponse auth = session.issueRegistrationAccess(user);
        audit.log(userId.toString(), connu.isPresent() ? "INSCRIPTION" : "INSCRIPTION_NOUVEAU_CLIENT",
                AuditService.SUCCES, dossierId.toString(), ip);

        return new RegisterResponse(dossierId, titulaireId, auth, connu.isPresent());
    }

    /**
     * Compte inconnu du referentiel : l'administrateur est prevenu et le prospect
     * invite a se presenter en agence — c'est la seule voie pour lui ouvrir un
     * compte-titres, sans lequel ses ordres ne pourront pas etre executes.
     */
    private void signalerNouveauClient(UUID userId, UUID dossierId, String email, String nom,
                                       String compteEspeces) {
        notifications.notifyRoles(List.of("ADMIN", "AGENT"), "WARN",
                "Nouveau client a orienter en agence",
                nom + " s'est inscrit avec le compte " + compteEspeces
                        + ", inconnu de la base clients. Aucun compte-titres ne lui est rattache.",
                dossierId.toString());

        String corps = """
                <p>Bonjour %s,</p>
                <p>Votre inscription sur la plateforme Titres d'Afriland First Bank a bien ete
                enregistree.</p>
                <p>Le compte <b>%s</b> que vous avez renseigne ne figure pas encore dans notre
                base clients. Pour ouvrir votre <b>compte-titres</b> et pouvoir effectuer des
                operations, nous vous invitons a <b>vous presenter dans votre agence</b> muni
                d'une piece d'identite.</p>
                <p>Cordialement,<br/>Afriland First Bank</p>
                """.formatted(nom, cm.afriland.titres.notif.CredentialDelivery.maskCompte(compteEspeces));
        emails.dispatchOne(email, "Ouverture de votre compte-titres — presentez-vous en agence",
                corps);

        notifications.notify(userId, "WARN", "Compte-titres a ouvrir",
                "Presentez-vous en agence pour ouvrir votre compte-titres et pouvoir passer des ordres.",
                dossierId.toString());
    }


    /** {@code users.compte_titres} est UNIQUE : un compte de depot ne sert qu'une fois. */
    private boolean compteTitresDejaPris(String compteDepot) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM users WHERE compte_titres = ?",
                Long.class, compteDepot);
        return n != null && n > 0;
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
        // Map.of() REFUSE les valeurs nulles : depuis que le type est facultatif
        // (detection OCR devenue indicative), il peut l'etre. On construit donc une
        // map tolerante au null plutot que de faire echouer le depot.
        Map<String, Object> reponse = new LinkedHashMap<>();
        reponse.put("pieceId", pieceId);
        reponse.put("type", req.type());          // peut etre null : purement indicatif
        reponse.put("cote", req.cote());
        reponse.put("verificationStatus", "EN_ATTENTE");
        return reponse;
    }

    // ────────────────────── Etape finale — soumission ───────────────────────

    /**
     * Soumet le dossier a la verification du back-office
     * ({@code BROUILLON} → {@code EN_ATTENTE_VERIFICATION}).
     *
     * <p>C'est le maillon qui relie le parcours d'inscription au back-office :
     * sans lui le dossier resterait indefiniment en brouillon, jamais valide,
     * et le prospect n'obtiendrait jamais de compte-titres. La capture faciale
     * et au moins une piece d'identite sont exigees — ce sont precisement les
     * deux elements que l'agent doit controler.</p>
     *
     * <p>Idempotent : re-soumettre un dossier deja soumis renvoie son etat sans
     * erreur (le client peut avoir perdu la reponse reseau).</p>
     */
    @PostMapping("/dossiers/{id}/submit")
    public Map<String, Object> submit(AuthUser user, @PathVariable UUID id, ClientIp clientIp) {
        Map<String, Object> dossier = loadOwnedDossier(id, user.id());
        String statut = String.valueOf(dossier.get("statut"));

        if (STATUT_EN_ATTENTE.equals(statut)) {
            return Map.of("dossierId", id, "statut", statut, "dejaSoumis", true);
        }
        if (!STATUT_BROUILLON.equals(statut)) {
            throw ApiException.badRequest(
                    "Ce dossier n'est plus modifiable (statut : " + statut + ").");
        }
        if (compte(id, "SELECT count(*) FROM face_captures WHERE dossier_id = ?") == 0) {
            throw ApiException.badRequest(
                    "Capture faciale manquante : reprenez la photo avant de soumettre.");
        }
        if (compte(id, "SELECT count(*) FROM justificatifs WHERE dossier_id = ? "
                + "AND type = 'PIECE_IDENTITE'") == 0) {
            throw ApiException.badRequest(
                    "Piece d'identite manquante : deposez-la avant de soumettre.");
        }

        jdbc.update("UPDATE registration_dossiers SET statut = ?, submitted_at = now(), "
                + "updated_at = now() WHERE id = ?", STATUT_EN_ATTENTE, id);
        audit.log(user.id().toString(), "DOSSIER_SOUMIS", AuditService.SUCCES, id.toString(),
                clientIp.value());

        return Map.of("dossierId", id, "statut", STATUT_EN_ATTENTE, "dejaSoumis", false);
    }

    private long compte(UUID dossierId, String sql) {
        Long n = jdbc.queryForObject(sql, Long.class, dossierId);
        return n == null ? 0 : n;
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
