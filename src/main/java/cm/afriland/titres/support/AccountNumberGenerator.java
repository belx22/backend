package cm.afriland.titres.support;

import java.security.SecureRandom;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Attribution des comptes de depot (comptes-titres).
 *
 * <p>Le compte de depot suit le format du depositaire : {@code CCEICMCXX} suivi
 * d'un rang sur 3 chiffres — {@code CCEICMCXX000}, {@code CCEICMCXX001}, … Il est
 * attribue <b>en sequence</b> (et non tire au hasard) : l'espace ne compte que
 * 1000 valeurs, et c'est ainsi que la banque le numerote dans son referentiel.</p>
 *
 * <p>C'est un identifiant <b>interne</b> : le back-office le voit, le client
 * jamais.</p>
 */
@Service
public class AccountNumberGenerator {

    public static final String PREFIXE = "CCEICMCXX";
    private static final int RANG_MAX = 999;
    /** Agence de rattachement par defaut pour un compte especes genere. */
    private static final String AGENCE_PAR_DEFAUT = "00001";

    private final SecureRandom rng = new SecureRandom();
    private final JdbcTemplate jdbc;

    public AccountNumberGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Prochain compte de depot libre, en balayant a la fois les comptes deja
     * attribues sur la plateforme ({@code users}) et ceux du referentiel importe
     * ({@code clients_fb}) : les deux puisent dans la meme numerotation.
     *
     * @param avoid comptes a exclure en plus (p. ex. un numero tout juste reserve)
     */
    public String generate(String... avoid) {
        int depart = prochainRang() % (RANG_MAX + 1);
        List<String> exclus = List.of(avoid);
        // On repart du rang suivant le plus haut attribue, puis on BOUCLE sur tout
        // l'espace : sans ce retour a zero, un seul CCEICMCXX999 en base condamnerait
        // toute la numerotation alors que des centaines de rangs restent libres.
        for (int i = 0; i <= RANG_MAX; i++) {
            String candidat = String.format(PREFIXE + "%03d", (depart + i) % (RANG_MAX + 1));
            if (!exclus.contains(candidat) && !estPris(candidat)) {
                return candidat;
            }
        }
        throw new IllegalStateException("Numerotation des comptes de depot epuisee : les "
                + (RANG_MAX + 1) + " numeros " + PREFIXE + "000-" + PREFIXE + RANG_MAX
                + " sont tous attribues.");
    }

    /**
     * Compte especes de repli, au format RIB : {@code 10005} (banque) + agence(5)
     * + compte(11) + cle(2) = 23 chiffres.
     *
     * <p>Rien a voir avec la numerotation des comptes de depot : ce sont deux
     * espaces distincts, et les confondre produirait un compte especes de la forme
     * {@code CCEICMCXX007} — ce qui n'est pas un numero de compte bancaire.</p>
     */
    public String generateCompteEspeces() {
        for (int i = 0; i < 25; i++) {
            String candidat = "10005" + AGENCE_PAR_DEFAUT
                    + String.format("%011d", rng.nextLong(100_000_000_000L))
                    + String.format("%02d", rng.nextInt(100));
            Long pris = jdbc.queryForObject("SELECT count(*) FROM users WHERE compte_especes = ?",
                    Long.class, candidat);
            if (pris != null && pris == 0) {
                return candidat;
            }
        }
        throw new IllegalStateException("Impossible de generer un compte especes unique.");
    }

    /** Rang suivant le plus grand deja utilise, des deux cotes. */
    private int prochainRang() {
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(rang), -1) + 1 FROM (
                    SELECT CAST(substring(compte_titres FROM 10 FOR 3) AS INT) AS rang
                      FROM users      WHERE compte_titres LIKE ? || '___'
                    UNION ALL
                    SELECT CAST(substring(compte_depot  FROM 10 FOR 3) AS INT) AS rang
                      FROM clients_fb WHERE compte_depot  LIKE ? || '___'
                ) AS rangs
                """, Integer.class, PREFIXE, PREFIXE);
        return max == null ? 0 : max;
    }

    private boolean estPris(String candidat) {
        Long n = jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM users WHERE compte_titres = ?) "
                        + "+ (SELECT count(*) FROM clients_fb WHERE compte_depot = ?)",
                Long.class, candidat, candidat);
        return n == null || n > 0;
    }
}
