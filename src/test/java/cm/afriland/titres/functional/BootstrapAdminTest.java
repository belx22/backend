package cm.afriland.titres.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import cm.afriland.titres.config.AppProperties;
import cm.afriland.titres.security.PasswordService;
import cm.afriland.titres.security.Rbac;
import cm.afriland.titres.seed.StartupRunner;

/**
 * Amorcage du premier administrateur — la seule ecriture que l'application fasse
 * d'elle-meme, et uniquement sur une base vierge.
 *
 * <p>L'application ne contient plus aucune donnee de demonstration : sans cet
 * amorcage, une base neuve n'offrirait aucun compte pour se connecter. Il ne doit
 * pour autant jamais permettre de s'octroyer un acces sur une plateforme en
 * service.</p>
 */
@SpringBootTest
class BootstrapAdminTest {

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    @DynamicPropertySource
    static void appProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",
                () -> env("TEST_DB_URL", "jdbc:postgresql://localhost:5433/afb_titres_test"));
        r.add("spring.datasource.username", () -> env("TEST_DB_USER", "afb_app"));
        r.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "change_me_db"));
        r.add("app.jwt-secret", () -> "test-jwt-secret-au-moins-32-caracteres-long!!");
        r.add("app.seed-on-start", () -> "true");
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PasswordService password;
    @Autowired
    Rbac rbac;

    private StartupRunner runner(String email, String motDePasse) {
        AppProperties props = new AppProperties();
        props.setJwtSecret("test-jwt-secret-au-moins-32-caracteres-long!!");
        props.setBootstrapAdminEmail(email);
        props.setBootstrapAdminPassword(motDePasse);
        return new StartupRunner(jdbc, password, props, rbac);
    }

    private long comptes() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        return n == null ? 0 : n;
    }

    /**
     * Le garde-fou essentiel : la base de test n'est PAS vide (elle porte le jeu
     * des tests). L'amorcage ne doit donc rien creer — sinon n'importe qui pourrait
     * s'ajouter un administrateur en redemarrant l'application avec deux variables
     * d'environnement.
     */
    @Test
    void ne_cree_RIEN_si_la_base_compte_deja_un_utilisateur() {
        long avant = comptes();
        assertThat(avant).as("la base des tests est peuplee").isGreaterThan(0);

        String email = "intrus+" + UUID.randomUUID() + "@example.cm";
        runner(email, "MotDePasse1").run(null);

        assertThat(comptes()).isEqualTo(avant);
        Long trouve = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Long.class, email);
        assertThat(trouve).as("aucun administrateur ne doit avoir ete cree").isZero();
    }

    @Test
    void ne_fait_rien_sans_identifiants_d_amorcage() {
        long avant = comptes();
        runner(null, null).run(null);
        runner("  ", "  ").run(null);
        assertThat(comptes()).isEqualTo(avant);
    }

    /** L'amorcage doit tout de meme recharger la matrice RBAC : elle est vitale. */
    @Test
    void charge_toujours_la_matrice_rbac() {
        runner(null, null).run(null);
        assertThat(Rbac.roleHasPermission("AGENT", cm.afriland.titres.security.Permission.ORDER_RESULT))
                .as("sans matrice RBAC, plus aucune permission n'est reconnue")
                .isTrue();
    }
}
