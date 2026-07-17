package cm.afriland.titres.support;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import cm.afriland.titres.error.ApiException;

/**
 * Genere la « Situation de portefeuille titres / Securities Account Statement »
 * en PDF, cote serveur, a partir des seules donnees de la base.
 *
 * <p>Editee par l'agent back-office pour un client donne puis deposee comme
 * livrable (le client la retrouve dans son espace). Comme l'attestation de
 * propriete, la generation est serveur : le releve engage la banque en tant que
 * teneur de compte conservateur.</p>
 *
 * <p><b>Valorisation</b> : chaque ligne porte un <em>Solde</em> (quantite) et une
 * <em>Valorisation</em> (valeur en base, issue de l'adjudication ou de l'import).
 * Le <em>Cours</em> affiche est derive — {@code Valorisation / Solde} — pour
 * respecter l'arithmetique du releve sans dependre d'un flux de cotation.</p>
 */
@Service
public class SituationPortefeuillePdfService {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Positions du client, derivees des ordres adjuges (memes regles que le portefeuille). */
    private static final String POSITIONS_SQL = """
            SELECT e.code AS emission_code, e.libelle, e.nature, e.date_echeance,
                   sum(coalesce(o.volume_alloue, 0))::bigint  AS volume,
                   sum(coalesce(o.montant_adjuge, 0))::bigint AS valorisation
              FROM orders o
              JOIN emissions e ON e.id = o.emission_id
             WHERE o.client_id = ?
               AND o.status IN ('TOTALEMENT_RETENU', 'PARTIELLEMENT_RETENU')
             GROUP BY e.id, e.code, e.libelle, e.nature, e.date_echeance
            HAVING sum(coalesce(o.volume_alloue, 0)) > 0
             ORDER BY e.date_echeance NULLS LAST, e.code
            """;

    private final JdbcTemplate jdbc;

    public SituationPortefeuillePdfService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Client(String nom, String telephone, String adresse, String agence,
                          String compteTitres, String compteEspeces) {
    }

    private record Ligne(String codeValeur, String type, LocalDate dateEcheance,
                         long solde, long cours, long valorisation) {
    }

    /** Resultat de l'edition : PDF en data URI, taille lisible, total et nb de lignes. */
    public record Situation(String dataUri, String taille, long valeurGlobale, int nbLignes) {
    }

    /**
     * Edite la situation de portefeuille du client {@code clientId} a la date
     * {@code periode} (par defaut aujourd'hui). {@code service} est un libelle
     * facultatif (ex. « TRESORERIE ») porte sur l'en-tete.
     */
    public Situation generer(UUID clientId, LocalDate periode, String service) {
        LocalDate date = periode != null ? periode : LocalDate.now(ZoneOffset.UTC);

        Client client = jdbc.query(
                        "SELECT u.nom, u.prenom, u.telephone, u.compte_titres, u.compte_especes, "
                                + "a.rue, a.ville, a.pays "
                                + "FROM users u "
                                + "LEFT JOIN client_adresses a ON a.user_id = u.id "
                                + "WHERE u.id = ? ORDER BY a.id LIMIT 1",
                        (rs, n) -> {
                            String nom = rs.getString("nom");
                            String prenom = rs.getString("prenom");
                            String complet = (prenom != null && !prenom.isBlank())
                                    ? (nom + " " + prenom) : nom;
                            String compteEspeces = rs.getString("compte_especes");
                            return new Client(
                                    complet,
                                    valeurOuTiret(rs.getString("telephone")),
                                    composeAdresse(rs.getString("rue"), rs.getString("ville"),
                                            rs.getString("pays")),
                                    agence(compteEspeces),
                                    valeurOuTiret(rs.getString("compte_titres")),
                                    compteEspecesAffiche(compteEspeces));
                        }, clientId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Client introuvable."));

        List<Ligne> lignes = jdbc.query(POSITIONS_SQL, (rs, n) -> {
            long solde = rs.getLong("volume");
            long valorisation = rs.getLong("valorisation");
            long cours = solde > 0 ? Math.round((double) valorisation / solde) : 0;
            return new Ligne(
                    codeValeur(rs.getString("emission_code"), rs.getString("libelle")),
                    typeLabel(rs.getString("nature")),
                    rs.getObject("date_echeance", LocalDate.class),
                    solde, cours, valorisation);
        }, clientId);

        long valeurGlobale = lignes.stream().mapToLong(Ligne::valorisation).sum();
        byte[] pdf = rendre(client, lignes, date, service, valeurGlobale);
        String dataUri = "data:application/pdf;base64,"
                + java.util.Base64.getEncoder().encodeToString(pdf);
        return new Situation(dataUri, tailleLisible(pdf.length), valeurGlobale, lignes.size());
    }

    // ── Rendu ────────────────────────────────────────────────────────────────

    private byte[] rendre(Client client, List<Ligne> lignes, LocalDate periode,
                          String service, long valeurGlobale) {
        Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
        Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font petit = FontFactory.getFont(FontFactory.HELVETICA, 7);
        Font entete = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        Font libelleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        java.awt.Color rouge = new java.awt.Color(0xC1, 0x0F, 0x1A);
        java.awt.Color griseClaire = new java.awt.Color(0xF3, 0xF4, 0xF6);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Paysage : le tableau des avoirs compte 7 colonnes larges.
        Document doc = new Document(PageSize.A4.rotate(), 36, 36, 44, 40);
        PdfWriter.getInstance(doc, out);
        doc.open();

        doc.add(centre("SITUATION DE PORTEFEUILLE TITRES // SECURITIES ACCOUNT STATEMENT", h1));
        doc.add(centre("Afriland First Bank — Teneur de compte conservateur (COSUMAF-I.MFAC01/2015)", petit));
        doc.add(espace(6));
        Paragraph periodeP = centre("PÉRIODE : " + periode.format(JOUR), h2);
        doc.add(periodeP);
        doc.add(espace(12));

        // ── Bloc identite (gauche) + avis bilingue (droite) ───────────────────
        PdfPTable enteteTable = new PdfPTable(new float[] { 1.15f, 1f });
        enteteTable.setWidthPercentage(100);
        enteteTable.getDefaultCell().setBorder(0);

        PdfPCell identite = new PdfPCell(identiteTable(client, lignes.size(), valeurGlobale,
                service, libelleFont, normal, entete, griseClaire, rouge));
        identite.setBorder(0);
        identite.setPaddingRight(10);
        enteteTable.addCell(identite);

        PdfPCell avis = new PdfPCell();
        avis.setBorder(0);
        avis.setPaddingLeft(10);
        avis.addElement(paragraphe("NB : le présent relevé traduit la situation de votre portefeuille "
                + "titres dans nos livres à la date ci-dessus indiquée. Sauf réclamation de votre part "
                + "dans un délai de 30 jours à compter de cette date, nous considérons ce relevé comme "
                + "approuvé.", petit));
        avis.addElement(espace(6));
        avis.addElement(paragraphe("NOTICE: This statement reflects the status of your securities "
                + "portfolio in our books on the date indicated above. Except claim by you within 30 "
                + "days as of that date, we believe this statement to be approved.", petit));
        enteteTable.addCell(avis);
        doc.add(enteteTable);
        doc.add(espace(14));

        doc.add(new Paragraph("Détails des avoirs en compte", h2));
        doc.add(espace(6));

        // ── Tableau des avoirs ────────────────────────────────────────────────
        PdfPTable table = new PdfPTable(new float[] { 3.2f, 1.2f, 1.2f, 2f, 1.1f, 1.1f, 1.5f });
        table.setWidthPercentage(100);
        String[][] colonnes = {
                { "Code valeur", "securities reference" },
                { "Date Dern. mise à jour", "last update" },
                { "Date d'échéance", "maturity" },
                { "Type", "type" },
                { "Solde", "balance" },
                { "Cours", "rate" },
                { "Valorisation", "amount" },
        };
        for (String[] col : colonnes) {
            table.addCell(celluleEntete(col[0], col[1], entete, petit, griseClaire));
        }

        for (Ligne l : lignes) {
            table.addCell(cellule(l.codeValeur(), petit, Element.ALIGN_LEFT));
            table.addCell(cellule(periode.format(JOUR), petit, Element.ALIGN_CENTER));
            table.addCell(cellule(l.dateEcheance() == null ? "N/A" : l.dateEcheance().format(JOUR),
                    petit, Element.ALIGN_CENTER));
            table.addCell(cellule(l.type(), petit, Element.ALIGN_LEFT));
            table.addCell(cellule(montant(l.solde()), petit, Element.ALIGN_RIGHT));
            table.addCell(cellule(montant(l.cours()), petit, Element.ALIGN_RIGHT));
            table.addCell(cellule(montant(l.valorisation()), petit, Element.ALIGN_RIGHT));
        }

        // Ligne de total (fond rouge, texte blanc) sur toute la largeur.
        PdfPCell totalLabel = new PdfPCell(new Phrase("TOTAL CLIENT EN FCFA", enteteBlanc()));
        totalLabel.setColspan(6);
        totalLabel.setBackgroundColor(rouge);
        totalLabel.setPadding(5);
        totalLabel.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.addCell(totalLabel);
        PdfPCell totalValeur = new PdfPCell(new Phrase(montant(valeurGlobale), enteteBlanc()));
        totalValeur.setBackgroundColor(rouge);
        totalValeur.setPadding(5);
        totalValeur.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(totalValeur);

        doc.add(table);
        doc.add(espace(16));
        doc.add(paragraphe("Document édité électroniquement par le système — Afriland First Bank, "
                + "teneur de compte conservateur. Toute altération le rend nul.", petit));

        doc.close();
        return out.toByteArray();
    }

    /** Bloc d'identite gauche (paires libelle bilingue / valeur), le total en rouge. */
    private PdfPTable identiteTable(Client client, int nbLignes, long valeurGlobale, String service,
                                    Font cle, Font valeur, Font entete, java.awt.Color grise,
                                    java.awt.Color rouge) {
        PdfPTable t = new PdfPTable(new float[] { 1.3f, 1.7f });
        t.setWidthPercentage(100);
        ligneIdentite(t, "Nom & prénom / name & surname", client.nom(), cle, valeur, grise);
        ligneIdentite(t, "Adresse / address", client.adresse(), cle, valeur, grise);
        ligneIdentite(t, "N° téléphone / phone", client.telephone(), cle, valeur, grise);
        ligneIdentite(t, "Agence de domiciliation / agency", client.agence(), cle, valeur, grise);
        ligneIdentite(t, "Compte titre n° / securities account", client.compteTitres(), cle, valeur, grise);
        ligneIdentite(t, "Compte espèce lié / current account", client.compteEspeces(), cle, valeur, grise);
        ligneIdentite(t, "Service", valeurOuTiret(service), cle, valeur, grise);
        ligneIdentite(t, "Nombre de lignes du portefeuille / portfolio lines",
                String.valueOf(nbLignes), cle, valeur, grise);

        PdfPCell cGlobalLabel = new PdfPCell(new Phrase("VALEUR GLOBALE DES ACTIFS", entete));
        cGlobalLabel.setBackgroundColor(grise);
        cGlobalLabel.setPadding(4);
        Font enteteRouge = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        enteteRouge.setColor(rouge);
        PdfPCell cGlobalVal = new PdfPCell(new Phrase(montant(valeurGlobale) + " FCFA", enteteRouge));
        cGlobalVal.setPadding(4);
        cGlobalVal.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(cGlobalLabel);
        t.addCell(cGlobalVal);
        return t;
    }

    private static void ligneIdentite(PdfPTable t, String cleTxt, String valTxt, Font cle,
                                      Font valeur, java.awt.Color grise) {
        PdfPCell c = new PdfPCell(new Phrase(cleTxt, cle));
        c.setBackgroundColor(grise);
        c.setPadding(4);
        t.addCell(c);
        PdfPCell v = new PdfPCell(new Phrase(valTxt, valeur));
        v.setPadding(4);
        t.addCell(v);
    }

    // ── Mise en forme ────────────────────────────────────────────────────────

    private static Paragraph centre(String texte, Font f) {
        Paragraph p = new Paragraph(texte, f);
        p.setAlignment(Element.ALIGN_CENTER);
        return p;
    }

    private static Paragraph paragraphe(String texte, Font f) {
        return new Paragraph(texte, f);
    }

    private static Paragraph espace(float hauteur) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(hauteur);
        return p;
    }

    private static PdfPCell cellule(String texte, Font f, int alignement) {
        PdfPCell c = new PdfPCell(new Phrase(texte == null ? "—" : texte, f));
        c.setHorizontalAlignment(alignement);
        c.setPadding(4);
        return c;
    }

    private static PdfPCell celluleEntete(String fr, String en, Font entete, Font petit,
                                          java.awt.Color grise) {
        Paragraph p = new Paragraph();
        p.add(new Phrase(fr + "\n", entete));
        p.add(new Phrase(en, petit));
        PdfPCell c = new PdfPCell(p);
        c.setBackgroundColor(grise);
        c.setPadding(4);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        return c;
    }

    private static Font enteteBlanc() {
        Font f = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        f.setColor(java.awt.Color.WHITE);
        return f;
    }

    /** « Code valeur » = code de l'emission suivi de son libelle (sans repetition). */
    private static String codeValeur(String code, String libelle) {
        String c = code == null ? "" : code.trim();
        String l = libelle == null ? "" : libelle.trim();
        if (c.isEmpty()) return l.isEmpty() ? "—" : l;
        if (l.isEmpty() || l.startsWith(c)) return c + (l.isEmpty() ? "" : " " + l.substring(c.length()).trim());
        return (c + " " + l).trim();
    }

    /** Libelle lisible du type d'instrument, a partir de la nature codee de l'emission. */
    private static String typeLabel(String nature) {
        if (nature == null) return "—";
        return switch (nature.trim().toUpperCase(Locale.ROOT)) {
            case "OTA" -> "Obligation du Trésor Assimilable";
            case "BTA" -> "Bon du Trésor Assimilable";
            case "ACTION" -> "Action cotée";
            case "OBLIGATION" -> "Emprunt obligataire";
            default -> nature.trim();
        };
    }

    /** Agence = les 5 chiffres suivant le code banque, dans un compte a 23 chiffres (RIB). */
    private static String agence(String compteEspeces) {
        String d = digits(compteEspeces);
        return d.length() >= 10 ? d.substring(5, 10) : "—";
    }

    /** Compte espece affiche = numero sans le code banque (agence + compte + cle). */
    private static String compteEspecesAffiche(String compteEspeces) {
        String d = digits(compteEspeces);
        if (d.length() >= 23) {
            String reste = d.substring(5);
            return reste.substring(0, 5) + " " + reste.substring(5, 16) + " " + reste.substring(16);
        }
        return valeurOuTiret(compteEspeces);
    }

    private static String composeAdresse(String rue, String ville, String pays) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] { rue, ville, pays }) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(part.trim());
            }
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private static String valeurOuTiret(String s) {
        return (s == null || s.isBlank()) ? "—" : s.trim();
    }

    /** Separateur de milliers par espace insecable — convention francaise (FCFA). */
    private static String montant(long valeur) {
        return String.format(Locale.FRANCE, "%,d", valeur).replace(' ', ' ');
    }

    private static String tailleLisible(int octets) {
        if (octets < 1024) return octets + " o";
        return Math.round(octets / 1024.0) + " Ko";
    }
}
