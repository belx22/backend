package cm.afriland.titres.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Comparaison faciale 1:1 — <b>decidee cote serveur</b>.
 *
 * <p>Le navigateur calcule une empreinte (descripteur 128-d) mais ne juge
 * jamais de la correspondance : il envoie l'empreinte, et c'est le serveur qui
 * calcule la distance et applique le seuil. Un client modifie ne peut donc pas
 * s'auto-declarer « reconnu ».</p>
 *
 * <p>Distance euclidienne entre descripteurs ; en deca de {@link #SEUIL} on
 * considere qu'il s'agit de la meme personne (valeur de reference du modele).</p>
 */
public final class FaceMatcher {

    /** Seuil de correspondance (distance euclidienne). */
    public static final double SEUIL = 0.6;

    /** Dimension attendue du descripteur. */
    public static final int DIMENSION = 128;

    public static final String CORRESPOND = "CORRESPOND";
    public static final String DIFFERENT = "DIFFERENT";
    public static final String NON_COMPARABLE = "NON_COMPARABLE";

    private FaceMatcher() {
    }

    /** Resultat d'une comparaison : distance (nulle si incomparable) + statut. */
    public record Resultat(Double distance, boolean matched, String status) {
    }

    /** Serialise un descripteur en JSON compact, ou {@code null} s'il est absent/invalide. */
    public static String encode(List<Double> descriptor) {
        if (descriptor == null || descriptor.size() != DIMENSION) {
            return null;
        }
        StringBuilder sb = new StringBuilder(DIMENSION * 8).append('[');
        for (int i = 0; i < descriptor.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(descriptor.get(i));
        }
        return sb.append(']').toString();
    }

    /** Relit un descripteur JSON. Renvoie {@code null} si absent ou mal forme. */
    public static double[] decode(String json) {
        if (json == null || json.isBlank()) return null;
        String s = json.trim();
        if (s.length() < 2 || s.charAt(0) != '[' || s.charAt(s.length() - 1) != ']') return null;
        String corps = s.substring(1, s.length() - 1).trim();
        if (corps.isEmpty()) return null;
        String[] parts = corps.split(",");
        if (parts.length != DIMENSION) return null;
        double[] out = new double[DIMENSION];
        try {
            for (int i = 0; i < DIMENSION; i++) {
                out[i] = Double.parseDouble(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /**
     * Compare l'empreinte du jour a l'empreinte de reference (ouverture de compte).
     *
     * <p>Si l'une des deux manque (visage non exploitable, compte ouvert avant la
     * mise en place de la biometrie), le statut est {@code NON_COMPARABLE} :
     * l'ordre n'est PAS bloque, il est simplement signale au back-office.</p>
     */
    public static Resultat comparer(String referenceJson, List<Double> courant) {
        double[] reference = decode(referenceJson);
        double[] jour = decode(encode(courant));
        if (reference == null || jour == null) {
            return new Resultat(null, false, NON_COMPARABLE);
        }
        double distance = distanceEuclidienne(reference, jour);
        boolean matched = distance < SEUIL;
        return new Resultat(distance, matched, matched ? CORRESPOND : DIFFERENT);
    }

    /** Distance euclidienne entre deux descripteurs de meme dimension. */
    public static double distanceEuclidienne(double[] a, double[] b) {
        double somme = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            somme += d * d;
        }
        return Math.sqrt(somme);
    }

    /** Liste utilitaire (tests / mapping). */
    public static List<Double> toList(double[] v) {
        List<Double> out = new ArrayList<>(v.length);
        for (double d : v) out.add(d);
        return out;
    }
}
