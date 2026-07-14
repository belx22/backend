package cm.afriland.titres;

import cm.afriland.titres.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Backend Spring Boot — Plateforme Valeurs du Tresor CEMAC (Afriland First Bank).
 *
 * Point d'entree : Spring Boot charge la configuration, applique les migrations
 * Flyway, charge la matrice RBAC et amorce l'administrateur initial ({@code StartupRunner}) puis
 * demarre le serveur HTTP. L'API est exposee sous le prefixe {@code /api/v1}.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
public class AfbTitresApplication {

    public static void main(String[] args) {
        SpringApplication.run(AfbTitresApplication.class, args);
    }
}
