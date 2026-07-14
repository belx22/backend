package cm.afriland.titres.web;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;
import cm.afriland.titres.security.Permission;
import cm.afriland.titres.support.PageResponse;
import cm.afriland.titres.support.Pagination;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Module 6 — Livrables reglementaires (CSFT §M4).
 *
 * L'agent SVT televerse un document et designe le client destinataire ; le
 * client le consulte et le telecharge. Le contenu est stocke encode en base64.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private static final String DOC_INTROUVABLE = "Document introuvable.";
    private static final String WHERE_DOC_ID = " WHERE d.id = ?";

    /**
     * Types MIME autorises au televersement. Liste blanche stricte : exclut
     * notamment text/html, image/svg+xml, application/xhtml+xml et tout type
     * scriptable — un tel fichier, ouvert ensuite par le client via un blob/data
     * URI, s'executerait dans l'origine de l'application (XSS stockee). CSFT §M4.
     */
    private static final java.util.Set<String> ALLOWED_MIME = java.util.Set.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/webp",
            "text/csv",
            "text/plain",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel");

    private static final String META = "SELECT d.id, d.reference, d.type, d.titre, d.client_id, "
            + "d.uploaded_by, d.mime_type, d.taille, d.telechargements, d.date_generation, "
            + "u.nom AS client_nom, u.prenom AS client_prenom "
            + "FROM documents d JOIN users u ON u.id = d.client_id";

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public DocumentController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    record DocumentResponse(
            UUID id, String reference, String type, String titre, UUID clientId, String clientNom,
            UUID uploadedBy, String mimeType, String taille, int telechargements,
            OffsetDateTime dateGeneration,
            @JsonInclude(JsonInclude.Include.NON_NULL) String contenu) {
    }

    record CreateDocumentRequest(
            @NotNull UUID clientId,
            @Size(min = 1, max = 60) String type,
            @Size(min = 1, max = 200) String titre,
            @Size(min = 1, max = 120) String mimeType,
            @Size(max = 40) String taille,
            @Size(min = 1, max = 22_000_000) String contenu) {
    }

    private static RowMapper<DocumentResponse> mapper(String contenu) {
        return (rs, n) -> {
            String nom = rs.getString("client_nom");
            String prenom = rs.getString("client_prenom");
            String clientNom = (prenom != null && !prenom.isEmpty()) ? nom + " " + prenom : nom;
            return new DocumentResponse(
                    rs.getObject("id", UUID.class),
                    rs.getString("reference"),
                    rs.getString("type"),
                    rs.getString("titre"),
                    rs.getObject("client_id", UUID.class),
                    clientNom,
                    rs.getObject("uploaded_by", UUID.class),
                    rs.getString("mime_type"),
                    rs.getString("taille"),
                    rs.getInt("telechargements"),
                    rs.getObject("date_generation", OffsetDateTime.class),
                    contenu);
        };
    }

    /** {@code GET /documents} — un client ne voit que ses livrables ; le staff voit tout. */
    @GetMapping
    public PageResponse<DocumentResponse> list(AuthUser user,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        Pagination pg = Pagination.of(page, size);
        boolean clientScope = user.isClient();

        List<DocumentResponse> data;
        long total;
        if (clientScope) {
            data = jdbc.query(META + " WHERE d.client_id = ? ORDER BY d.date_generation DESC "
                    + "LIMIT ? OFFSET ?", mapper(null), user.id(), pg.limit(), pg.offset());
            total = jdbc.queryForObject(
                    "SELECT count(*) FROM documents WHERE client_id = ?", Long.class, user.id());
        } else {
            data = jdbc.query(META + " ORDER BY d.date_generation DESC LIMIT ? OFFSET ?",
                    mapper(null), pg.limit(), pg.offset());
            total = jdbc.queryForObject("SELECT count(*) FROM documents", Long.class);
        }
        return pg.build(data, total);
    }

    /** {@code GET /documents/:id} — detail avec contenu (consultation / telechargement). */
    @GetMapping("/{id}")
    public DocumentResponse get(AuthUser user, @PathVariable UUID id) {
        DocumentResponse meta = jdbc.query(META + WHERE_DOC_ID, mapper(null), id)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound(DOC_INTROUVABLE));

        // Cloisonnement : un client n'accede qu'a ses propres documents.
        if (user.isClient() && !meta.clientId().equals(user.id())) {
            throw ApiException.notFound(DOC_INTROUVABLE);
        }

        String contenu = jdbc.queryForObject(
                "SELECT contenu FROM documents WHERE id = ?", String.class, id);

        // Compteur de telechargements : seul le client destinataire est comptabilise
        // (la previsualisation par le staff ne doit pas gonfler le compteur).
        if (user.isClient() && meta.clientId().equals(user.id())) {
            jdbc.update("UPDATE documents SET telechargements = telechargements + 1 WHERE id = ?", id);
        }

        return jdbc.query(META + WHERE_DOC_ID, mapper(contenu), id)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound(DOC_INTROUVABLE));
    }

    /** {@code POST /documents} — televersement d'un livrable (DOCUMENT_UPLOAD). */
    @PostMapping
    public ResponseEntity<DocumentResponse> upload(AuthUser user, ClientIp ip,
                                                   @Valid @RequestBody CreateDocumentRequest req) {
        user.require(Permission.DOCUMENT_UPLOAD);

        String destRole = jdbc.query("SELECT role FROM users WHERE id = ?",
                        (rs, n) -> rs.getString("role"), req.clientId())
                .stream().findFirst().orElse(null);
        if (destRole == null) {
            throw ApiException.notFound("Client destinataire introuvable.");
        }
        if (!"CLIENT_PP".equals(destRole) && !"CLIENT_PM".equals(destRole)) {
            throw ApiException.badRequest("Le destinataire doit être un client.");
        }

        // Liste blanche stricte du type MIME (anti XSS stockee via document piege).
        String mime = req.mimeType() == null ? "application/pdf"
                : req.mimeType().split(";")[0].trim().toLowerCase();
        ApiException.ensure(ALLOWED_MIME.contains(mime),
                "Type de fichier non autorisé. Formats acceptés : PDF, PNG, JPEG, WEBP, CSV, XLS(X), TXT.");

        // Coherence du data-URI : son type declare doit correspondre au MIME valide.
        String contenu = req.contenu();
        if (contenu != null && contenu.startsWith("data:")) {
            int comma = contenu.indexOf(',');
            String declared = comma > 5
                    ? contenu.substring(5, comma).split(";")[0].trim().toLowerCase() : "";
            ApiException.ensure(declared.isEmpty() || declared.equals(mime),
                    "Incohérence entre le type déclaré et le contenu du fichier.");
        }

        // Reference UNIQUE (DOC-AAAAMMJJ-NNNNNN) basee sur le total : sous
        // concurrence count(*)+1 peut collisionner -> retente sur conflit.
        String datePart = OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String reference = null;
        UUID newId = null;
        for (int attempt = 0; attempt < 5 && newId == null; attempt++) {
            long count = jdbc.queryForObject("SELECT count(*) FROM documents", Long.class);
            reference = "DOC-" + datePart + "-" + String.format("%06d", count + 1);
            try {
                newId = jdbc.queryForObject(
                        "INSERT INTO documents (reference, type, titre, client_id, uploaded_by, mime_type, "
                                + "taille, contenu) VALUES (?,?,?,?,?,?,?,?) RETURNING id",
                        UUID.class, reference, req.type().trim(), req.titre().trim(), req.clientId(),
                        user.id(), mime, req.taille(), req.contenu());
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (attempt == 4) throw e;
            }
        }

        audit.log(user.id().toString(), "DEPOT_LIVRABLE", AuditService.SUCCES, reference, ip.value());

        DocumentResponse row = jdbc.query(META + WHERE_DOC_ID, mapper(null), newId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound(DOC_INTROUVABLE));
        return ResponseEntity.status(HttpStatus.CREATED).body(row);
    }
}
