package cm.afriland.titres.support;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * Genere l'attestation de propriete de titres (CSFT §M4-F02) en PDF, cote
 * serveur, a partir des seules donnees de la base.
 *
 * <p>La generation est volontairement serveur : l'attestation engage la banque
 * en tant que teneur de compte conservateur. Un rendu cote navigateur, puis
 * televerse, laisserait le porteur maitre du contenu de sa propre attestation.
 * Ici le client ne fournit rien — il declenche seulement l'edition.
 */
@Service
public class AttestationPdfService {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Positions du porteur, derivees des ordres adjuges (memes regles que le
     * portefeuille). Le {@code %s} accueille un filtre optionnel par titre (ISIN)
     * pour editer une attestation portant sur un seul titre.
     */
    private static final String POSITIONS_SQL = """
            SELECT e.code AS emission_code, o.isin, e.nature, e.pays_code, e.date_echeance,
                   sum(coalesce(o.volume_alloue, 0))::bigint       AS volume,
                   e.valeur_nominale_unitaire                      AS valeur_nominale,
                   sum(coalesce(o.montant_adjuge, 0))::bigint      AS valeur_totale
              FROM orders o
              JOIN emissions e ON e.id = o.emission_id
             WHERE o.client_id = ?
               AND o.status IN ('TOTALEMENT_RETENU', 'PARTIELLEMENT_RETENU')
               %s
             GROUP BY e.id, o.isin, e.code, e.nature, e.pays_code, e.date_echeance,
                      e.valeur_nominale_unitaire
            HAVING sum(coalesce(o.volume_alloue, 0)) > 0
             ORDER BY e.date_echeance
            """;

    private final JdbcTemplate jdbc;

    public AttestationPdfService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Ligne(String emissionCode, String isin, String nature, String emetteur,
                         LocalDate dateEcheance, long volume, long valeurNominale, long valeurTotale) {
    }

    private record Titulaire(String nom, String compteTitres) {
    }

    /** Resultat de l'edition : le PDF encode en data URI, et sa taille affichable. */
    public record Attestation(String dataUri, String taille) {
    }

    /**
     * Edite l'attestation du porteur {@code clientId} a partir de la base.
     *
     * @throws ApiException si le porteur ne detient aucune position : une
     *                      attestation de propriete sans titre n'a pas d'objet.
     */
    public Attestation generer(UUID clientId) {
        return generer(clientId, null);
    }

    /**
     * Edite l'attestation du porteur {@code clientId}. Si {@code isin} est fourni,
     * l'attestation ne porte que sur ce titre (edition back-office « pour un titre
     * possede ») ; sinon elle couvre toutes ses positions.
     *
     * @throws ApiException si le porteur ne detient aucune position concernee.
     */
    public Attestation generer(UUID clientId, String isin) {
        Titulaire titulaire = jdbc.query(
                        "SELECT nom, prenom, compte_titres FROM users WHERE id = ?",
                        (rs, n) -> {
                            String nom = rs.getString("nom");
                            String prenom = rs.getString("prenom");
                            String complet = (prenom != null && !prenom.isBlank())
                                    ? (prenom + " " + nom) : nom;
                            String ct = rs.getString("compte_titres");
                            return new Titulaire(complet, (ct == null || ct.isBlank()) ? "—" : ct);
                        }, clientId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Titulaire introuvable."));

        boolean unTitre = isin != null && !isin.isBlank();
        String sql = String.format(POSITIONS_SQL, unTitre ? "AND o.isin = ?" : "");
        Object[] args = unTitre ? new Object[] { clientId, isin } : new Object[] { clientId };

        List<Ligne> lignes = jdbc.query(sql, (rs, n) -> new Ligne(
                rs.getString("emission_code"),
                rs.getString("isin"),
                rs.getString("nature"),
                Countries.label(rs.getString("pays_code")),
                rs.getObject("date_echeance", LocalDate.class),
                rs.getLong("volume"),
                rs.getLong("valeur_nominale"),
                rs.getLong("valeur_totale")), args);

        ApiException.ensure(!lignes.isEmpty(), unTitre
                ? "Le client ne détient pas ce titre : attestation sans objet."
                : "Aucune position en portefeuille : l'attestation de propriété est sans objet.");

        byte[] pdf = rendre(titulaire, lignes);
        String dataUri = "data:application/pdf;base64,"
                + java.util.Base64.getEncoder().encodeToString(pdf);
        return new Attestation(dataUri, tailleLisible(pdf.length));
    }

    // ── Rendu ────────────────────────────────────────────────────────────────

    private byte[] rendre(Titulaire titulaire, List<Ligne> lignes) {
        Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Font petit = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Font entete = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 56, 48);
        PdfWriter.getInstance(doc, out);
        doc.open();

        doc.add(centre("AFRILAND FIRST BANK", h2));
        doc.add(centre("Spécialiste en Valeurs du Trésor", normal));
        doc.add(centre("Teneur de compte conservateur — agrément COSUMAF-I.MFAC01/2015", petit));
        doc.add(espace(18));

        doc.add(centre("ATTESTATION DE PROPRIÉTÉ DE TITRES", h1));
        doc.add(espace(18));

        doc.add(new Paragraph(
                "Nous soussignés, Afriland First Bank, teneur de compte conservateur, attestons "
                        + "que les titres désignés ci-après sont inscrits en compte au nom du titulaire "
                        + "suivant, à la date d'édition du présent document.", normal));
        doc.add(espace(12));

        doc.add(new Paragraph("Titulaire : " + titulaire.nom(), h2));
        doc.add(new Paragraph("Compte-titres : " + titulaire.compteTitres(), normal));
        doc.add(espace(14));

        PdfPTable table = new PdfPTable(new float[] { 2.4f, 1.6f, 0.9f, 1.5f, 1f, 1.6f, 1.3f });
        table.setWidthPercentage(100);
        for (String col : new String[] {
                "ISIN", "Émission", "Nature", "Émetteur", "Volume", "Valeur totale", "Échéance" }) {
            table.addCell(cellule(col, entete, Element.ALIGN_LEFT, true));
        }

        long totalVolume = 0;
        long totalValeur = 0;
        for (Ligne l : lignes) {
            table.addCell(cellule(l.isin(), petit, Element.ALIGN_LEFT, false));
            table.addCell(cellule(l.emissionCode(), petit, Element.ALIGN_LEFT, false));
            table.addCell(cellule(l.nature(), petit, Element.ALIGN_LEFT, false));
            table.addCell(cellule(l.emetteur(), petit, Element.ALIGN_LEFT, false));
            table.addCell(cellule(montant(l.volume()), petit, Element.ALIGN_RIGHT, false));
            table.addCell(cellule(montant(l.valeurTotale()) + " FCFA", petit, Element.ALIGN_RIGHT, false));
            table.addCell(cellule(l.dateEcheance() == null ? "—" : l.dateEcheance().format(JOUR),
                    petit, Element.ALIGN_LEFT, false));
            totalVolume += l.volume();
            totalValeur += l.valeurTotale();
        }
        doc.add(table);
        doc.add(espace(10));

        doc.add(new Paragraph("Nombre de lignes : " + lignes.size(), normal));
        doc.add(new Paragraph("Volume total : " + montant(totalVolume) + " titres", normal));
        doc.add(new Paragraph("Valeur totale : " + montant(totalValeur) + " FCFA", h2));
        doc.add(espace(24));

        OffsetDateTime maintenant = OffsetDateTime.now(ZoneOffset.UTC);
        doc.add(new Paragraph("Fait à Yaoundé, le " + maintenant.format(JOUR), normal));
        doc.add(espace(8));
        doc.add(new Paragraph(
                "Document édité et signé électroniquement par le système — conforme BEAC / COBAC / "
                        + "COSUMAF. Toute altération le rend nul.", petit));

        doc.close();
        return out.toByteArray();
    }

    // ── Mise en forme ────────────────────────────────────────────────────────

    private static Paragraph centre(String texte, Font f) {
        Paragraph p = new Paragraph(texte, f);
        p.setAlignment(Element.ALIGN_CENTER);
        return p;
    }

    private static Paragraph espace(float hauteur) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(hauteur);
        return p;
    }

    private static PdfPCell cellule(String texte, Font f, int alignement, boolean entete) {
        PdfPCell c = new PdfPCell(new Phrase(texte == null ? "—" : texte, f));
        c.setHorizontalAlignment(alignement);
        c.setPadding(5);
        if (entete) {
            c.setBackgroundColor(new java.awt.Color(0xF3, 0xF4, 0xF6));
        }
        return c;
    }

    /** Separateur de milliers par espace insecable — convention francaise (FCFA). */
    private static String montant(long valeur) {
        return String.format(Locale.FRANCE, "%,d", valeur).replace(' ', ' ');
    }

    private static String tailleLisible(int octets) {
        if (octets < 1024) return octets + " o";
        return Math.round(octets / 1024.0) + " Ko";
    }
}
