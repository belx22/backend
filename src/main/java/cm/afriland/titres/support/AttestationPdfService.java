package cm.afriland.titres.support;

import java.awt.Color;
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
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

import cm.afriland.titres.error.ApiException;

/**
 * Genere l'attestation de propriete de titre (CSFT §M4-F02) en PDF, cote
 * serveur, a partir des seules donnees de la base — reproduit la mise en forme
 * du document officiel Afriland First Bank.
 *
 * <p>La generation est volontairement serveur : l'attestation engage la banque
 * en tant que teneur de compte conservateur. Le client ne fournit rien — il
 * declenche seulement l'edition (ou l'agent l'edite pour lui).</p>
 *
 * <p>Mise en page : une bande grise a gauche (laissee vide) et le contenu
 * decale sur la droite ; titre encadre ; un bloc borde par titre detenu.</p>
 */
@Service
public class AttestationPdfService {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter JOUR_LONG =
            DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.FRENCH);

    /** Largeur de la bande grise de gauche (points) — laissee vide. */
    private static final float BANDE_LARGEUR = 96f;
    private static final Color BANDE_GRISE = new Color(0xEC, 0xED, 0xF0);
    private static final Color CADRE_FOND = new Color(0xEC, 0xEC, 0xEE);

    /**
     * Positions du porteur, derivees des ordres adjuges (memes regles que le
     * portefeuille). Le {@code %s} accueille un filtre optionnel par titre (ISIN)
     * pour editer une attestation portant sur un seul titre.
     */
    private static final String POSITIONS_SQL = """
            SELECT e.code AS emission_code, e.libelle, o.isin, e.nature, e.pays_code, e.date_echeance,
                   sum(coalesce(o.volume_alloue, 0))::bigint       AS volume,
                   e.valeur_nominale_unitaire                      AS valeur_nominale,
                   sum(coalesce(o.montant_adjuge, 0))::bigint      AS valeur_totale
              FROM orders o
              JOIN emissions e ON e.id = o.emission_id
             WHERE o.client_id = ?
               AND o.status IN ('TOTALEMENT_RETENU', 'PARTIELLEMENT_RETENU')
               %s
             GROUP BY e.id, e.libelle, o.isin, e.code, e.nature, e.pays_code, e.date_echeance,
                      e.valeur_nominale_unitaire
            HAVING sum(coalesce(o.volume_alloue, 0)) > 0
             ORDER BY e.date_echeance
            """;

    private final JdbcTemplate jdbc;

    public AttestationPdfService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Ligne(String emissionCode, String libelle, String isin, String nature,
                         String emetteur, LocalDate dateEcheance, long volume, long valeurNominale,
                         long valeurTotale) {
    }

    private record Titulaire(String nom, String compteTitres) {
    }

    /** Resultat de l'edition : le PDF encode en data URI, et sa taille affichable. */
    public record Attestation(String dataUri, String taille) {
    }

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
                rs.getString("libelle"),
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
        Font nomBanque = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15);
        Font titre = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Font gras = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        Font petit = FontFactory.getFont(FontFactory.HELVETICA, 8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Marge gauche large : tout le contenu se decale a droite de la bande grise.
        Document doc = new Document(PageSize.A4, BANDE_LARGEUR + 20, 44, 44, 44);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new PdfPageEventHelper() {
            @Override
            public void onStartPage(PdfWriter w, Document d) {
                PdfContentByte cb = w.getDirectContentUnder();
                Rectangle ps = d.getPageSize();
                cb.setColorFill(BANDE_GRISE);
                cb.rectangle(0, 0, BANDE_LARGEUR, ps.getHeight());
                cb.fill();
            }
        });
        doc.open();

        // En-tete : nom de la banque, centre sur la zone de contenu.
        doc.add(centre("Afriland First Bank", nomBanque));
        doc.add(espace(16));

        // Titre encadre.
        PdfPTable cadre = new PdfPTable(1);
        cadre.setWidthPercentage(88);
        PdfPCell tc = new PdfPCell(new Phrase("ATTESTATION DE PROPRIÉTÉ DE TITRE", titre));
        tc.setHorizontalAlignment(Element.ALIGN_CENTER);
        tc.setBackgroundColor(CADRE_FOND);
        tc.setBorderWidth(1.5f);
        tc.setPadding(12);
        cadre.addCell(tc);
        doc.add(cadre);
        doc.add(espace(20));

        // Corps.
        doc.add(justifie("Nous soussignés, Afriland First Bank, Société Anonyme au Capital de FCFA "
                + "50.000.000.000 dont le Siège Social est à Yaoundé, B.P. 11834, Spécialiste en "
                + "valeurs du Trésor et Intermédiaire du Marché Financier d'Afrique Centrale agréé "
                + "par la Commission de Surveillance du Marché Financier d'Afrique Centrale sous le "
                + "numéro COSUMAF-I. MFAC-01/2015.", normal));
        doc.add(espace(12));

        String verbe = lignes.size() > 1 ? "les valeurs" : "la valeur";
        doc.add(justifie("Attestons que " + titulaire.nom() + ", entretient dans son compte titre "
                + "N° " + titulaire.compteTitres() + ", tenu dans nos livres, " + verbe + " :", normal));
        doc.add(espace(14));

        LocalDate aujourdHui = LocalDate.now(ZoneOffset.UTC);
        for (Ligne l : lignes) {
            doc.add(boiteTitre(l, aujourdHui, normal, gras));
            doc.add(espace(14));
        }

        doc.add(espace(4));
        doc.add(justifie("En foi de quoi, la présente Attestation est délivrée pour servir et "
                + "valoir ce que de droit.", normal));
        doc.add(espace(24));

        doc.add(centre("Fait à Yaoundé, le " + aujourdHui.format(JOUR_LONG), normal));
        doc.add(espace(48));

        doc.add(new Paragraph("Pour Afriland First Bank, teneur de compte conservateur.", petit));
        doc.add(espace(4));
        doc.add(new Paragraph("Document édité électroniquement par le système — toute altération "
                + "le rend nul.", petit));

        doc.close();
        return out.toByteArray();
    }

    /** Bloc borde d'un titre : reproduit la liste de champs de l'attestation. */
    private PdfPTable boiteTitre(Ligne l, LocalDate date, Font normal, Font gras) {
        PdfPTable inner = new PdfPTable(new float[] { 2.3f, 4.2f });
        inner.setWidthPercentage(100);

        champ(inner, "Dénomination", denomination(l), normal, gras);
        champ(inner, "Catégorie", l.nature(), normal, gras);
        vide(inner);
        champ(inner, "Emetteur", l.emetteur(), normal, gras);
        champ(inner, "Total crédit", montant(l.volume()), normal, gras);
        champ(inner, "Total débit", "0", normal, gras);
        vide(inner);
        champ(inner, "Solde au " + date.format(JOUR), montant(l.volume()), normal, gras);
        champ(inner, "Valeur XAF",
                montant(l.valeurTotale()) + " (" + capitale(enLettres(l.valeurTotale())) + ")",
                normal, gras);

        PdfPCell wrap = new PdfPCell(inner);
        wrap.setPadding(10);
        wrap.setBorderWidth(1f);
        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(90);
        box.addCell(wrap);
        return box;
    }

    private static void champ(PdfPTable t, String label, String valeur, Font normal, Font gras) {
        PdfPCell lc = new PdfPCell(new Phrase("-   " + label, normal));
        lc.setBorder(Rectangle.NO_BORDER);
        lc.setPaddingBottom(4);
        t.addCell(lc);
        PdfPCell vc = new PdfPCell(new Phrase(":   " + (valeur == null ? "—" : valeur), gras));
        vc.setBorder(Rectangle.NO_BORDER);
        vc.setPaddingBottom(4);
        t.addCell(vc);
    }

    private static void vide(PdfPTable t) {
        for (int i = 0; i < 2; i++) {
            PdfPCell c = new PdfPCell(new Phrase(" "));
            c.setBorder(Rectangle.NO_BORDER);
            c.setFixedHeight(7);
            t.addCell(c);
        }
    }

    /** « Dénomination » = code de l'émission suivi de son libellé (sans répétition). */
    private static String denomination(Ligne l) {
        String code = l.emissionCode() == null ? "" : l.emissionCode().trim();
        String lib = l.libelle() == null ? "" : l.libelle().trim();
        if (code.isEmpty()) return lib.isEmpty() ? "—" : lib;
        if (lib.isEmpty() || lib.startsWith(code)) return lib.isEmpty() ? code : lib;
        return code + " " + lib;
    }

    // ── Mise en forme ────────────────────────────────────────────────────────

    private static Paragraph centre(String texte, Font f) {
        Paragraph p = new Paragraph(texte, f);
        p.setAlignment(Element.ALIGN_CENTER);
        return p;
    }

    private static Paragraph justifie(String texte, Font f) {
        Paragraph p = new Paragraph(texte, f);
        p.setAlignment(Element.ALIGN_JUSTIFIED);
        return p;
    }

    private static Paragraph espace(float hauteur) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(hauteur);
        return p;
    }

    /** Separateur de milliers par espace insecable — convention francaise (FCFA). */
    private static String montant(long valeur) {
        return String.format(Locale.FRANCE, "%,d", valeur).replace(' ', ' ');
    }

    private static String tailleLisible(int octets) {
        if (octets < 1024) return octets + " o";
        return Math.round(octets / 1024.0) + " Ko";
    }

    // ── Montant en toutes lettres (francais) ─────────────────────────────────

    private static final String[] UNITES = {
            "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
            "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize",
            "dix-sept", "dix-huit", "dix-neuf" };

    static String enLettres(long n) {
        if (n == 0) return "zéro";
        if (n < 0) return "moins " + enLettres(-n);

        StringBuilder sb = new StringBuilder();
        long milliards = n / 1_000_000_000L;
        long millions = (n % 1_000_000_000L) / 1_000_000L;
        long milliers = (n % 1_000_000L) / 1000L;
        int reste = (int) (n % 1000L);

        if (milliards > 0) sb.append(groupe(milliards, "milliard")).append(' ');
        if (millions > 0) sb.append(groupe(millions, "million")).append(' ');
        if (milliers == 1) {
            sb.append("mille ");
        } else if (milliers > 1) {
            sb.append(troisChiffres((int) milliers)).append(" mille ");
        }
        if (reste > 0) sb.append(troisChiffres(reste));
        return sb.toString().trim();
    }

    /** {@code n} fois « million » / « milliard » (accord en nombre). */
    private static String groupe(long n, String mot) {
        String pluriel = mot + (n > 1 ? "s" : "");
        return (n == 1 ? "un" : troisChiffres((int) n)) + " " + pluriel;
    }

    private static String troisChiffres(int n) {
        if (n == 0) return "";
        int c = n / 100;
        int r = n % 100;
        StringBuilder sb = new StringBuilder();
        if (c > 0) {
            if (c > 1) sb.append(UNITES[c]).append(' ');
            sb.append("cent");
            if (c > 1 && r == 0) sb.append('s');
            if (r > 0) sb.append(' ');
        }
        if (r > 0) sb.append(dizaines(r));
        return sb.toString().trim();
    }

    private static String dizaines(int n) {
        if (n < 20) return UNITES[n];
        int d = n / 10;
        int u = n % 10;
        String base;
        switch (d) {
            case 2: base = "vingt"; break;
            case 3: base = "trente"; break;
            case 4: base = "quarante"; break;
            case 5: base = "cinquante"; break;
            case 6: base = "soixante"; break;
            case 7:
                if (u == 0) return "soixante-dix";
                if (u == 1) return "soixante et onze";
                return "soixante-" + UNITES[10 + u];
            case 8:
                return u == 0 ? "quatre-vingts" : "quatre-vingt-" + UNITES[u];
            case 9:
                return u == 0 ? "quatre-vingt-dix" : "quatre-vingt-" + UNITES[10 + u];
            default: return "";
        }
        if (u == 0) return base;
        if (u == 1) return base + " et un";
        return base + "-" + UNITES[u];
    }

    private static String capitale(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
