package cm.afriland.titres.integration;

/**
 * Levee lorsque le serveur Amplitude/AIF est injoignable ou repond une erreur.
 *
 * <p>Distincte d'{@link cm.afriland.titres.error.ApiException} : permet au
 * controleur appelant de degrader gracieusement (reponse vide + statut
 * UNAVAILABLE) au lieu de propager une erreur HTTP. Le but est que la
 * plateforme reste fonctionnelle meme si AIF est en panne (CSFT §INT-CB01).</p>
 */
public class AifUnavailableException extends RuntimeException {

    public AifUnavailableException(String message) {
        super(message);
    }

    public AifUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
