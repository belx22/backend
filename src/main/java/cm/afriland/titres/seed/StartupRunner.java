package cm.afriland.titres.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import cm.afriland.titres.config.AppProperties;
import cm.afriland.titres.security.PasswordService;
import cm.afriland.titres.security.Rbac;

/**
 * Amorcage de l'application au demarrage.
 *
 * <p>Ne contient <b>aucune donnee de demonstration</b> : la plateforme demarre sur
 * une base vierge. Deux responsabilites, et deux seulement :</p>
 *
 * <ol>
 *   <li>charger en memoire la matrice RBAC (table alimentee par les migrations) —
 *       sans quoi plus aucune permission n'est reconnue ;</li>
 *   <li>creer le <b>premier administrateur</b> si, et seulement si, la base ne
 *       compte aucun utilisateur et que ses identifiants sont fournis par
 *       l'environnement.</li>
 * </ol>
 *
 * <p>Sans cet amorcage, une base neuve serait inutilisable : aucun compte ne
 * permettrait de se connecter pour creer les autres. Le mot de passe n'est jamais
 * journalise, et le compte cree doit le changer a la premiere connexion.</p>
 */
@Component
public class StartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    private final JdbcTemplate jdbc;
    private final PasswordService password;
    private final AppProperties props;
    private final Rbac rbac;

    public StartupRunner(JdbcTemplate jdbc, PasswordService password, AppProperties props,
                         Rbac rbac) {
        this.jdbc = jdbc;
        this.password = password;
        this.props = props;
        this.rbac = rbac;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        creerPremierAdministrateur();
        rbac.loadMatrix();
    }

    /**
     * Cree l'administrateur initial sur une base vierge.
     *
     * <p>Ne fait rien si la base compte deja un utilisateur : cet amorcage ne peut
     * donc pas servir a s'octroyer un acces sur une plateforme en service.</p>
     */
    private void creerPremierAdministrateur() {
        String email = props.getBootstrapAdminEmail();
        String motDePasse = props.getBootstrapAdminPassword();
        if (email == null || email.isBlank() || motDePasse == null || motDePasse.isBlank()) {
            return;
        }

        Long utilisateurs = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        if (utilisateurs == null || utilisateurs > 0) {
            return;
        }

        jdbc.update("INSERT INTO users (email, password_hash, role, nom, must_change_password) "
                        + "VALUES (?,?, 'ADMIN', 'ADMINISTRATEUR', TRUE)",
                email.trim().toLowerCase(), password.hash(motDePasse));

        // Le mot de passe n'est JAMAIS journalise.
        log.info("Base vierge — administrateur initial cree ({}). "
                + "Son mot de passe devra etre change a la premiere connexion.", email);
    }
}
