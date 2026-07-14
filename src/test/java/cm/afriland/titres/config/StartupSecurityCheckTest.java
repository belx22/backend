package cm.afriland.titres.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Controle de securite au demarrage : en production, un defaut de developpement
 * doit EMPECHER le demarrage ; ailleurs, il est seulement journalise.
 */
class StartupSecurityCheckTest {

    private static final String SECRET_FAIBLE =
            "dev_secret_a_remplacer_en_production_32_caracteres_min";
    private static final String SECRET_FORT =
            "un-secret-aleatoire-de-plus-de-32-octets-pour-la-prod";

    private AppProperties props;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.setJwtSecret(SECRET_FORT);
        props.setSeedOnStart(false);
        props.setAifTrustAllCerts(false);
    }

    private static StartupSecurityCheck check(AppProperties props, MockEnvironment env) {
        return new StartupSecurityCheck(props, env);
    }

    private static MockEnvironment envDev() {
        return new MockEnvironment();
    }

    private static MockEnvironment envProd() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    private static void run(StartupSecurityCheck c) {
        c.run(null);
    }

    // ─── Configuration durcie ────────────────────────────────────────────────

    @Test
    void configuration_durcie_demarre_en_production() {
        assertThatCode(() -> run(check(props, envProd()))).doesNotThrowAnyException();
    }

    @Test
    void configuration_durcie_demarre_en_developpement() {
        assertThatCode(() -> run(check(props, envDev()))).doesNotThrowAnyException();
    }

    // ─── Defauts dangereux : bloquants en production ─────────────────────────

    @Test
    void secret_jwt_de_demonstration_empeche_le_demarrage_en_production() {
        props.setJwtSecret(SECRET_FAIBLE);

        assertThatThrownBy(() -> run(check(props, envProd())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_JWT_SECRET");
    }

    @Test
    void code_mfa_fixe_empeche_le_demarrage_en_production() {
        props.setMfaDevCode("123456");

        assertThatThrownBy(() -> run(check(props, envProd())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_MFA_DEV_CODE");
    }

    @Test
    void jeu_de_demonstration_empeche_le_demarrage_en_production() {
        props.setSeedOnStart(true);

        assertThatThrownBy(() -> run(check(props, envProd())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_SEED_ON_START");
    }

    /** TLS non valide vers le serveur de soldes : risque d'interception. */
    @Test
    void tls_non_valide_vers_aif_empeche_le_demarrage_en_production() {
        props.setAifTrustAllCerts(true);

        assertThatThrownBy(() -> run(check(props, envProd())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_AIF_TRUST_ALL_CERTS");
    }

    /** Tous les defauts a la fois : le message les enumere tous. */
    @Test
    void tous_les_defauts_sont_enumeres_dans_le_message_d_erreur() {
        props.setJwtSecret(SECRET_FAIBLE);
        props.setMfaDevCode("123456");
        props.setSeedOnStart(true);
        props.setAifTrustAllCerts(true);

        assertThatThrownBy(() -> run(check(props, envProd())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContainingAll("APP_JWT_SECRET", "APP_MFA_DEV_CODE",
                        "APP_SEED_ON_START", "APP_AIF_TRUST_ALL_CERTS");
    }

    /** Le profil « production » est reconnu au meme titre que « prod ». */
    @Test
    void le_profil_production_est_reconnu_comme_la_production() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        props.setSeedOnStart(true);

        assertThatThrownBy(() -> run(check(props, env)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Un profil quelconque ne declenche pas le mode bloquant. */
    @Test
    void un_profil_non_production_ne_bloque_pas() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("staging");
        props.setSeedOnStart(true);

        assertThatCode(() -> run(check(props, env))).doesNotThrowAnyException();
    }

    // ─── Bascule explicite APP_ENFORCE_PROD_SECURITY ─────────────────────────

    /** Hors production, la bascule rend les controles bloquants. */
    @Test
    void la_bascule_enforce_rend_les_controles_bloquants_hors_production() {
        MockEnvironment env = envDev();
        env.setProperty("APP_ENFORCE_PROD_SECURITY", "true");
        props.setSeedOnStart(true);

        assertThatThrownBy(() -> run(check(props, env)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Démarrage refusé");
    }

    /** La bascule est insensible a la casse. */
    @Test
    void la_bascule_enforce_est_insensible_a_la_casse() {
        MockEnvironment env = envDev();
        env.setProperty("APP_ENFORCE_PROD_SECURITY", "TRUE");
        props.setMfaDevCode("123456");

        assertThatThrownBy(() -> run(check(props, env)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Sans la bascule, les defauts sont seulement journalises : le demarrage aboutit. */
    @Test
    void hors_production_les_defauts_sont_seulement_journalises() {
        props.setJwtSecret(SECRET_FAIBLE);
        props.setMfaDevCode("123456");
        props.setSeedOnStart(true);
        props.setAifTrustAllCerts(true);

        assertThatCode(() -> run(check(props, envDev()))).doesNotThrowAnyException();
    }

    @Test
    void la_bascule_a_false_ne_bloque_pas() {
        MockEnvironment env = envDev();
        env.setProperty("APP_ENFORCE_PROD_SECURITY", "false");
        props.setSeedOnStart(true);

        assertThatCode(() -> run(check(props, env))).doesNotThrowAnyException();
    }

    // ─── Validation minimale, toujours active ────────────────────────────────

    /** Un secret trop court echoue meme hors production (props.validate()). */
    @Test
    void un_secret_trop_court_echoue_meme_hors_production() {
        props.setJwtSecret("trop-court");

        assertThatThrownBy(() -> run(check(props, envDev())))
                .isInstanceOf(IllegalStateException.class);
    }
}
