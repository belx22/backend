package cm.afriland.titres.web;

import java.time.OffsetDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.config.AppProperties;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.notif.EmailService;
import cm.afriland.titres.notif.NotificationService;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;
import cm.afriland.titres.security.Permission;
import cm.afriland.titres.support.Countries;
import cm.afriland.titres.support.PageResponse;
import cm.afriland.titres.support.Pagination;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Module 4 — Ordres de souscription (CSFT §6.2, §M2).
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private static final Set<String> ORDER_STATUSES = Set.of("SOUMIS", "EN_VERIFICATION",
            "EN_ATTENTE_ADJUDICATION", "TOTALEMENT_RETENU", "PARTIELLEMENT_RETENU", "NON_RETENU", "ANNULE");

    /** Statuts finaux : un ordre cloture ne peut plus changer de statut. */
    private static final Set<String> FINAL_STATUSES = Set.of("ANNULE", "TOTALEMENT_RETENU",
            "PARTIELLEMENT_RETENU", "NON_RETENU");

    /** Resultats d'adjudication : saisis par l'agent puis valides par le superviseur. */
    private static final Set<String> ADJUDICATION_RESULTS = Set.of("TOTALEMENT_RETENU",
            "PARTIELLEMENT_RETENU", "NON_RETENU");

    private static final String SELECT = "SELECT o.id, o.reference, o.emission_id, o.client_id, o.isin, "
            + "o.volume, o.montant, o.taux_soumis, o.status, o.compte_especes, o.compte_titres, o.canal, "
            + "o.montant_adjuge, o.taux_adjuge, o.volume_alloue, o.commentaire_resultat, o.motif_annulation, "
            + "o.notes, o.validated_by_agent, o.date_validation_agent, o.signature_data, o.signature_verified, "
            + "o.resultat_propose, o.resultat_propose_at, o.resultat_valide_at, "
            + "o.ip_soumission, o.date_soumission, o.updated_at, "
            + "e.code AS emission_code, e.nature AS emission_nature, e.pays_code AS emission_pays, "
            + "u.nom AS client_nom, u.prenom AS client_prenom, "
            + "va.nom AS agent_nom, va.prenom AS agent_prenom "
            + "FROM orders o "
            + "JOIN emissions e ON e.id = o.emission_id "
            + "JOIN users u ON u.id = o.client_id "
            + "LEFT JOIN users va ON va.id = o.validated_by_agent";

    /** Delai de validation des co-signataires d'un compte joint (minutes). */
    private static final int COSIGN_TTL_MINUTES = 5;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final NotificationService notifications;
    private final EmailService email;
    private final AppProperties props;

    public OrderController(JdbcTemplate jdbc, AuditService audit, NotificationService notifications,
                           EmailService email, AppProperties props) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
        this.email = email;
        this.props = props;
    }

    /** Identifiant du COMPTE (titulaire principal) : pour un co-signataire, on
     *  resout vers son account_holder_id ; sinon l'utilisateur est lui-meme le compte. */
    private UUID accountId(UUID userId) {
        // NB : ne pas utiliser stream().findFirst() — le mapper peut renvoyer null
        // (compte individuel : account_holder_id IS NULL) et Optional refuse null.
        List<UUID> holders = jdbc.query("SELECT account_holder_id FROM users WHERE id = ?",
                (rs, n) -> rs.getObject("account_holder_id", UUID.class), userId);
        UUID holder = holders.isEmpty() ? null : holders.get(0);
        return holder != null ? holder : userId;
    }

    private record SignatoryRow(UUID id, String email, String nom, String telephone) {
    }

    // ─────────────────────────────── DTO ────────────────────────────────────

    record OrderResponse(
            UUID id, String reference, UUID emissionId, String emissionCode, String isin, String nature,
            String emetteur, UUID clientId, String clientNom, String compteEspeces, String compteTitres,
            int volume, long montant, double tauxSoumis, String status, OffsetDateTime dateSoumission,
            OffsetDateTime updatedAt, Long montantAdjuge, Double tauxAdjuge, Integer volumeAlloue,
            String commentaireResultat, String motifAnnulation, String canal, String notes,
            String ipSoumission, String signatureData, Boolean signatureVerified, UUID validatedByAgent,
            String validatedByAgentNom, OffsetDateTime dateValidationAgent,
            String resultatPropose, OffsetDateTime resultatProposeAt, OffsetDateTime resultatValideAt) {
    }

    record CreateOrderRequest(
            @NotNull UUID emissionId,
            @NotNull @Min(value = 1, message = "le volume doit être un entier positif") Integer volume,
            @NotNull @PositiveOrZero Double tauxSoumis,
            String signatureData) {
    }

    record NotesRequest(@NotNull @Size(max = 2000) String notes) {
    }

    record VerifySignatureRequest(@NotNull Boolean verified) {
    }

    record RejectOrderRequest(
            @Size(min = 3, max = 500, message = "motif obligatoire (3 a 500 caracteres)") String motif) {
    }

    record ChangeStatusRequest(
            @NotNull String status,
            @Size(max = 1000) String motif,
            Long montantAdjuge,
            Integer volumeAlloue,
            Double tauxAdjuge,
            @Size(max = 1000) String commentaire) {
    }

    private record EmissionForOrder(String status, long vnu, long montantMin, String isin) {
    }

    record CoSignRequest(@NotNull Boolean approve) {
    }

    record UpdateOrderRequest(
            @NotNull @Min(value = 1, message = "le volume doit être un entier positif") Integer volume,
            @NotNull @PositiveOrZero Double tauxSoumis,
            String signatureData) {
    }

    record CoSignatureView(UUID orderId, String reference, String emissionCode, String nature,
                           int volume, long montant, double tauxSoumis, OffsetDateTime expiresAt) {
    }

    private static final RowMapper<OrderResponse> MAPPER = (rs, n) -> {
        String clientNom = nomAffichage(rs.getString("client_nom"), rs.getString("client_prenom"));
        String agentNom = rs.getString("agent_nom");
        String validatedByAgentNom = agentNom == null
                ? null : nomAffichage(agentNom, rs.getString("agent_prenom"));
        return new OrderResponse(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getObject("emission_id", UUID.class),
                rs.getString("emission_code"),
                rs.getString("isin"),
                rs.getString("emission_nature"),
                Countries.label(rs.getString("emission_pays")),
                rs.getObject("client_id", UUID.class),
                clientNom,
                rs.getString("compte_especes"),
                rs.getString("compte_titres"),
                rs.getInt("volume"),
                rs.getLong("montant"),
                rs.getDouble("taux_soumis"),
                rs.getString("status"),
                rs.getObject("date_soumission", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("montant_adjuge", Long.class),
                rs.getObject("taux_adjuge", Double.class),
                rs.getObject("volume_alloue", Integer.class),
                rs.getString("commentaire_resultat"),
                rs.getString("motif_annulation"),
                rs.getString("canal"),
                rs.getString("notes"),
                rs.getString("ip_soumission"),
                rs.getString("signature_data"),
                rs.getObject("signature_verified", Boolean.class),
                rs.getObject("validated_by_agent", UUID.class),
                validatedByAgentNom,
                rs.getObject("date_validation_agent", OffsetDateTime.class),
                rs.getString("resultat_propose"),
                rs.getObject("resultat_propose_at", OffsetDateTime.class),
                rs.getObject("resultat_valide_at", OffsetDateTime.class));
    };

    // ───────────────────────────── Handlers ─────────────────────────────────

    /** {@code GET /orders} — un client ne voit que ses ordres ; le staff voit tout. */
    @GetMapping
    public PageResponse<OrderResponse> list(AuthUser user,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) UUID emissionId,
                                            @RequestParam(required = false) UUID clientId,
                                            @RequestParam(required = false) Boolean pendingValidation,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        Pagination pg = Pagination.of(page, size);
        StringBuilder dataSql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        StringBuilder countSql = new StringBuilder("SELECT count(*) FROM orders o WHERE 1 = 1");
        List<Object> args = new ArrayList<>();

        if (user.isClient()) {
            // Cloisonnement : un client est verrouille sur les ordres de SON compte
            // (pour un co-signataire d'un compte joint, le compte du titulaire principal).
            dataSql.append(" AND o.client_id = ?");
            countSql.append(" AND o.client_id = ?");
            args.add(accountId(user.id()));
        } else {
            if (clientId != null) {
                dataSql.append(" AND o.client_id = ?");
                countSql.append(" AND o.client_id = ?");
                args.add(clientId);
            }
            if (status != null && !status.isEmpty()) {
                dataSql.append(" AND o.status = ?");
                countSql.append(" AND o.status = ?");
                args.add(status);
            }
            if (emissionId != null) {
                dataSql.append(" AND o.emission_id = ?");
                countSql.append(" AND o.emission_id = ?");
                args.add(emissionId);
            }
            // File de validation du superviseur : adjudications saisies par
            // l'agent et pas encore validees.
            if (Boolean.TRUE.equals(pendingValidation)) {
                dataSql.append(" AND o.resultat_propose IS NOT NULL AND o.resultat_valide_at IS NULL");
                countSql.append(" AND o.resultat_propose IS NOT NULL AND o.resultat_valide_at IS NULL");
            }
        }

        long total = jdbc.queryForObject(countSql.toString(), Long.class, args.toArray());

        dataSql.append(" ORDER BY o.date_soumission DESC LIMIT ? OFFSET ?");
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add(pg.limit());
        dataArgs.add(pg.offset());

        List<OrderResponse> data = jdbc.query(dataSql.toString(), MAPPER, dataArgs.toArray());
        if (user.isClient()) {
            data = data.stream().map(OrderController::scrubForClient).toList();
        }
        return pg.build(data, total);
    }

    /** {@code GET /orders/:id} — detail (le client ne consulte que ses ordres). */
    @GetMapping("/{id}")
    public OrderResponse get(AuthUser user, @PathVariable UUID id) {
        OrderResponse order = fetch(id);
        authorizeView(user, order);
        return user.isClient() ? scrubForClient(order) : order;
    }

    /** {@code GET /orders/cosignatures/pending} — operations en attente de MA signature (compte joint). */
    @GetMapping("/cosignatures/pending")
    public List<CoSignatureView> pendingCoSignatures(AuthUser user) {
        if (!user.isClient()) return List.of();
        return jdbc.query(
                "SELECT o.id, o.reference, o.volume, o.montant, o.taux_soumis, e.code AS emission_code, "
                        + "e.nature AS emission_nature, s.expires_at "
                        + "FROM order_signatures s "
                        + "JOIN orders o ON o.id = s.order_id "
                        + "JOIN emissions e ON e.id = o.emission_id "
                        + "WHERE s.signatory_id = ? AND s.status = 'PENDING' "
                        + "AND o.status = 'EN_ATTENTE_SIGNATURES' AND s.expires_at > now() "
                        + "ORDER BY s.expires_at ASC",
                (rs, n) -> new CoSignatureView(
                        rs.getObject("id", UUID.class), rs.getString("reference"),
                        rs.getString("emission_code"), rs.getString("emission_nature"),
                        rs.getInt("volume"), rs.getLong("montant"), rs.getDouble("taux_soumis"),
                        rs.getObject("expires_at", OffsetDateTime.class)),
                user.id());
    }

    /**
     * {@code POST /orders/:id/cosign} — un co-signataire d'un compte joint valide
     * (approve=true) ou refuse (approve=false) l'operation. Quand tous les
     * signataires ont valide, l'ordre passe en SOUMIS ; un refus le rejette et
     * notifie l'ensemble des signataires du compte.
     */
    @PostMapping("/{id}/cosign")
    public OrderResponse coSign(AuthUser user, ClientIp ip, @PathVariable UUID id,
                                @Valid @RequestBody CoSignRequest req) {
        if (!user.isClient()) {
            throw ApiException.forbidden("Seul un signataire du compte peut valider cette opération.");
        }
        OrderResponse order = fetch(id);

        String sigStatus = jdbc.query(
                "SELECT status FROM order_signatures WHERE order_id = ? AND signatory_id = ? "
                        + "AND expires_at > now()",
                (rs, n) -> rs.getString("status"), id, user.id())
                .stream().findFirst().orElse(null);
        ApiException.ensure(sigStatus != null,
                "Aucune demande de validation en attente pour vous sur cette opération.");
        ApiException.ensure("PENDING".equals(sigStatus), "Vous avez déjà répondu à cette demande.");
        ApiException.ensure("EN_ATTENTE_SIGNATURES".equals(order.status()),
                "Cette opération n'est plus en attente de signatures.");

        UUID account = order.clientId();
        if (req.approve()) {
            jdbc.update("UPDATE order_signatures SET status='SIGNED', decided_at=now() "
                    + "WHERE order_id = ? AND signatory_id = ?", id, user.id());
            Long restantes = jdbc.queryForObject(
                    "SELECT count(*) FROM order_signatures WHERE order_id = ? AND status = 'PENDING'",
                    Long.class, id);
            audit.log(user.id().toString(), "COSIGNATURE_VALIDATION", AuditService.SUCCES,
                    order.reference(), ip.value());
            if (restantes != null && restantes == 0) {
                // Toutes les signatures obtenues -> l'ordre est transmis (SOUMIS).
                jdbc.update("UPDATE orders SET status='SOUMIS', updated_at=now() "
                        + "WHERE id = ? AND status = 'EN_ATTENTE_SIGNATURES'", id);
                notifyAccount(account, "SUCCESS", "Opération validée et transmise",
                        "L'ordre " + order.reference() + " a été validé par tous les signataires et "
                                + "est désormais en cours de traitement.", order.reference());
            } else {
                notifyAccount(account, "INFO", "Validation enregistrée",
                        "Votre validation de l'ordre " + order.reference() + " est enregistrée. "
                                + "En attente des autres signataires.", order.reference());
            }
        } else {
            jdbc.update("UPDATE order_signatures SET status='REJECTED', decided_at=now() "
                    + "WHERE order_id = ? AND signatory_id = ?", id, user.id());
            jdbc.update("UPDATE orders SET status='ANNULE', "
                    + "motif_annulation='Opération refusée par un co-signataire du compte joint', "
                    + "updated_at=now() WHERE id = ? AND status = 'EN_ATTENTE_SIGNATURES'", id);
            audit.log(user.id().toString(), "COSIGNATURE_REFUS", AuditService.SUCCES,
                    order.reference(), ip.value());
            notifyAccount(account, "WARN", "Opération rejetée",
                    "L'ordre " + order.reference() + " a été rejeté car un signataire n'a pas validé "
                            + "l'opération.", order.reference());
        }
        return scrubForClient(fetch(id));
    }

    /** Notifie tous les signataires d'un compte (titulaire principal + co-titulaires). */
    private void notifyAccount(UUID account, String type, String titre, String message, String reference) {
        try {
            jdbc.update("INSERT INTO notifications (user_id, type, titre, message, reference) "
                            + "SELECT id, ?, ?, ?, ? FROM users WHERE id = ? OR account_holder_id = ?",
                    type, titre, message, reference, account, account);
        } catch (RuntimeException ignored) {
            // Une notification ne doit jamais interrompre l'operation metier.
        }
    }

    /** {@code POST /orders} — soumission d'un ordre par un client. */
    @PostMapping
    public ResponseEntity<OrderResponse> create(AuthUser user, ClientIp ip,
                                                @Valid @RequestBody CreateOrderRequest req) {
        if (!user.isClient()) {
            throw ApiException.forbidden("Seul un client peut soumettre un ordre.");
        }

        EmissionForOrder emission = jdbc.query(
                        "SELECT status, valeur_nominale_unitaire, montant_minimum, isin "
                                + "FROM emissions WHERE id = ?",
                        (rs, n) -> new EmissionForOrder(rs.getString("status"),
                                rs.getLong("valeur_nominale_unitaire"),
                                rs.getLong("montant_minimum"), rs.getString("isin")),
                        req.emissionId())
                .stream().findFirst().orElse(null);
        if (emission == null) {
            throw ApiException.notFound("Émission introuvable.");
        }
        ApiException.ensure("PUBLIE".equals(emission.status()),
                "cette émission n'est pas ouverte à la souscription");

        // Pour un co-signataire, on resout vers le compte du titulaire principal :
        // tous les signataires partagent le meme compte-titres et les memes ordres.
        UUID account = accountId(user.id());

        String[] comptes = jdbc.queryForObject(
                "SELECT compte_titres, compte_especes FROM users WHERE id = ?",
                (rs, n) -> new String[]{rs.getString("compte_titres"), rs.getString("compte_especes")},
                account);
        if (comptes == null || comptes[0] == null || comptes[1] == null) {
            throw ApiException.badRequest("Aucun compte-titres rattaché à votre profil.");
        }

        long montant;
        try {
            montant = Math.multiplyExact((long) req.volume(), emission.vnu());
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("Montant de l'ordre hors limites.");
        }
        ApiException.ensure(montant >= emission.montantMin(),
                "le montant de l'ordre est inférieur au minimum de souscription");

        // Un compte peut soumettre PLUSIEURS ordres sur une meme emission (le
        // frontend confirme par un pop-up si un ordre actif existe deja sur le
        // meme titre). Aucune contrainte d'unicite n'est appliquee ici.

        // Signataires actifs du compte (titulaire principal + co-titulaires). Si le
        // compte est joint (plusieurs signataires), l'ordre attend la validation de
        // TOUS les AUTRES signataires avant d'etre transmis.
        List<SignatoryRow> signataires = jdbc.query(
                "SELECT id, email, nom, prenom, telephone FROM users "
                        + "WHERE (id = ? OR account_holder_id = ?) AND statut = 'ACTIF'",
                (rs, n) -> new SignatoryRow(rs.getObject("id", UUID.class), rs.getString("email"),
                        nomAffichage(rs.getString("nom"), rs.getString("prenom")), rs.getString("telephone")),
                account, account);
        boolean joint = signataires.size() > 1;
        String initialStatus = joint ? "EN_ATTENTE_SIGNATURES" : "SOUMIS";

        // Reference UNIQUE a numerotation annuelle. Sous concurrence, count(*)+1
        // peut produire deux fois le meme numero : on retente sur conflit d'unicite.
        String reference = null;
        UUID newId = null;
        for (int attempt = 0; attempt < 5 && newId == null; attempt++) {
            reference = nextOrderReference();
            try {
                newId = jdbc.queryForObject(
                        "INSERT INTO orders (reference, emission_id, client_id, isin, volume, montant, "
                                + "taux_soumis, status, compte_especes, compte_titres, canal, ip_soumission, "
                                + "signature_data) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,'EN_LIGNE',?,?) RETURNING id",
                        UUID.class, reference, req.emissionId(), account, emission.isin(), req.volume(),
                        montant, req.tauxSoumis(), initialStatus, comptes[1], comptes[0], ip.value(),
                        req.signatureData());
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Seule la reference d'ordre est desormais soumise a unicite :
                // un conflit signifie une collision de numerotation -> on retente.
                if (attempt == 4) throw e;
            }
        }

        audit.log(user.id().toString(), "SOUMISSION_ORDRE", AuditService.SUCCES, reference, ip.value());

        if (joint) {
            // Compte joint : une demande de signature (TTL 5 min) par AUTRE signataire ;
            // l'emetteur consent par sa soumission. Chacun est notifie (in-app + e-mail).
            OffsetDateTime expiry = OffsetDateTime.now().plusMinutes(COSIGN_TTL_MINUTES);
            int demandes = 0;
            for (SignatoryRow s : signataires) {
                if (s.id().equals(user.id())) continue;
                jdbc.update("INSERT INTO order_signatures (order_id, signatory_id, expires_at) VALUES (?,?,?)",
                        newId, s.id(), expiry);
                notifications.notify(s.id(), "WARN", "Validation requise (compte joint)",
                        "Une opération (" + reference + ") sur votre compte-titres joint requiert votre "
                                + "validation sous " + COSIGN_TTL_MINUTES + " minutes. Connectez-vous pour valider.",
                        reference);
                sendCoSignatureEmail(s.email(), s.nom(), reference);
                demandes++;
            }
            notifications.notify(user.id(), "INFO", "Ordre en attente de signatures",
                    "Votre ordre " + reference + " attend la validation de " + demandes
                            + " co-signataire(s). Il sera transmis une fois toutes les validations obtenues.",
                    reference);
        } else {
            notifications.notify(user.id(), "INFO", "Ordre soumis",
                    "Votre ordre " + reference + " a été enregistré et est en cours de traitement.",
                    reference);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(fetch(newId));
    }

    /** Envoie au signataire un e-mail l'invitant a se connecter pour valider l'ordre. */
    private void sendCoSignatureEmail(String to, String nom, String reference) {
        if (to == null || !to.contains("@")) return;
        String origin = props.getFrontendOrigin() == null ? ""
                : props.getFrontendOrigin().split(",")[0].trim();
        String lien = origin.isEmpty() ? "votre espace investisseur" : origin;
        String html = "<p>Bonjour " + esc(nom) + ",</p>"
                + "<p>Une opération (<b>" + esc(reference) + "</b>) a été initiée sur votre compte-titres "
                + "joint et requiert <b>votre validation</b>.</p>"
                + "<p>Connectez-vous avec vos identifiants personnels pour l'approuver ou la refuser : "
                + "<a href=\"" + esc(lien) + "\">" + esc(lien) + "</a></p>"
                + "<p>Sans validation de votre part sous <b>" + COSIGN_TTL_MINUTES
                + " minutes</b>, l'opération sera automatiquement rejetée.</p>";
        try {
            email.sendOne(to, "Validation requise — opération sur compte-titres joint", html);
        } catch (RuntimeException ignored) {
            // L'echec d'envoi n'interrompt pas la creation de l'ordre (notif in-app deja posee).
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** {@code POST /orders/:id/cancel} — annulation par le client proprietaire ou le staff. */
    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(AuthUser user, ClientIp ip, @PathVariable UUID id) {
        OrderResponse order = fetch(id);
        authorizeView(user, order);
        ApiException.ensure("SOUMIS".equals(order.status()),
                "seul un ordre au statut « Soumis » peut être annulé");

        jdbc.update("UPDATE orders SET status='ANNULE', motif_annulation=?, updated_at=now() WHERE id=?",
                "Annulation à la demande du client", id);

        audit.log(user.id().toString(), "ANNULATION_ORDRE", AuditService.SUCCES,
                order.reference(), ip.value());
        notifications.notify(order.clientId(), "INFO", "Ordre annulé",
                "Votre ordre " + order.reference() + " a été annulé.", order.reference());
        return fetch(id);
    }

    /** {@code PATCH /orders/:id} — modification d'un ordre SOUMIS par le client propriétaire. */
    @PatchMapping("/{id}")
    public OrderResponse update(AuthUser user, ClientIp ip, @PathVariable UUID id,
                                @Valid @RequestBody UpdateOrderRequest req) {
        if (!user.isClient()) {
            throw ApiException.forbidden("Seul un client peut modifier son ordre.");
        }
        OrderResponse order = fetch(id);
        authorizeView(user, order);
        ApiException.ensure("SOUMIS".equals(order.status()),
                "seul un ordre au statut « Soumis » peut être modifié");
        ApiException.ensure(order.validatedByAgent() == null,
                "cet ordre a déjà été pris en charge par un agent et ne peut plus être modifié");

        Long vnu = jdbc.queryForObject(
                "SELECT valeur_nominale_unitaire FROM emissions WHERE id = ?",
                Long.class, order.emissionId());
        if (vnu == null) throw ApiException.badRequest("Émission introuvable.");
        long montant = (long) req.volume() * vnu;

        jdbc.update("UPDATE orders SET volume=?, taux_soumis=?, montant=?, "
                + "signature_data=COALESCE(?,signature_data), updated_at=now() WHERE id=?",
                req.volume(), req.tauxSoumis(), montant, req.signatureData(), id);

        audit.log(user.id().toString(), "MODIFICATION_ORDRE", AuditService.SUCCES, order.reference(), ip.value());
        return fetch(id);
    }

    /** {@code POST /orders/:id/validate} — transmission pour adjudication (ORDER_VALIDATE). */
    @PostMapping("/{id}/validate")
    public OrderResponse validate(AuthUser user, ClientIp ip, @PathVariable UUID id) {
        user.require(Permission.ORDER_VALIDATE);
        OrderResponse order = fetch(id);
        ApiException.ensure("SOUMIS".equals(order.status()) || "EN_VERIFICATION".equals(order.status()),
                "cet ordre ne peut pas être validé dans son statut actuel");

        jdbc.update("UPDATE orders SET status='EN_ATTENTE_ADJUDICATION', validated_by_agent=?, "
                + "date_validation_agent=now(), updated_at=now() WHERE id=?", user.id(), id);

        audit.log(user.id().toString(), "VALIDATION_ORDRE", AuditService.SUCCES,
                order.reference(), ip.value());
        notifications.notify(order.clientId(), "INFO", "Ordre validé",
                "Votre ordre " + order.reference()
                        + " est validé et en cours de traitement.", order.reference());
        return fetch(id);
    }

    /** {@code POST /orders/:id/reject} — rejet motive d'un ordre (ORDER_VALIDATE). */
    @PostMapping("/{id}/reject")
    public OrderResponse reject(AuthUser user, ClientIp ip, @PathVariable UUID id,
                                @Valid @RequestBody RejectOrderRequest req) {
        user.require(Permission.ORDER_VALIDATE);
        OrderResponse order = fetch(id);
        // Un ordre dans un statut final (y compris NON_RETENU, résultat d'adjudication
        // validé) ne peut plus être rejeté — cohérent avec changeStatus().
        ApiException.ensure(!FINAL_STATUSES.contains(order.status()),
                "cet ordre est dans un statut final et ne peut plus être rejeté");

        jdbc.update("UPDATE orders SET status='ANNULE', motif_annulation=?, updated_at=now() WHERE id=?",
                req.motif().trim(), id);

        audit.log(user.id().toString(), "REJET_ORDRE", AuditService.SUCCES,
                order.reference(), ip.value());
        notifications.notify(order.clientId(), "WARN", "Ordre rejeté",
                "Votre ordre " + order.reference() + " a été rejeté. Motif : " + req.motif().trim(),
                order.reference());
        return fetch(id);
    }

    /**
     * {@code POST /orders/:id/status} — transition de statut.
     *
     * <p>Les résultats d'adjudication (totalement/partiellement/non retenu) ne
     * sont pas appliqués directement : l'agent (ORDER_RESULT) les <em>propose</em>,
     * l'ordre reste « en attente d'adjudication » côté client et le superviseur
     * est notifié pour validation (voir {@code /validate-result}). Les autres
     * transitions de traitement restent immédiates (ORDER_VALIDATE).</p>
     */
    @PostMapping("/{id}/status")
    public OrderResponse changeStatus(AuthUser user, ClientIp ip, @PathVariable UUID id,
                                      @Valid @RequestBody ChangeStatusRequest req) {
        OrderResponse order = fetch(id);
        ApiException.ensure(ORDER_STATUSES.contains(req.status()), "statut d'ordre invalide");
        // Un ordre cloture (etat final) ne peut plus changer de statut.
        ApiException.ensure(!FINAL_STATUSES.contains(order.status()),
                "cet ordre est clôturé : son statut ne peut plus être modifié");

        // ── Adjudication DIRECTE (un seul niveau) : l'agent applique le résultat ──
        // Plus de validation superviseur : le résultat saisi par l'agent
        // (ORDER_RESULT) prend effet immédiatement et l'ordre est finalisé.
        if (ADJUDICATION_RESULTS.contains(req.status())) {
            user.require(Permission.ORDER_RESULT);
            ApiException.ensure("EN_ATTENTE_ADJUDICATION".equals(order.status()),
                    "le résultat ne peut être saisi que pour un ordre transmis à l'adjudication");

            Long montantAdjuge = req.montantAdjuge() != null ? req.montantAdjuge() : order.montantAdjuge();
            Integer volumeAlloue = req.volumeAlloue() != null
                    ? req.volumeAlloue() : order.volumeAlloue();
            Double tauxAdjuge = req.tauxAdjuge() != null ? req.tauxAdjuge() : order.tauxAdjuge();
            String commentaire = req.commentaire() != null
                    ? req.commentaire().trim() : order.commentaireResultat();

            // Le résultat est appliqué et marqué validé par l'agent lui-même
            // (resultat_valide_at renseigné) : l'ordre atteint son statut final.
            jdbc.update("UPDATE orders SET status=?, resultat_propose=?, resultat_propose_par=?, "
                            + "resultat_propose_at=now(), resultat_valide_par=?, "
                            + "resultat_valide_at=now(), montant_adjuge=?, volume_alloue=?, "
                            + "taux_adjuge=?, commentaire_resultat=?, updated_at=now() WHERE id=?",
                    req.status(), req.status(), user.id(), user.id(),
                    montantAdjuge, volumeAlloue, tauxAdjuge, commentaire, id);

            audit.log(user.id().toString(), "ADJUDICATION_" + req.status(),
                    AuditService.SUCCES, order.reference(), ip.value());
            // Le client est informé sans détail interne (pas de nom de validateur).
            notifications.notify(order.clientId(), "INFO", "Mise à jour de votre ordre",
                    "Votre ordre " + order.reference() + " a été traité.", order.reference());
            return fetch(id);
        }

        // ── Transitions de traitement immediates (verification, transmission, annulation) ──
        user.require(Permission.ORDER_VALIDATE);
        String motif = order.motifAnnulation();
        if ("ANNULE".equals(req.status()) && req.motif() != null) {
            motif = req.motif().trim();
        }
        UUID validatedBy = order.validatedByAgent();
        OffsetDateTime dateValidation = order.dateValidationAgent();
        if ("EN_ATTENTE_ADJUDICATION".equals(req.status()) && order.validatedByAgent() == null) {
            validatedBy = user.id();
            dateValidation = OffsetDateTime.now();
        }

        jdbc.update("UPDATE orders SET status=?, motif_annulation=?, validated_by_agent=?, "
                        + "date_validation_agent=?, updated_at=now() WHERE id=?",
                req.status(), motif, validatedBy, dateValidation, id);

        audit.log(user.id().toString(), "CHANGEMENT_STATUT_" + req.status(), AuditService.SUCCES,
                order.reference(), ip.value());
        notifications.notify(order.clientId(), "INFO", "Mise à jour de votre ordre",
                "Le statut de votre ordre " + order.reference() + " a évolué : " + req.status() + ".",
                order.reference());
        return fetch(id);
    }

    /**
     * {@code POST /orders/:id/validate-result} — le superviseur valide
     * l'adjudication saisie par l'agent (ORDER_RESULT_VALIDATE). L'ordre prend
     * alors son statut final et le client est notifié.
     */
    @PostMapping("/{id}/validate-result")
    public OrderResponse validateResult(AuthUser user, ClientIp ip, @PathVariable UUID id) {
        user.require(Permission.ORDER_RESULT_VALIDATE);
        OrderResponse order = fetch(id);
        ApiException.ensure(order.resultatPropose() != null && order.resultatValideAt() == null,
                "aucun résultat d'adjudication en attente de validation pour cet ordre");

        jdbc.update("UPDATE orders SET status=?, resultat_valide_par=?, resultat_valide_at=now(), "
                + "updated_at=now() WHERE id=?", order.resultatPropose(), user.id(), id);

        audit.log(user.id().toString(), "VALIDATION_RESULTAT_" + order.resultatPropose(),
                AuditService.SUCCES, order.reference(), ip.value());
        notifications.notify(order.clientId(), "INFO", "Résultat d'adjudication",
                "Le résultat de votre ordre " + order.reference() + " est disponible : "
                        + resultLabel(order.resultatPropose()) + ".", order.reference());
        return fetch(id);
    }

    /**
     * {@code POST /orders/:id/reject-result} — le superviseur refuse l'adjudication
     * proposée (ORDER_RESULT_VALIDATE) ; elle repart vers l'agent pour correction.
     */
    @PostMapping("/{id}/reject-result")
    public OrderResponse rejectResult(AuthUser user, ClientIp ip, @PathVariable UUID id,
                                      @Valid @RequestBody RejectOrderRequest req) {
        user.require(Permission.ORDER_RESULT_VALIDATE);
        OrderResponse order = fetch(id);
        ApiException.ensure(order.resultatPropose() != null && order.resultatValideAt() == null,
                "aucun résultat d'adjudication en attente de validation pour cet ordre");

        String motif = req.motif() != null ? req.motif().trim() : "Correction demandée";
        jdbc.update("UPDATE orders SET resultat_propose=NULL, resultat_propose_par=NULL, "
                + "resultat_propose_at=NULL, commentaire_resultat=?, updated_at=now() WHERE id=?",
                motif, id);

        audit.log(user.id().toString(), "REJET_RESULTAT", AuditService.SUCCES,
                order.reference(), ip.value());
        if (order.validatedByAgent() != null) {
            notifications.notify(order.validatedByAgent(), "WARN", "Adjudication à corriger",
                    "Le résultat proposé pour l'ordre " + order.reference()
                            + " a été refusé par le superviseur. Motif : " + motif, order.reference());
        }
        return fetch(id);
    }

    /** {@code POST /orders/:id/notes} — note interne d'un ordre (ORDER_VALIDATE). */
    @PostMapping("/{id}/notes")
    public OrderResponse setNotes(AuthUser user, ClientIp ip, @PathVariable UUID id,
                                  @Valid @RequestBody NotesRequest req) {
        user.require(Permission.ORDER_VALIDATE);
        OrderResponse order = fetch(id);
        jdbc.update("UPDATE orders SET notes=?, updated_at=now() WHERE id=?", req.notes().trim(), id);
        audit.log(user.id().toString(), "NOTE_ORDRE", AuditService.SUCCES,
                order.reference(), ip.value());
        return fetch(id);
    }

    /** {@code POST /orders/:id/verify-signature} — valide ou rejette la signature (ORDER_VALIDATE). */
    @PostMapping("/{id}/verify-signature")
    public OrderResponse verifySignature(AuthUser user, ClientIp ip, @PathVariable UUID id,
                                         @Valid @RequestBody VerifySignatureRequest req) {
        user.require(Permission.ORDER_VALIDATE);
        OrderResponse order = fetch(id);
        jdbc.update("UPDATE orders SET signature_verified=?, updated_at=now() WHERE id=?",
                req.verified(), id);
        String action = req.verified() ? "SIGNATURE_VALIDEE" : "SIGNATURE_REJETEE";
        audit.log(user.id().toString(), action, AuditService.SUCCES, order.reference(), ip.value());
        if (!req.verified()) {
            notifications.notify(order.clientId(), "WARN", "Signature rejetée",
                    "La signature de votre ordre " + order.reference()
                            + " a été rejetée. Rapprochez-vous de votre agence.", order.reference());
        }
        return fetch(id);
    }

    // ───────────────────────────── Helpers ──────────────────────────────────

    private OrderResponse fetch(UUID id) {
        return jdbc.query(SELECT + " WHERE o.id = ?", MAPPER, id).stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Ordre introuvable."));
    }

    /** Un client ne peut consulter/agir que sur ses propres ordres. */
    private void authorizeView(AuthUser user, OrderResponse order) {
        // Un co-signataire accede aux ordres du compte joint : on compare au COMPTE
        // (titulaire principal), pas seulement a l'id de connexion.
        if (user.isClient() && !order.clientId().equals(accountId(user.id()))) {
            // 404 plutot que 403 : ne pas reveler l'existence de l'ordre d'autrui.
            throw ApiException.notFound("Ordre introuvable.");
        }
    }

    private String nextOrderReference() {
        String prefix = "ORD-" + Year.now().getValue() + "-";
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE reference LIKE ? || '%'", Long.class, prefix);
        return prefix + String.format("%06d", count + 1);
    }

    private static String nomAffichage(String nom, String prenom) {
        if (prenom != null && !prenom.trim().isEmpty()) {
            return nom + " " + prenom.trim();
        }
        return nom;
    }

    /**
     * Nettoie une reponse d'ordre destinee a un CLIENT (defense en profondeur) :
     *  - masque les notes internes du back-office et l'IP de soumission ;
     *  - masque tout resultat d'adjudication propose mais pas encore valide par
     *    le superviseur (le statut reste EN_ATTENTE_ADJUDICATION).
     * La signature reste exposee au proprietaire (c'est la sienne).
     */
    private static OrderResponse scrubForClient(OrderResponse o) {
        boolean pendingResult = o.resultatValideAt() == null && o.resultatPropose() != null;
        return new OrderResponse(o.id(), o.reference(), o.emissionId(), o.emissionCode(), o.isin(),
                o.nature(), o.emetteur(), o.clientId(), o.clientNom(), o.compteEspeces(),
                o.compteTitres(), o.volume(), o.montant(), o.tauxSoumis(), o.status(),
                o.dateSoumission(), o.updatedAt(),
                pendingResult ? null : o.montantAdjuge(),
                pendingResult ? null : o.tauxAdjuge(),
                pendingResult ? null : o.volumeAlloue(),
                pendingResult ? null : o.commentaireResultat(),
                o.motifAnnulation(), o.canal(),
                null,                 // notes internes — jamais exposees au client
                null,                 // ip de soumission — masquee
                o.signatureData(), o.signatureVerified(),
                null,                 // identifiant du valideur — masqué au client
                null,                 // nom du valideur — masqué au client (pas de « qui a validé »)
                o.dateValidationAgent(),
                pendingResult ? null : o.resultatPropose(),
                pendingResult ? null : o.resultatProposeAt(),
                o.resultatValideAt());
    }

    private static String resultLabel(String status) {
        return switch (status) {
            case "TOTALEMENT_RETENU" -> "totalement retenu";
            case "PARTIELLEMENT_RETENU" -> "partiellement retenu";
            case "NON_RETENU" -> "non retenu";
            default -> status;
        };
    }
}
