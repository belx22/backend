package cm.afriland.titres.support;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import cm.afriland.titres.error.ApiException;

/**
 * Referentiel des clients Afriland deja existants (table {@code clients_fb}),
 * alimente par import Excel/CSV depuis la « BASE CLIENTS » de la banque.
 *
 * <p>C'est le pivot de l'auto-inscription : le numero de compte saisi par le
 * prospect y est recherche pour savoir s'il est <b>deja client</b> (on reprend
 * alors ses informations) ou un <b>nouveau client</b> (a orienter en agence).</p>
 */
@Repository
public class ClientsFbRepository {

    /** Code banque Afriland — prefixe du RIB, absent du fichier source. */
    public static final String CODE_BANQUE = "10005";

    /** Longueur EXACTE imposee au compte de depot (nomenclature bancaire, ex. CCEICMCXXnnn). */
    public static final int LONGUEUR_COMPTE_DEPOT = 12;

    private final JdbcTemplate jdbc;

    public ClientsFbRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Une ligne du referentiel, telle qu'exploitee par l'inscription. */
    public record ClientFb(UUID id, String nomPrenom, String numeroCompte, String compteEspeces,
                           String agence, String matricule, String categorie, String compteDepot,
                           Boolean assujettiTaxes, String localisation, String dirigeant,
                           String telephone1, String telephone2, String email) {
    }

    /** Issue d'un import ligne par ligne (cf. {@link #upsert}). */
    public enum UpsertOutcome {
        /** Ligne inseree ou mise a jour avec succes. */
        IMPORTE,
        /** Numero de compte absent ou mal forme (18 chiffres attendus). */
        NUMERO_INVALIDE,
        /** Compte de depot present mais de longueur != 12 (nomenclature bancaire). */
        DEPOT_INVALIDE,
        /** Meme nom+prenom, categorie et telephone qu'une AUTRE ligne deja
         *  en base : tres probablement le meme client saisi deux fois sous un
         *  numero de compte different (erreur de saisie du fichier source). */
        DOUBLON_IDENTITE,
    }

    /**
     * Retrouve un client par son compte especes (23 chiffres) OU par le numero
     * brut du fichier (18 chiffres) : le prospect saisit le RIB complet, mais le
     * referentiel est indexe sur les deux.
     */
    public Optional<ClientFb> parCompte(String compte) {
        String c = normaliser(compte);
        if (c == null) {
            return Optional.empty();
        }
        String numero = c.length() == 23 && c.startsWith(CODE_BANQUE) ? c.substring(5) : c;
        List<ClientFb> rows = jdbc.query(
                "SELECT id, nom_prenom, numero_compte, compte_especes, agence, matricule, "
                        + "categorie, compte_depot, assujetti_taxes, localisation, dirigeant, "
                        + "telephone1, telephone2, email FROM clients_fb WHERE numero_compte = ?",
                (rs, n) -> new ClientFb(
                        rs.getObject("id", UUID.class), rs.getString("nom_prenom"),
                        rs.getString("numero_compte"), rs.getString("compte_especes"),
                        rs.getString("agence"), rs.getString("matricule"), rs.getString("categorie"),
                        rs.getString("compte_depot"), (Boolean) rs.getObject("assujetti_taxes"),
                        rs.getString("localisation"), rs.getString("dirigeant"),
                        rs.getString("telephone1"), rs.getString("telephone2"),
                        rs.getString("email")),
                numero);
        return rows.stream().findFirst();
    }

    /**
     * Insere ou met a jour une ligne du referentiel (import idempotent : re-importer
     * le meme fichier rafraichit les lignes au lieu de les dupliquer).
     *
     * @return l'issue de l'import pour cette ligne (cf. {@link UpsertOutcome})
     */
    public UpsertOutcome upsert(Map<String, Object> ligne, UUID par) {
        String numero = normaliser(texte(ligne.get("numeroCompte")));
        if (numero == null || !numero.matches("\\d{18}")) {
            return UpsertOutcome.NUMERO_INVALIDE;   // ligne inexploitable : signalee a l'agent, pas importee
        }

        // Compte de depot : quand il est renseigne, il doit respecter la nomenclature
        // bancaire (exactement 12 caracteres). Sinon la ligne est ecartee.
        String compteDepot = texte(ligne.get("compteDepot"));
        if (compteDepot != null && compteDepot.length() != LONGUEUR_COMPTE_DEPOT) {
            return UpsertOutcome.DEPOT_INVALIDE;
        }

        String nomPrenom = texte(ligne.get("nomPrenom"));
        String categorie = texte(ligne.get("categorie"));
        String telephone1 = texte(ligne.get("telephone1"));

        // Doublon d'identite : le meme client ne doit pas se retrouver deux fois
        // sous des numeros de compte differents (erreur de saisie frequente dans
        // le fichier source). On ne bloque que si les 3 champs identifiants sont
        // TOUS renseignes ET correspondent a une AUTRE ligne deja en base — une
        // comparaison avec des champs vides ne permettrait aucune conclusion.
        if (nomPrenom != null && categorie != null && telephone1 != null) {
            Long doublons = jdbc.queryForObject(
                    "SELECT count(*) FROM clients_fb WHERE lower(nom_prenom) = lower(?) "
                            + "AND lower(categorie) = lower(?) AND telephone1 = ? AND numero_compte <> ?",
                    Long.class, nomPrenom, categorie, telephone1, numero);
            if (doublons != null && doublons > 0) {
                return UpsertOutcome.DOUBLON_IDENTITE;
            }
        }

        jdbc.update("""
                INSERT INTO clients_fb (nom_prenom, numero_compte, agence, matricule, categorie,
                        compte_depot, assujetti_taxes, localisation, dirigeant, telephone1,
                        telephone2, email, imported_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (numero_compte) DO UPDATE SET
                    nom_prenom = EXCLUDED.nom_prenom, agence = EXCLUDED.agence,
                    matricule = EXCLUDED.matricule, categorie = EXCLUDED.categorie,
                    compte_depot = EXCLUDED.compte_depot,
                    assujetti_taxes = EXCLUDED.assujetti_taxes,
                    localisation = EXCLUDED.localisation, dirigeant = EXCLUDED.dirigeant,
                    telephone1 = EXCLUDED.telephone1, telephone2 = EXCLUDED.telephone2,
                    email = EXCLUDED.email, imported_at = now(), imported_by = EXCLUDED.imported_by
                """,
                nomPrenom, numero,
                agence(ligne.get("agence"), numero), texte(ligne.get("matricule")),
                categorie, compteDepot,
                oui(ligne.get("assujettiTaxes")), texte(ligne.get("localisation")),
                texte(ligne.get("dirigeant")), telephone1,
                texte(ligne.get("telephone2")), texte(ligne.get("email")), par);
        return UpsertOutcome.IMPORTE;
    }

    /**
     * Met a jour une fiche EXISTANTE du referentiel (edition back-office), sans la
     * recreer. Le numero de compte doit rester valide (18 chiffres) et le compte de
     * depot, s'il est renseigne, respecter la nomenclature (exactement 12 caracteres).
     *
     * @throws ApiException 400 numero/depot invalide, 404 fiche introuvable,
     *                      409 si le numero entre en conflit avec une autre fiche.
     */
    public void update(java.util.UUID id, Map<String, Object> data, UUID par) {
        String numero = normaliser(texte(data.get("numeroCompte")));
        if (numero == null || !numero.matches("\\d{18}")) {
            throw ApiException.badRequest("Le numéro de compte doit comporter 18 chiffres.");
        }
        String compteDepot = texte(data.get("compteDepot"));
        if (compteDepot != null && compteDepot.length() != LONGUEUR_COMPTE_DEPOT) {
            throw ApiException.badRequest(
                    "Le compte de dépôt doit contenir exactement " + LONGUEUR_COMPTE_DEPOT + " caractères.");
        }
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE clients_fb SET nom_prenom = ?, numero_compte = ?, agence = ?,
                        matricule = ?, categorie = ?, compte_depot = ?, assujetti_taxes = ?,
                        localisation = ?, dirigeant = ?, telephone1 = ?, telephone2 = ?,
                        email = ?, imported_at = now(), imported_by = ?
                    WHERE id = ?
                    """,
                    texte(data.get("nomPrenom")), numero, agence(data.get("agence"), numero),
                    texte(data.get("matricule")), texte(data.get("categorie")), compteDepot,
                    // A l'edition, l'assujettissement arrive deja typé (Boolean) — pas « OUI/NON ».
                    data.get("assujettiTaxes") instanceof Boolean b ? b : oui(data.get("assujettiTaxes")),
                    texte(data.get("localisation")),
                    texte(data.get("dirigeant")), texte(data.get("telephone1")),
                    texte(data.get("telephone2")), texte(data.get("email")), par, id);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("Ce numéro de compte est déjà utilisé par une autre fiche.");
        }
        if (updated == 0) {
            throw ApiException.notFound("Fiche introuvable.");
        }
    }

    /** L'agence est les 5 premiers chiffres du numero — la colonne du fichier n'est qu'un rappel. */
    private static String agence(Object fourni, String numero) {
        String a = normaliser(texte(fourni));
        return a != null && a.matches("\\d{5}") ? a : numero.substring(0, 5);
    }

    /** Le fichier note l'assujettissement « OUI » / « NON » ; une case vide reste inconnue. */
    private static Boolean oui(Object v) {
        String s = texte(v);
        if (s == null) {
            return null;
        }
        return s.trim().equalsIgnoreCase("OUI") ? Boolean.TRUE : Boolean.FALSE;
    }

    /** Retire espaces et separateurs : le fichier ecrit « 00090 56010090000 48 ». */
    private static String normaliser(String s) {
        if (s == null) {
            return null;
        }
        String c = s.replaceAll("[^0-9]", "");
        return c.isEmpty() ? null : c;
    }

    private static String texte(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
