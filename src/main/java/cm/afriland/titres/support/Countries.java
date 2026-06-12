package cm.afriland.titres.support;

/**
 * Libelle du pays emetteur a partir du code ISO 3 lettres (CSFT §3.1.3).
 */
public final class Countries {

    private Countries() {
    }

    public static String label(String code) {
        return switch (code == null ? "" : code) {
            case "CMR" -> "Cameroun";
            case "GAB" -> "Gabon";
            case "CGO" -> "Congo";
            case "TCD" -> "Tchad";
            case "RCA" -> "République Centrafricaine";
            case "GNQ" -> "Guinée Équatoriale";
            default -> "—";
        };
    }
}
