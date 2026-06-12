package cm.afriland.titres.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import cm.afriland.titres.security.ClientIpArgumentResolver;
import cm.afriland.titres.security.CurrentUserArgumentResolver;
import cm.afriland.titres.security.JwtService;
import cm.afriland.titres.security.MustChangePasswordInterceptor;

/**
 * Configuration MVC : politique CORS et resolveurs d'arguments personnalises
 * ({@code AuthUser}, {@code OptionalAuthUser}, {@code ClientIp}).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtService jwtService;
    private final AppProperties props;

    public WebConfig(JwtService jwtService, AppProperties props) {
        this.jwtService = jwtService;
        this.props = props;
    }

    /** CORS : seules les origines du frontend sont autorisees (pas de joker).
     *  {@code APP_FRONTEND_ORIGIN} peut lister plusieurs origines separees par
     *  une virgule (ex. adresse publique + localhost de dev). */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = props.getFrontendOrigin().split("\\s*,\\s*");
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .maxAge(600);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver(jwtService));
        resolvers.add(new ClientIpArgumentResolver());
    }

    /** Bloque l'acces a l'API tant que le mot de passe provisoire n'est pas change. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new MustChangePasswordInterceptor(jwtService))
                .addPathPatterns("/api/**");
    }
}
