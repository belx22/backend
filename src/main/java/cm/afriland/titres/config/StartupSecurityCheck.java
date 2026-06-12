package cm.afriland.titres.config;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Controle de securite au demarrage (contexte bancaire).
 *
 * <p>Verifie que les valeurs de configuration sensibles ne sont pas restees a
 * leur defaut de DEVELOPPEMENT. En profil {@code prod} (ou
 * {@code APP_ENFORCE_PROD_SECURITY=true}), tout defaut dangereux EMPECHE le
 * demarrage ; sinon, un avertissement explicite est journalise. Cela transforme
 * un oubli de configuration en echec visible plutot qu'en faille silencieuse.</p>
 */
@Component
public class StartupSecurityCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityCheck.class);

    /** Secret JWT de demonstration a bannir en production. */
    private static final String WEAK_JWT_SECRET =
            "dev_secret_a_remplacer_en_production_32_caracteres_min";

    private final AppProperties props;
    private final Environment env;

    public StartupSecurityCheck(AppProperties props, Environment env) {
        this.props = props;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Validation minimale toujours active (secret present et suffisamment long).
        props.validate();

        boolean enforce = isProd()
                || "true".equalsIgnoreCase(env.getProperty("APP_ENFORCE_PROD_SECURITY", "false"));

        List<String> issues = new ArrayList<>();
        if (WEAK_JWT_SECRET.equals(props.getJwtSecret())) {
            issues.add("APP_JWT_SECRET utilise la valeur de démonstration — générez un secret "
                    + "aléatoire d'au moins 32 octets.");
        }
        if (props.getMfaDevCode() != null) {
            issues.add("APP_MFA_DEV_CODE est défini — la MFA renvoie un code fixe. Laissez-le vide "
                    + "en production (OTP aléatoire réel).");
        }
        if (props.isSeedOnStart()) {
            issues.add("APP_SEED_ON_START=true — des comptes de démonstration à mot de passe connu "
                    + "peuvent être créés. Mettez false en production.");
        }
        if (props.isAifTrustAllCerts()) {
            issues.add("APP_AIF_TRUST_ALL_CERTS=true — la validation TLS vers le serveur de soldes "
                    + "est désactivée (risque MITM). Mettez false + truststore en production.");
        }

        if (issues.isEmpty()) {
            log.info("Contrôle de sécurité au démarrage : configuration durcie OK.");
            return;
        }
        if (enforce) {
            String msg = "Démarrage refusé — configuration de production non sécurisée :\n  - "
                    + String.join("\n  - ", issues);
            log.error(msg);
            throw new IllegalStateException(msg);
        }
        log.warn("⚠ Configuration de DÉVELOPPEMENT détectée (à NE PAS déployer en l'état) :");
        for (String i : issues) {
            log.warn("  - {}", i);
        }
        log.warn("  → En production, définissez SPRING_PROFILES_ACTIVE=prod (ou "
                + "APP_ENFORCE_PROD_SECURITY=true) pour rendre ces contrôles bloquants.");
    }

    private boolean isProd() {
        for (String p : env.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p)) {
                return true;
            }
        }
        return false;
    }
}
