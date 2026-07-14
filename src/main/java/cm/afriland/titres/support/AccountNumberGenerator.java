package cm.afriland.titres.support;

import java.security.SecureRandom;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Attribution des numeros de compte-titres.
 *
 * <p>Un compte-titres est cree soit par un agent (creation d'un client au
 * back-office), soit a la <b>validation d'un dossier d'auto-inscription</b>.
 * Les deux chemins doivent produire des numeros du meme format et surtout
 * <b>uniques</b> : la generation est donc centralisee ici plutot que dupliquee.</p>
 */
@Service
public class AccountNumberGenerator {

    /** Format Afriland : « 037 10001 xxxxxxxxxxx ». */
    private static final String FORMAT = "037 10001 %011d";
    private static final long BORNE = 100_000_000_000L;
    private static final int TENTATIVES = 25;

    private final SecureRandom rng = new SecureRandom();
    private final JdbcTemplate jdbc;

    public AccountNumberGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Genere un numero de compte non encore attribue.
     *
     * @param avoid numeros a exclure en plus de ceux deja en base (p. ex. le
     *              compte-titres qu'on vient de tirer, pour ne pas reutiliser le
     *              meme numero comme compte especes).
     */
    public String generate(String... avoid) {
        Set<String> taboo = Set.of(avoid);
        for (int i = 0; i < TENTATIVES; i++) {
            String candidat = String.format(FORMAT, rng.nextLong(BORNE));
            if (taboo.contains(candidat) || estPris(candidat)) {
                continue;
            }
            return candidat;
        }
        throw new IllegalStateException("Impossible de generer un numero de compte unique.");
    }

    private boolean estPris(String candidat) {
        Long existe = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE compte_titres = ? OR compte_especes = ?",
                Long.class, candidat, candidat);
        return existe == null || existe > 0;
    }
}
