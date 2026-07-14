package cm.afriland.titres.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Documentation OpenAPI 3 / Swagger UI.
 *
 * <p>Expose les metadonnees de l'API et declare le schema d'authentification
 * <b>Bearer JWT</b> : le bouton « Authorize » de Swagger UI permet de coller un
 * jeton d'acces (obtenu via {@code POST /api/v1/auth/mfa/verify}) qui sera
 * ensuite envoye dans l'en-tete {@code Authorization} des appels « Try it out ».</p>
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI afbTitresOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AFB Titres CEMAC — API")
                        .version("1.0.0")
                        .description("API REST de la plateforme Valeurs du Tresor CEMAC "
                                + "(Afriland First Bank). Authentification par jeton JWT "
                                + "(en-tete Authorization: Bearer <jeton>).")
                        .contact(new Contact().name("Afriland First Bank — DFT"))
                        .license(new License().name("Proprietaire — usage interne")))
                // Schema de securite reutilisable + application par defaut a toutes
                // les operations (le bouton « Authorize » injecte le Bearer).
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Jeton d'acces JWT (HS256).")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
