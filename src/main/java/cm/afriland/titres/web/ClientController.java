package cm.afriland.titres.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.dto.UserProfile;
import cm.afriland.titres.dto.UserRow;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.notif.CredentialDelivery;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;
import cm.afriland.titres.security.PasswordService;
import cm.afriland.titres.security.Permission;
import cm.afriland.titres.security.Tokens;
import cm.afriland.titres.support.PageResponse;
import cm.afriland.titres.support.Pagination;

/**
 * Module 10 — Administration : clients investisseurs (PP / PM).
 *
 * Le dossier client (profil, adresses, contacts, sous-comptes) est lu/ecrit
 * depuis PostgreSQL — aucune donnee n'est codee en dur.
 */
@RestController
@RequestMapping("/api/v1/clients")
public class ClientController {

    /** Tous les clients (CLIENT_PP/CLIENT_PM) sont inclus, qu'ils aient un
     *  dossier formalise (`client_profiles`) ou non — les comptes seeds et les
     *  comptes crees avant la formalisation du dossier doivent apparaitre dans
     *  le registre BO. Quand le profil manque, on synthetise des valeurs par
     *  defaut a partir du compte utilisateur (`raison_sociale` = nom prenom,
     *  type derive du role, statut ACTIF, date d'ouverture inconnue). */
    private static final String CLIENT_ROLES = "('CLIENT_PP','CLIENT_PM')";

    private static final String PROFILE_SELECT = "SELECT u.id AS user_id, "
            + "COALESCE(cp.type_personne, CASE WHEN u.role = 'CLIENT_PM' THEN 'PM' ELSE 'PP' END) "
            + "  AS type_personne, "
            + "COALESCE(cp.raison_sociale, "
            + "  NULLIF(trim(coalesce(u.nom,'') || ' ' || coalesce(u.prenom,'')), ''), "
            + "  u.email) AS raison_sociale, "
            + "cp.rccm, "
            + "COALESCE(cp.compte_statut, u.statut, 'ACTIF') AS compte_statut, "
            + "cp.date_ouverture, cp.created_by, cp.created_at, "
            + "NULLIF(trim(coalesce(cb.nom,'') || ' ' || coalesce(cb.prenom,'')), '') AS created_by_nom, "
            + "u.compte_titres, u.compte_especes, u.solde, u.categorie, u.type_compte "
            + "FROM users u "
            + "LEFT JOIN client_profiles cp ON cp.user_id = u.id "
            + "LEFT JOIN users cb ON cb.id = cp.created_by "
            + "WHERE u.role IN " + CLIENT_ROLES;

    private final JdbcTemplate jdbc;
    private final PasswordService password;
    private final AuditService audit;
    private final CredentialDelivery credentials;
    private final cm.afriland.titres.security.SecretCipher cipher;

    public ClientController(JdbcTemplate jdbc, PasswordService password, AuditService audit,
                            CredentialDelivery credentials,
                            cm.afriland.titres.security.SecretCipher cipher) {
        this.jdbc = jdbc;
        this.password = password;
        this.audit = audit;
        this.credentials = credentials;
        this.cipher = cipher;
    }

    // ─────────────────────────────── DTO ────────────────────────────────────

    record AdresseDto(String type, String residence, String rue, String codePostal,
                      String ville, String pays) {
    }

    record ContactDto(UUID id, String type, String nom, String prenom, String civilite,
                      String fonction, String telephonePortable, String telephoneDomicile,
                      String telephoneBureau, String email, String whatsapp, String pieceIdentite,
                      String numeroPiece, String dateValiditePiece, String lienParente) {
    }

    record SousCompteDto(UUID id, String numero, String libelle, String type, String statut,
                         LocalDate dateOuverture, int positionsCount, long valeurTotale,
                         String observations) {
    }

    record CompteDto(UUID id, String numero, String typeCompte, String statut, LocalDate dateOuverture,
                     String categorie, String compteEspecesLie, long soldeEspeces,
                     List<AdresseDto> adresses, List<ContactDto> contacts,
                     List<SousCompteDto> sousComptes) {
    }

    record ClientDossier(UUID id, String type, String raisonSociale, String rccm, UUID createdBy,
                         String createdByNom, OffsetDateTime createdAt, CompteDto compte) {
    }

    // --- Requete de creation ---

    record ReqAdresse(String type, String residence, String rue, String codePostal,
                      String ville, String pays) {
    }

    record ReqContact(String type, String nom, String prenom, String civilite, String fonction,
                      String telephonePortable, String telephoneDomicile, String telephoneBureau,
                      String email, String whatsapp, String pieceIdentite, String numeroPiece,
                      String dateValiditePiece, String lienParente) {
    }

    record ReqSousCompte(String numero, String libelle, String type, String statut,
                         String dateOuverture, Integer positionsCount, Long valeurTotale,
                         String observations) {
    }

    record CreateClientRequest(String type, String raisonSociale, String rccm, String categorie,
                               String typeCompte, ReqAdresse adresse, List<ReqContact> signataires,
                               List<ReqSousCompte> sousComptes, String compteEspecesLie,
                               Long soldeEspecesInitial) {
    }

    // ───────────────────────────── Handlers ─────────────────────────────────

    /** {@code GET /clients} — liste des comptes investisseurs (CLIENT_MANAGE). */
    @GetMapping
    public PageResponse<UserProfile> listClients(AuthUser user,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        user.require(Permission.CLIENT_MANAGE);
        Pagination pg = Pagination.of(page, size);

        List<UserProfile> data = jdbc.query(
                        "SELECT " + UserRow.COLUMNS + " FROM users WHERE role IN ('CLIENT_PP','CLIENT_PM') "
                                + "ORDER BY nom, prenom LIMIT ? OFFSET ?",
                        UserRow.MAPPER, pg.limit(), pg.offset())
                .stream().map(UserRow::toProfile).toList();
        long total = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE role IN ('CLIENT_PP','CLIENT_PM')", Long.class);
        return pg.build(data, total);
    }

    /** {@code GET /clients/dossiers} — registre des dossiers, pagine (CLIENT_MANAGE). */
    @GetMapping("/dossiers")
    public PageResponse<ClientDossier> listDossiers(AuthUser user,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size) {
        user.require(Permission.CLIENT_MANAGE);
        Pagination pg = Pagination.of(page, size);

        long total = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE role IN " + CLIENT_ROLES, Long.class);
        // ORDER BY sur la raison sociale synthetisee (cf. PROFILE_SELECT),
        // sinon les comptes sans dossier formalise ne sont pas tries.
        List<ClientDossier> data = loadDossiers(
                PROFILE_SELECT + " ORDER BY raison_sociale LIMIT ? OFFSET ?",
                pg.limit(), pg.offset());
        return pg.build(data, total);
    }

    /** {@code GET /clients/me} — dossier du client connecte (solde masque). */
    @GetMapping("/me")
    public ClientDossier myDossier(AuthUser user) {
        ClientDossier dossier = loadDossiers(PROFILE_SELECT + " AND u.id = ?", user.id())
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound(
                        "Aucun dossier client rattaché à ce compte."));
        return scrubBalanceForClient(dossier, user);
    }

    /** {@code GET /clients/:id} — dossier d'un client donne (solde masque pour le client). */
    @GetMapping("/{id}")
    public ClientDossier getDossier(AuthUser user, @PathVariable UUID id) {
        boolean staff = !user.id().equals(id);
        if (staff) {
            user.require(Permission.CLIENT_MANAGE);
        }
        ClientDossier dossier = loadDossiers(PROFILE_SELECT + " AND u.id = ?", id).stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Client introuvable."));
        return staff ? dossier : scrubBalanceForClient(dossier, user);
    }

    /**
     * Supprime la valeur du solde espece (cache base) avant de renvoyer le
     * dossier a un client. Source de verite : Amplitude/AIF, reserve au BO.
     */
    private static ClientDossier scrubBalanceForClient(ClientDossier dossier, AuthUser user) {
        if (!user.isClient() || dossier.compte() == null) return dossier;
        CompteDto c = dossier.compte();
        CompteDto scrubbed = new CompteDto(c.id(), c.numero(), c.typeCompte(), c.statut(),
                c.dateOuverture(), c.categorie(), c.compteEspecesLie(), 0L,
                c.adresses(), c.contacts(), c.sousComptes());
        return new ClientDossier(dossier.id(), dossier.type(), dossier.raisonSociale(),
                dossier.rccm(), dossier.createdBy(), dossier.createdByNom(),
                dossier.createdAt(), scrubbed);
    }

    /** {@code POST /clients} — onboarding d'un client investisseur (CLIENT_MANAGE). */
    @PostMapping
    @Transactional
    public ResponseEntity<ClientDossier> createClient(AuthUser creator, ClientIp ip,
                                                      @RequestBody CreateClientRequest req) {
        creator.require(Permission.CLIENT_MANAGE);

        String typePersonne = req.type() == null ? "" : req.type().trim().toUpperCase();
        ApiException.ensure("PP".equals(typePersonne) || "PM".equals(typePersonne),
                "type de client invalide (PP ou PM)");
        ApiException.ensure(req.raisonSociale() != null && !req.raisonSociale().trim().isEmpty(),
                "raison sociale requise");
        ApiException.ensure(req.signataires() != null && !req.signataires().isEmpty(),
                "au moins un signataire requis");
        ApiException.ensure(req.sousComptes() != null && !req.sousComptes().isEmpty(),
                "au moins un sous-compte titres requis");

        String role = "PP".equals(typePersonne) ? "CLIENT_PP" : "CLIENT_PM";
        String categorie = "QUALIFIE".equals(req.categorie()) ? "QUALIFIE" : "NON_QUALIFIE";
        String typeCompte = (req.typeCompte() != null && !req.typeCompte().trim().isEmpty())
                ? req.typeCompte() : "INDIVIDUEL";

        // L'e-mail de connexion est celui du premier signataire.
        ReqContact first = req.signataires().get(0);
        String email = (first.email() == null ? "" : first.email().trim().toLowerCase());
        ApiException.ensure(email.contains("@"),
                "Le premier signataire doit avoir un e-mail valide.");

        Long exists = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Long.class, email);
        ApiException.ensure(exists == 0, "un compte existe déjà avec cet e-mail");

        // Champs OBLIGATOIRES uniquement : nom + téléphone du 1er signataire, et le
        // numéro de chaque sous-compte. Tout le reste est facultatif. On échoue en
        // 400 explicite plutôt qu'en NPE → 500 lors des .trim() ci-dessous.
        ApiException.ensure(first.nom() != null && !first.nom().trim().isEmpty(),
                "le nom du premier signataire est requis");
        boolean firstHasPhone =
                (first.telephonePortable() != null && !first.telephonePortable().trim().isEmpty())
                || (first.telephoneBureau() != null && !first.telephoneBureau().trim().isEmpty());
        ApiException.ensure(firstHasPhone,
                "le numéro de téléphone du premier signataire est requis");
        for (ReqContact c : req.signataires()) {
            ApiException.ensure(c.nom() != null && !c.nom().trim().isEmpty(),
                    "le nom de chaque signataire est requis");
        }
        for (ReqSousCompte s : req.sousComptes()) {
            ApiException.ensure(s.numero() != null && !s.numero().trim().isEmpty(),
                    "le numéro de chaque sous-compte titres est requis");
            // Le libellé est facultatif (valeur par défaut appliquée à l'insertion).
        }

        // Numeros de compte uniques (verifies en base, anti-collision). Le compte
        // titres est toujours genere ; le compte especes est soit fourni
        // (compteEspecesLie), soit genere distinct du compte titres.
        String compteTitres = generateUniqueAccount();
        String compteEspeces = (req.compteEspecesLie() != null
                && !req.compteEspecesLie().trim().isEmpty())
                ? req.compteEspecesLie().trim()
                : generateUniqueAccount(compteTitres);
        long solde = Math.max(0, req.soldeEspecesInitial() == null ? 0 : req.soldeEspecesInitial());

        // Mot de passe initial genere aleatoirement (jamais code en dur) : il est
        // transmis au client par e-mail/SMS puis devra etre change a la 1re connexion.
        String motDePasseInitial = Tokens.generatePassword();
        String passwordHash = password.hash(motDePasseInitial);

        // Telephone du titulaire (premier signataire) — sert au canal SMS.
        String telephone = first.telephonePortable() != null && !first.telephonePortable().isBlank()
                ? first.telephonePortable().trim()
                : (first.telephoneBureau() != null ? first.telephoneBureau().trim() : null);

        String nom = "PM".equals(typePersonne)
                ? req.raisonSociale().trim() : first.nom().trim();
        String prenom = "PM".equals(typePersonne)
                ? null : (first.prenom() == null ? null : first.prenom().trim());

        UUID userId = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, role, nom, prenom, compte_titres, "
                        + "compte_especes, solde, categorie, type_compte, telephone, "
                        + "must_change_password, initial_password_enc) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?, TRUE, ?) RETURNING id",
                UUID.class, email, passwordHash, role, nom, prenom, compteTitres, compteEspeces,
                solde, categorie, typeCompte, telephone, cipher.encrypt(motDePasseInitial));

        jdbc.update("INSERT INTO client_profiles (user_id, type_personne, raison_sociale, rccm, "
                        + "compte_statut, created_by) VALUES (?,?,?,?, 'ACTIF', ?)",
                userId, typePersonne, req.raisonSociale().trim(),
                trimToNull(req.rccm()), creator.id());

        ReqAdresse a = req.adresse();
        if (a != null && a.rue() != null && !a.rue().trim().isEmpty()) {
            String defaultKind = "PM".equals(typePersonne) ? "SIEGE" : "DOMICILE";
            jdbc.update("INSERT INTO client_adresses (user_id, type, residence, rue, code_postal, "
                            + "ville, pays, ordre) VALUES (?,?,?,?,?,?,?,0)",
                    userId, a.type() != null ? a.type() : defaultKind, a.residence(),
                    a.rue().trim(), a.codePostal(),
                    a.ville() != null ? a.ville().trim() : "",
                    a.pays() != null ? a.pays().trim() : "Cameroun");
        }

        List<ReqContact> signataires = req.signataires();
        for (int i = 0; i < signataires.size(); i++) {
            ReqContact c = signataires.get(i);
            jdbc.update("INSERT INTO client_contacts (user_id, type, nom, prenom, civilite, fonction, "
                            + "telephone_portable, telephone_domicile, telephone_bureau, email, whatsapp, "
                            + "piece_identite, numero_piece, date_validite_piece, lien_parente, ordre) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    userId, c.type() != null ? c.type() : "TITULAIRE", c.nom().trim(),
                    trimToNull(c.prenom()), c.civilite(), c.fonction(), c.telephonePortable(),
                    c.telephoneDomicile(), c.telephoneBureau(), c.email(), c.whatsapp(),
                    c.pieceIdentite(), c.numeroPiece(), c.dateValiditePiece(), c.lienParente(), i);
        }

        List<ReqSousCompte> sousComptes = req.sousComptes();
        for (int i = 0; i < sousComptes.size(); i++) {
            ReqSousCompte s = sousComptes.get(i);
            LocalDate dateOuv;
            try {
                dateOuv = s.dateOuverture() != null
                        ? LocalDate.parse(s.dateOuverture()) : LocalDate.now();
            } catch (RuntimeException e) {
                dateOuv = LocalDate.now();
            }
            jdbc.update("INSERT INTO sous_comptes_titres (user_id, numero, libelle, type, statut, "
                            + "date_ouverture, positions_count, valeur_totale, observations, ordre) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    userId, s.numero().trim(),
                    (s.libelle() != null && !s.libelle().trim().isEmpty())
                            ? s.libelle().trim() : "Compte de conservation",
                    s.type() != null ? s.type() : "CONSERVATION",
                    s.statut() != null ? s.statut() : "ACTIF", dateOuv,
                    Math.max(0, s.positionsCount() == null ? 0 : s.positionsCount()),
                    Math.max(0, s.valeurTotale() == null ? 0 : s.valeurTotale()),
                    trimToNull(s.observations()), i);
        }

        // Compte JOINT : chaque co-signataire au-dela du 1er (qui est le titulaire
        // principal/login) recoit SON propre login, rattache au meme compte-titres
        // via account_holder_id — pour la tracabilite et la double signature des
        // ordres. Ses identifiants provisoires lui sont envoyes par e-mail.
        if ("JOINT".equals(typeCompte) || "INDIVIS".equals(typeCompte)) {
            for (int i = 1; i < signataires.size(); i++) {
                ReqContact cs = signataires.get(i);
                String csEmail = cs.email() == null ? "" : cs.email().trim().toLowerCase();
                if (!csEmail.contains("@")) continue;        // pas d'e-mail -> pas de login
                Long taken = jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?",
                        Long.class, csEmail);
                if (taken != null && taken > 0) continue;     // e-mail deja utilise -> ignore
                String csPwd = Tokens.generatePassword();
                String csTel = cs.telephonePortable() != null && !cs.telephonePortable().isBlank()
                        ? cs.telephonePortable().trim()
                        : (cs.telephoneBureau() != null ? cs.telephoneBureau().trim() : null);
                String csNom = cs.nom().trim();
                String csPrenom = cs.prenom() == null ? null : cs.prenom().trim();
                jdbc.update("INSERT INTO users (email, password_hash, role, nom, prenom, telephone, "
                                + "account_holder_id, must_change_password, initial_password_enc) "
                                + "VALUES (?,?,?,?,?,?,?, TRUE, ?)",
                        csEmail, password.hash(csPwd), role, csNom, csPrenom, csTel, userId,
                        cipher.encrypt(csPwd));
                String csNomComplet = csPrenom != null ? csNom + " " + csPrenom : csNom;
                credentials.sendInitialPassword(csEmail, csTel, csNomComplet, csPwd, true);
            }
        }

        audit.log(creator.id().toString(), "CREATION_CLIENT_" + typePersonne,
                AuditService.SUCCES, email, ip.value());

        // Transmission du mot de passe provisoire au client (e-mail + SMS prepare).
        String nomComplet = prenom != null ? nom + " " + prenom : nom;
        credentials.sendInitialPassword(email, telephone, nomComplet, motDePasseInitial, true);

        ClientDossier dossier = loadDossiers(PROFILE_SELECT + " AND u.id = ?", userId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("dossier introuvable après création"));
        return ResponseEntity.status(HttpStatus.CREATED).body(dossier);
    }

    // ───────────────────────────── Helpers ──────────────────────────────────

    /**
     * Genere un numero de compte ("037 10001 NNNNNNNNNNN") garanti unique en base
     * — il ne doit apparaitre ni en compte_titres ni en compte_especes — et
     * distinct des numeros deja choisis dans le meme onboarding. Remplace l'ancien
     * derive de l'horodatage, qui pouvait collisionner silencieusement.
     */
    private String generateUniqueAccount(String... avoid) {
        java.util.Set<String> taboo = java.util.Set.of(avoid);
        for (int i = 0; i < 25; i++) {
            long n = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 100_000_000_000L);
            String candidate = String.format("037 10001 %011d", n);
            if (taboo.contains(candidate)) {
                continue;
            }
            Long exists = jdbc.queryForObject(
                    "SELECT count(*) FROM users WHERE compte_titres = ? OR compte_especes = ?",
                    Long.class, candidate, candidate);
            if (exists != null && exists == 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("Impossible de générer un numéro de compte unique.");
    }

    /**
     * Charge une page de dossiers (profils + enfants groupes) en se limitant
     * aux user_ids resultant du SELECT de premier niveau, afin que la
     * pagination s'applique aussi aux requetes d'enfants.
     */
    private List<ClientDossier> loadDossiers(String profileSql, Object... profileArgs) {
        // 1) Charge la page de profils.
        List<Object[]> rows = jdbc.query(profileSql, (rs, n) -> new Object[]{
                rs.getObject("user_id", UUID.class),
                rs.getString("type_personne"),
                rs.getString("raison_sociale"),
                rs.getString("rccm"),
                rs.getObject("created_by", UUID.class),
                rs.getString("created_by_nom"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("compte_titres"),
                rs.getString("type_compte"),
                rs.getString("compte_statut"),
                rs.getObject("date_ouverture", LocalDate.class),
                rs.getString("categorie"),
                rs.getString("compte_especes"),
                rs.getObject("solde", Long.class),
        }, profileArgs);

        if (rows.isEmpty()) {
            return List.of();
        }

        // 2) Charge les enfants restreints aux user_ids de la page courante.
        List<UUID> ids = rows.stream().map(r -> (UUID) r[0]).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] idArgs = ids.toArray();

        Map<UUID, List<AdresseDto>> adresses = new HashMap<>();
        jdbc.query("SELECT user_id, type, residence, rue, code_postal, ville, pays "
                + "FROM client_adresses WHERE user_id IN (" + placeholders + ") "
                + "ORDER BY user_id, ordre", rs -> {
            adresses.computeIfAbsent(rs.getObject("user_id", UUID.class), k -> new ArrayList<>())
                    .add(new AdresseDto(rs.getString("type"), rs.getString("residence"),
                            rs.getString("rue"), rs.getString("code_postal"), rs.getString("ville"),
                            rs.getString("pays")));
        }, idArgs);

        Map<UUID, List<ContactDto>> contacts = new HashMap<>();
        jdbc.query("SELECT id, user_id, type, nom, prenom, civilite, fonction, telephone_portable, "
                + "telephone_domicile, telephone_bureau, email, whatsapp, piece_identite, numero_piece, "
                + "date_validite_piece, lien_parente FROM client_contacts "
                + "WHERE user_id IN (" + placeholders + ") ORDER BY user_id, ordre", rs -> {
            contacts.computeIfAbsent(rs.getObject("user_id", UUID.class), k -> new ArrayList<>())
                    .add(new ContactDto(rs.getObject("id", UUID.class), rs.getString("type"),
                            rs.getString("nom"), rs.getString("prenom"), rs.getString("civilite"),
                            rs.getString("fonction"), rs.getString("telephone_portable"),
                            rs.getString("telephone_domicile"), rs.getString("telephone_bureau"),
                            rs.getString("email"), rs.getString("whatsapp"),
                            rs.getString("piece_identite"), rs.getString("numero_piece"),
                            rs.getString("date_validite_piece"), rs.getString("lien_parente")));
        }, idArgs);

        Map<UUID, List<SousCompteDto>> sousComptes = new HashMap<>();
        jdbc.query("SELECT id, user_id, numero, libelle, type, statut, date_ouverture, "
                + "positions_count, valeur_totale, observations FROM sous_comptes_titres "
                + "WHERE user_id IN (" + placeholders + ") ORDER BY user_id, ordre", rs -> {
            sousComptes.computeIfAbsent(rs.getObject("user_id", UUID.class), k -> new ArrayList<>())
                    .add(new SousCompteDto(rs.getObject("id", UUID.class), rs.getString("numero"),
                            rs.getString("libelle"), rs.getString("type"), rs.getString("statut"),
                            rs.getObject("date_ouverture", LocalDate.class),
                            rs.getInt("positions_count"), rs.getLong("valeur_totale"),
                            rs.getString("observations")));
        }, idArgs);

        // 3) Assemble les dossiers en preservant l'ordre du SELECT principal.
        List<ClientDossier> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            UUID uid = (UUID) r[0];
            String typeCompte = (String) r[8];
            String categorie = (String) r[11];
            Long solde = (Long) r[13];
            CompteDto compte = new CompteDto(
                    uid,
                    r[7] != null ? (String) r[7] : "",
                    typeCompte != null ? typeCompte : "INDIVIDUEL",
                    (String) r[9],
                    (LocalDate) r[10],
                    categorie != null ? categorie : "NON_QUALIFIE",
                    r[12] != null ? (String) r[12] : "",
                    solde != null ? solde : 0L,
                    adresses.getOrDefault(uid, List.of()),
                    contacts.getOrDefault(uid, List.of()),
                    sousComptes.getOrDefault(uid, List.of()));
            result.add(new ClientDossier(uid, (String) r[1], (String) r[2], (String) r[3],
                    (UUID) r[4], (String) r[5], (OffsetDateTime) r[6], compte));
        }
        return result;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
