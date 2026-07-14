package cm.afriland.titres.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.notif.EmailService;
import cm.afriland.titres.notif.NotificationService;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;
import cm.afriland.titres.security.OptionalAuthUser;
import cm.afriland.titres.security.Permission;
import cm.afriland.titres.security.Rbac;
import cm.afriland.titres.support.Countries;
import cm.afriland.titres.support.PageResponse;
import cm.afriland.titres.support.Pagination;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Module 3 — Emissions de titres publics (CSFT §6.1, §M1).
 */
@RestController
@RequestMapping("/api/v1/emissions")
public class EmissionController {

    private static final Set<String> NATURES = Set.of("BTA", "OTA");
    private static final Set<String> PAYS = Set.of("CMR", "GAB", "CGO", "TCD", "RCA", "GNQ");
    private static final Set<String> MODES = Set.of("PRIX", "TAUX");

    private static final String STATUT_PUBLIE = "PUBLIE";
    private static final String STATUT_BROUILLON = "BROUILLON";
    /** Fragments HTML du tableau de l'avis — reutilises lors de la generation. */
    private static final String TD_RIGHT = "<td style=\"text-align:right;\">";
    private static final String TD_TR_CLOSE = "</td></tr>";

    /** Selection avec jointures pour resoudre les noms lisibles des acteurs. */
    private static final String SELECT = "SELECT e.id, e.code, e.isin, e.libelle, e.nature, e.pays_code, "
            + "e.date_emission, e.ouverture_souscription, e.fermeture_souscription, e.date_echeance, "
            + "e.date_reglement, e.duree_jours, e.valeur_nominale_unitaire, e.montant_global, e.taux_nominal, "
            + "e.montant_minimum, e.frequence_coupon, e.mode_adjudication, e.description, e.observation, "
            + "e.status, e.created_by, e.validated_by, e.date_validation, e.rejection_motif, "
            + "e.rejected_by, e.rejected_at, e.created_at, "
            + "NULLIF(trim(coalesce(cu.nom,'') || ' ' || coalesce(cu.prenom,'')), '') AS created_by_nom, "
            + "NULLIF(trim(coalesce(vu.nom,'') || ' ' || coalesce(vu.prenom,'')), '') AS validated_by_nom "
            + "FROM emissions e "
            + "LEFT JOIN users cu ON cu.id = e.created_by "
            + "LEFT JOIN users vu ON vu.id = e.validated_by";

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final EmailService email;
    private final NotificationService notifications;

    public EmissionController(JdbcTemplate jdbc, AuditService audit, EmailService email,
                             NotificationService notifications) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.email = email;
        this.notifications = notifications;
    }

    /** Roles destinataires des notifications de publication. */
    private static final List<String> PUBLISH_NOTIFY_ROLES = List.of("SUPERVISEUR", "ADMIN");

    // ─────────────────────────────── DTO ────────────────────────────────────

    record EmissionResponse(
            UUID id, String code, String isin, String libelle, String nature, String paysCode,
            String emetteur, LocalDate dateEmission, OffsetDateTime ouvertureSouscription,
            OffsetDateTime fermetureSouscription, LocalDate dateEcheance, LocalDate dateReglement,
            int dureeJours, long valeurNominaleUnitaire, long montantGlobal, double tauxNominal,
            long montantMinimum, String frequenceCoupon, String modeAdjudication, String description,
            String observation, String status, UUID createdBy, String createdByNom, UUID validatedBy,
            String validatedByNom, OffsetDateTime dateValidation, String rejectionMotif,
            UUID rejectedBy, OffsetDateTime rejectedAt, OffsetDateTime createdAt) {
    }

    record CreateEmissionRequest(
            @Size(min = 1, max = 150) String code,
            @Size(min = 12, max = 12, message = "l'ISIN comporte 12 caracteres") String isin,
            @Size(min = 1, max = 150) String libelle,
            @NotNull String nature,
            @NotNull String paysCode,
            @NotNull LocalDate dateEmission,
            @NotNull OffsetDateTime ouvertureSouscription,
            @NotNull OffsetDateTime fermetureSouscription,
            @NotNull LocalDate dateEcheance,
            @NotNull LocalDate dateReglement,
            @NotNull Long valeurNominaleUnitaire,
            @NotNull Long montantGlobal,
            Double tauxNominal,
            @NotNull Long montantMinimum,
            String frequenceCoupon,
            @NotNull String modeAdjudication,
            @Size(max = 2000) String description,
            String observation) {
    }

    record RejectEmissionRequest(
            @Size(min = 3, max = 1000, message = "motif obligatoire (3 a 1000 caracteres)") String motif) {
    }

    /** Requete de diffusion par e-mail du catalogue d'emissions non cloturees.
     *  Si {@code recipients} est vide, tous les clients enregistres sont cibles ;
     *  sinon, seules les adresses fournies par l'admin sont utilisees. */
    record BroadcastRequest(List<String> recipients) {
    }

    record BroadcastResponse(int recipientsCount, int emissionsCount, String status) {
    }

    private static final RowMapper<EmissionResponse> MAPPER = (rs, n) -> new EmissionResponse(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("isin"),
            rs.getString("libelle"),
            rs.getString("nature"),
            rs.getString("pays_code"),
            Countries.label(rs.getString("pays_code")),
            rs.getObject("date_emission", LocalDate.class),
            rs.getObject("ouverture_souscription", OffsetDateTime.class),
            rs.getObject("fermeture_souscription", OffsetDateTime.class),
            rs.getObject("date_echeance", LocalDate.class),
            rs.getObject("date_reglement", LocalDate.class),
            rs.getInt("duree_jours"),
            rs.getLong("valeur_nominale_unitaire"),
            rs.getLong("montant_global"),
            rs.getDouble("taux_nominal"),
            rs.getLong("montant_minimum"),
            rs.getString("frequence_coupon"),
            rs.getString("mode_adjudication"),
            rs.getString("description"),
            rs.getString("observation"),
            rs.getString("status"),
            rs.getObject("created_by", UUID.class),
            rs.getString("created_by_nom"),
            rs.getObject("validated_by", UUID.class),
            rs.getString("validated_by_nom"),
            rs.getObject("date_validation", OffsetDateTime.class),
            rs.getString("rejection_motif"),
            rs.getObject("rejected_by", UUID.class),
            rs.getObject("rejected_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class));

    // ───────────────────────────── Handlers ─────────────────────────────────

    /** {@code GET /emissions} — liste filtree et paginee. */
    @GetMapping
    public PageResponse<EmissionResponse> list(OptionalAuthUser auth,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String nature,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        boolean staff = auth.isPresent() && Rbac.isStaff(auth.value().role());
        Pagination pg = Pagination.of(page, size);

        StringBuilder dataSql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        StringBuilder countSql = new StringBuilder("SELECT count(*) FROM emissions WHERE 1 = 1");
        List<Object> args = new ArrayList<>();

        if (!staff) {
            dataSql.append(" AND e.status = 'PUBLIE'");
            countSql.append(" AND status = 'PUBLIE'");
        } else if (status != null && !status.isEmpty()) {
            dataSql.append(" AND e.status = ?");
            countSql.append(" AND status = ?");
            args.add(status);
        }
        if (nature != null && !nature.isEmpty()) {
            dataSql.append(" AND e.nature = ?");
            countSql.append(" AND nature = ?");
            args.add(nature);
        }

        long total = jdbc.queryForObject(countSql.toString(), Long.class, args.toArray());

        dataSql.append(" ORDER BY e.date_emission DESC, e.created_at DESC LIMIT ? OFFSET ?");
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add(pg.limit());
        dataArgs.add(pg.offset());

        List<EmissionResponse> data = jdbc.query(dataSql.toString(), MAPPER, dataArgs.toArray());
        return pg.build(data, total);
    }

    /** {@code GET /emissions/:id} — detail d'une emission. */
    @GetMapping("/{id}")
    public EmissionResponse get(OptionalAuthUser auth, @PathVariable UUID id) {
        EmissionResponse emission = fetch(id);
        boolean staff = auth.isPresent() && Rbac.isStaff(auth.value().role());
        if (!staff && !STATUT_PUBLIE.equals(emission.status())) {
            throw ApiException.notFound("Émission introuvable.");
        }
        return emission;
    }

    /** {@code POST /emissions} — creation d'une fiche emission (EMISSION_CREATE). */
    @PostMapping
    public ResponseEntity<EmissionResponse> create(AuthUser user, ClientIp ip,
                                                   @Valid @RequestBody CreateEmissionRequest req) {
        user.require(Permission.EMISSION_CREATE);
        validate(req);

        String isin = req.isin().trim().toUpperCase();
        Long dup = jdbc.queryForObject("SELECT count(*) FROM emissions WHERE isin = ?", Long.class, isin);
        ApiException.ensure(dup == 0,
                "deux émissions ne peuvent pas avoir le même ISIN : cet ISIN existe déjà");

        int dureeJours = (int) ChronoUnit.DAYS.between(req.dateEmission(), req.dateEcheance());

        UUID newId = jdbc.queryForObject(
                "INSERT INTO emissions (code, isin, libelle, nature, pays_code, date_emission, "
                        + "ouverture_souscription, fermeture_souscription, date_echeance, date_reglement, "
                        + "duree_jours, valeur_nominale_unitaire, montant_global, taux_nominal, "
                        + "montant_minimum, frequence_coupon, mode_adjudication, description, observation, "
                        + "status, created_by) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'BROUILLON',?) RETURNING id",
                UUID.class,
                req.code().trim(), isin, req.libelle().trim(), req.nature(), req.paysCode(),
                req.dateEmission(), req.ouvertureSouscription(), req.fermetureSouscription(),
                req.dateEcheance(), req.dateReglement(), dureeJours, req.valeurNominaleUnitaire(),
                req.montantGlobal(), req.tauxNominal() == null ? 0.0 : req.tauxNominal(),
                req.montantMinimum(), trimToNull(req.frequenceCoupon()), req.modeAdjudication(),
                trimToNull(req.description()), trimToNull(req.observation()), user.id());

        EmissionResponse row = fetch(newId);
        audit.log(user.id().toString(), "CREATION_EMISSION", AuditService.SUCCES, row.code(), ip.value());
        // Le superviseur et l'admin sont notifies qu'un brouillon attend publication.
        notifications.notifyRoles(PUBLISH_NOTIFY_ROLES, "INFO", "Émission à publier",
                "L'émission " + row.code() + " (" + row.libelle() + ") est en brouillon "
                        + "et attend sa publication.", row.code());
        return ResponseEntity.status(HttpStatus.CREATED).body(row);
    }

    /** {@code PATCH /emissions/:id} — modification d'une fiche (BROUILLON ou PUBLIE). */
    @PatchMapping("/{id}")
    public EmissionResponse update(AuthUser user, ClientIp ip, @PathVariable UUID id,
                                   @Valid @RequestBody CreateEmissionRequest req) {
        user.require(Permission.EMISSION_CREATE);
        EmissionResponse current = fetch(id);
        ApiException.ensure(STATUT_BROUILLON.equals(current.status()) || STATUT_PUBLIE.equals(current.status()),
                "seule une fiche en brouillon ou publiée peut être modifiée");
        validate(req);

        String isin = req.isin().trim().toUpperCase();
        Long dup = jdbc.queryForObject("SELECT count(*) FROM emissions WHERE isin = ? AND id <> ?",
                Long.class, isin, id);
        ApiException.ensure(dup == 0,
                "deux émissions ne peuvent pas avoir le même ISIN : cet ISIN existe déjà");

        int dureeJours = (int) ChronoUnit.DAYS.between(req.dateEmission(), req.dateEcheance());

        jdbc.update("UPDATE emissions SET code=?, isin=?, libelle=?, nature=?, pays_code=?, "
                        + "date_emission=?, ouverture_souscription=?, fermeture_souscription=?, "
                        + "date_echeance=?, date_reglement=?, duree_jours=?, valeur_nominale_unitaire=?, "
                        + "montant_global=?, taux_nominal=?, montant_minimum=?, frequence_coupon=?, "
                        + "mode_adjudication=?, description=?, observation=?, "
                        + "status = CASE WHEN status='PUBLIE' THEN 'BROUILLON' ELSE status END, "
                        + "validated_by = CASE WHEN status='PUBLIE' THEN NULL ELSE validated_by END, "
                        + "date_validation = CASE WHEN status='PUBLIE' THEN NULL ELSE date_validation END, "
                        + "updated_at = now() WHERE id = ?",
                req.code().trim(), isin, req.libelle().trim(), req.nature(), req.paysCode(),
                req.dateEmission(), req.ouvertureSouscription(), req.fermetureSouscription(),
                req.dateEcheance(), req.dateReglement(), dureeJours, req.valeurNominaleUnitaire(),
                req.montantGlobal(), req.tauxNominal() == null ? 0.0 : req.tauxNominal(),
                req.montantMinimum(), trimToNull(req.frequenceCoupon()), req.modeAdjudication(),
                trimToNull(req.description()), trimToNull(req.observation()), id);

        EmissionResponse row = fetch(id);
        audit.log(user.id().toString(), "MODIFICATION_EMISSION", AuditService.SUCCES, row.code(), ip.value());
        return row;
    }

    /** {@code POST /emissions/:id/publish} — BROUILLON -> PUBLIE.
     *  Publication par l'agent (EMISSION_CREATE), sans validation superviseur. */
    @PostMapping("/{id}/publish")
    public EmissionResponse publish(AuthUser user, ClientIp ip, @PathVariable UUID id) {
        user.require(Permission.EMISSION_CREATE);
        EmissionResponse current = fetch(id);
        ApiException.ensure(STATUT_BROUILLON.equals(current.status()),
                "seule une fiche en brouillon peut être publiée");
        // La date limite de souscription doit être dans le futur : sinon l'émission
        // serait immédiatement clôturée (souscription déjà fermée). On bloque avec
        // un message explicite plutôt que de publier puis clôturer aussitôt.
        ApiException.ensure(current.fermetureSouscription().isAfter(OffsetDateTime.now(java.time.ZoneOffset.UTC)),
                "la date limite de souscription est déjà dépassée : modifiez-la (dans le futur) avant de publier");
        jdbc.update("UPDATE emissions SET status='PUBLIE', validated_by=?, date_validation=now(), "
                + "updated_at=now() WHERE id=?", user.id(), id);
        EmissionResponse row = fetch(id);
        audit.log(user.id().toString(), "PUBLICATION_EMISSION", AuditService.SUCCES, row.code(), ip.value());
        return row;
    }

    /**
     * {@code POST /emissions/publish-all} — publie en une fois toutes les fiches
     * en brouillon (EMISSION_VALIDATE). Destine au bouton « tout publier » du
     * superviseur / de l'admin.
     */
    @PostMapping("/publish-all")
    public ResponseEntity<java.util.Map<String, Object>> publishAll(AuthUser user, ClientIp ip) {
        user.require(Permission.EMISSION_CREATE);
        // On ne publie que les brouillons encore ouverts (clôture future) : publier
        // une fiche déjà expirée la clôturerait aussitôt. Les fiches expirées sont
        // ignorées et signalées pour que l'opérateur corrige leur date limite.
        List<String> codes = jdbc.queryForList(
                "SELECT code FROM emissions WHERE status='BROUILLON' AND fermeture_souscription > now() "
                        + "ORDER BY created_at", String.class);
        int skipped = jdbc.queryForObject(
                "SELECT count(*) FROM emissions WHERE status='BROUILLON' "
                        + "AND fermeture_souscription <= now()", Integer.class);
        int count = jdbc.update("UPDATE emissions SET status='PUBLIE', validated_by=?, "
                + "date_validation=now(), updated_at=now() "
                + "WHERE status='BROUILLON' AND fermeture_souscription > now()", user.id());
        audit.log(user.id().toString(), "PUBLICATION_GROUPEE_EMISSIONS", AuditService.SUCCES,
                count + " fiche(s)", ip.value());
        return ResponseEntity.ok(java.util.Map.of("published", count, "skippedExpired", skipped, "codes", codes));
    }

    /** {@code POST /emissions/:id/reject} — rejet motive d'une fiche en brouillon. */
    @PostMapping("/{id}/reject")
    public EmissionResponse reject(AuthUser user, ClientIp ip, @PathVariable UUID id,
                                   @Valid @RequestBody RejectEmissionRequest req) {
        user.require(Permission.EMISSION_VALIDATE);
        EmissionResponse current = fetch(id);
        ApiException.ensure(STATUT_BROUILLON.equals(current.status()),
                "seule une fiche en brouillon peut être rejetée");
        jdbc.update("UPDATE emissions SET rejection_motif=?, rejected_by=?, rejected_at=now(), "
                + "validated_by=NULL, date_validation=NULL, updated_at=now() WHERE id=?",
                req.motif().trim(), user.id(), id);
        EmissionResponse row = fetch(id);
        audit.log(user.id().toString(), "REJET_EMISSION", AuditService.ECHEC, row.code(), ip.value());
        return row;
    }

    /** {@code POST /emissions/:id/close} — PUBLIE -> CLOTURE (EMISSION_VALIDATE). */
    @PostMapping("/{id}/close")
    public EmissionResponse close(AuthUser user, ClientIp ip, @PathVariable UUID id) {
        user.require(Permission.EMISSION_VALIDATE);
        EmissionResponse current = fetch(id);
        ApiException.ensure(STATUT_PUBLIE.equals(current.status()),
                "seule une fiche publiée peut être clôturée");
        jdbc.update("UPDATE emissions SET status='CLOTURE', updated_at=now() WHERE id=?", id);
        EmissionResponse row = fetch(id);
        audit.log(user.id().toString(), "CLOTURE_EMISSION", AuditService.SUCCES, row.code(), ip.value());
        return row;
    }

    /** {@code POST /emissions/:id/archive} — CLOTURE -> ARCHIVE (EMISSION_VALIDATE). */
    @PostMapping("/{id}/archive")
    public EmissionResponse archive(AuthUser user, ClientIp ip, @PathVariable UUID id) {
        user.require(Permission.EMISSION_VALIDATE);
        EmissionResponse current = fetch(id);
        ApiException.ensure("CLOTURE".equals(current.status()),
                "seule une fiche clôturée peut être archivée");
        jdbc.update("UPDATE emissions SET status='ARCHIVE', updated_at=now() WHERE id=?", id);
        EmissionResponse row = fetch(id);
        audit.log(user.id().toString(), "ARCHIVAGE_EMISSION", AuditService.SUCCES, row.code(), ip.value());
        return row;
    }

    /** {@code DELETE /emissions/:id} — suppression (EMISSION_DELETE, brouillon seulement). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(AuthUser user, ClientIp ip, @PathVariable UUID id) {
        user.require(Permission.EMISSION_DELETE);
        EmissionResponse current = fetch(id);
        ApiException.ensure(STATUT_BROUILLON.equals(current.status()),
                "seule une fiche en brouillon peut être supprimée");
        jdbc.update("DELETE FROM emissions WHERE id = ?", id);
        audit.log(user.id().toString(), "SUPPRESSION_EMISSION", AuditService.SUCCES,
                current.code(), ip.value());
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code GET /emissions/broadcast/recipients} — liste des e-mails des
     * clients enregistres actifs (CLIENT_PP / CLIENT_PM). Sert au front a
     * preremplir Outlook lorsque l'option « tous les clients » est choisie.
     * Reservee aux acteurs disposant de la permission CONFIG_MARCHE.
     */
    @GetMapping("/broadcast/recipients")
    public java.util.Map<String, List<String>> broadcastRecipients(AuthUser user) {
        user.require(Permission.CONFIG_MARCHE);
        List<String> emails = jdbc.queryForList(
                "SELECT email FROM users WHERE role IN ('CLIENT_PP','CLIENT_PM') "
                        + "AND statut = 'ACTIF' AND email IS NOT NULL ORDER BY email",
                String.class);
        return java.util.Map.of("emails", emails);
    }

    /**
     * {@code POST /emissions/broadcast} — envoi par e-mail du catalogue des
     * emissions non cloturees (statut PUBLIE) aux destinataires choisis.
     *
     * <p>Si {@code recipients} est vide ou absent, le digest est envoye a
     * tous les clients enregistres (roles CLIENT_PP et CLIENT_PM). Sinon, il
     * est envoye aux adresses fournies (admin-controlled). Reservee aux
     * acteurs disposant de la permission {@link Permission#CONFIG_MARCHE}.</p>
     *
     * <p>Note de securite : les BROUILLON ne sont jamais diffuses (information
     * interne tant que le superviseur n'a pas valide la fiche).</p>
     */
    @PostMapping("/broadcast")
    public BroadcastResponse broadcast(AuthUser user, ClientIp ip,
                                       @RequestBody(required = false) BroadcastRequest req) {
        user.require(Permission.CONFIG_MARCHE);

        // 1) Recipients : liste fournie OU tous les clients enregistres.
        List<String> recipients = sanitizeRecipients(req == null ? null : req.recipients());
        if (recipients.isEmpty()) {
            recipients = jdbc.queryForList(
                    "SELECT email FROM users WHERE role IN ('CLIENT_PP','CLIENT_PM') "
                            + "AND statut = 'ACTIF' AND email IS NOT NULL",
                    String.class);
        }
        ApiException.ensure(!recipients.isEmpty(),
                "Aucun destinataire : aucun client enregistre ni liste fournie.");

        // 2) Catalogue des emissions ouvertes (PUBLIE uniquement — pas de BROUILLON).
        List<EmissionResponse> emissions = jdbc.query(
                SELECT + " WHERE e.status = 'PUBLIE' ORDER BY e.fermeture_souscription, e.code",
                MAPPER);
        ApiException.ensure(!emissions.isEmpty(),
                "Aucune emission ouverte a diffuser actuellement.");

        // 3) Construction du digest et envoi.
        String subject = "Marche primaire CEMAC — " + emissions.size()
                + " emission(s) ouverte(s) a la souscription";
        String html = buildDigestHtml(emissions);
        EmailService.Status status = email.send(recipients, subject, html);

        audit.log(user.id().toString(), "DIFFUSION_EMISSIONS_EMAIL",
                status == EmailService.Status.FAILED ? AuditService.ECHEC : AuditService.SUCCES,
                recipients.size() + " dest. / " + emissions.size() + " emi.", ip.value());

        return new BroadcastResponse(recipients.size(), emissions.size(), status.name());
    }

    // ───────────────────────────── Helpers ──────────────────────────────────

    /** Filtre les adresses : trim, lower, format syntaxique minimum, dedoublonnage. */
    private static List<String> sanitizeRecipients(List<String> raw) {
        if (raw == null) return List.of();
        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
        for (String r : raw) {
            if (r == null) continue;
            String s = r.trim().toLowerCase();
            // Format minimal : non vide, presence de '@', longueur raisonnable.
            if (s.length() >= 5 && s.length() <= 254
                    && s.indexOf('@') > 0 && s.indexOf('@') < s.length() - 3
                    && !s.contains(" ")) {
                uniq.add(s);
            }
        }
        return new java.util.ArrayList<>(uniq);
    }

    /** Construit le corps HTML du digest — texte simple, robuste a tout client mail. */
    private static String buildDigestHtml(List<EmissionResponse> emissions) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html><html lang=\"fr\"><head><meta charset=\"utf-8\">")
                .append("<title>Emissions ouvertes</title></head>")
                .append("<body style=\"font-family:Arial,sans-serif;color:#1a2332;line-height:1.55;\">")
                .append("<div style=\"max-width:640px;margin:0 auto;padding:24px;\">")
                .append("<h2 style=\"color:#0f4c81;margin:0 0 4px;\">Marche primaire CEMAC</h2>")
                .append("<p style=\"color:#6b7a94;margin:0 0 20px;\">")
                .append("Retrouvez ci-dessous les emissions actuellement ouvertes a la souscription. ")
                .append("Connectez-vous a votre espace investisseur pour souscrire.</p>");

        for (EmissionResponse e : emissions) {
            sb.append("<div style=\"border:1px solid #e2e8f4;border-radius:10px;padding:16px;margin-bottom:14px;\">")
                    .append("<div style=\"font-weight:700;font-size:15px;\">")
                    .append(escape(e.libelle())).append("</div>")
                    .append("<div style=\"color:#6b7a94;font-size:13px;margin-top:2px;\">")
                    .append(escape(e.nature())).append(" &middot; ")
                    .append(escape(e.emetteur())).append(" (").append(escape(e.paysCode())).append(")")
                    .append(" &middot; ISIN ").append(escape(e.isin())).append("</div>")
                    .append("<table style=\"width:100%;margin-top:10px;font-size:13px;border-collapse:collapse;\">")
                    .append("<tr><td style=\"color:#6b7a94;padding:2px 0;\">Date d'emission</td>")
                    .append(TD_RIGHT).append(e.dateEmission()).append(TD_TR_CLOSE)
                    .append("<tr><td style=\"color:#6b7a94;padding:2px 0;\">Date d'echeance</td>")
                    .append(TD_RIGHT).append(e.dateEcheance()).append(TD_TR_CLOSE)
                    .append("<tr><td style=\"color:#6b7a94;padding:2px 0;\">Fermeture souscription</td>")
                    .append(TD_RIGHT).append(e.fermetureSouscription()).append(TD_TR_CLOSE)
                    .append("<tr><td style=\"color:#6b7a94;padding:2px 0;\">Montant global</td>")
                    .append("<td style=\"text-align:right;font-weight:600;\">")
                    .append(formatMoney(e.montantGlobal())).append(" FCFA</td></tr>")
                    .append("<tr><td style=\"color:#6b7a94;padding:2px 0;\">Montant minimum</td>")
                    .append(TD_RIGHT)
                    .append(formatMoney(e.montantMinimum())).append(" FCFA</td></tr>")
                    .append("<tr><td style=\"color:#6b7a94;padding:2px 0;\">Taux nominal</td>")
                    .append(TD_RIGHT).append(e.tauxNominal()).append(" %</td></tr>")
                    .append("</table></div>");
        }

        sb.append("<p style=\"color:#6b7a94;font-size:11px;margin-top:20px;\">")
                .append("Ce message vous est envoye dans le cadre du dispositif d'information ")
                .append("aux investisseurs. Pour toute question, contactez votre teneur de compte.")
                .append("</p></div></body></html>");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String formatMoney(long v) {
        return String.format(java.util.Locale.FRANCE, "%,d", v).replace(',', ' ');
    }

    private EmissionResponse fetch(UUID id) {
        return jdbc.query(SELECT + " WHERE e.id = ?", MAPPER, id).stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Émission introuvable."));
    }

    private static void validate(CreateEmissionRequest req) {
        ApiException.ensure(NATURES.contains(req.nature()), "nature invalide (BTA ou OTA)");
        ApiException.ensure(PAYS.contains(req.paysCode()), "pays émetteur invalide (code CEMAC)");
        ApiException.ensure(MODES.contains(req.modeAdjudication()), "mode d'adjudication invalide");
        ApiException.ensure(req.dateEcheance().isAfter(req.dateEmission()),
                "la date d'échéance doit suivre l'émission");
        ApiException.ensure(!req.fermetureSouscription().isBefore(req.ouvertureSouscription()),
                "la fermeture de souscription doit suivre l'ouverture");
        ApiException.ensure(req.montantMinimum() >= req.valeurNominaleUnitaire(),
                "le montant minimum doit être au moins égal à la valeur nominale");
        ApiException.ensure(req.valeurNominaleUnitaire() >= 1 && req.montantGlobal() >= 1
                        && req.montantMinimum() >= 1,
                "montants invalides : valeurs strictement positives attendues");
        if ("OTA".equals(req.nature())) {
            ApiException.ensure(req.frequenceCoupon() != null && !req.frequenceCoupon().isBlank(),
                    "la fréquence de coupon est obligatoire pour une OTA");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
