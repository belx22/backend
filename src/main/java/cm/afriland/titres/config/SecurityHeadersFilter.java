package cm.afriland.titres.config;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Ajoute les en-tetes de securite HTTP recommandes (OWASP Secure Headers).
 *
 * L'API ne renvoie que du JSON : une CSP tres restrictive et l'interdiction
 * d'encadrement limitent les risques de clickjacking.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /**
     * CSP permissive reservee a l'interface Swagger UI : elle charge ses propres
     * scripts/styles (inline) et images data:, et interroge l'API en meme origine
     * (« Try it out »). La CSP {@code default-src 'none'} de l'API les bloquerait.
     */
    private static final String SWAGGER_CSP =
            "default-src 'self'; script-src 'self' 'unsafe-inline'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'";

    /** Vrai pour les ressources de documentation OpenAPI / Swagger UI. */
    private static boolean isDocsPath(String uri) {
        return uri.startsWith("/swagger-ui")
                || uri.startsWith("/v3/api-docs")
                || uri.equals("/swagger-ui.html");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        // L'API ne sert que du JSON (CSP verrouillee) ; Swagger UI recoit une CSP
        // adaptee a son rendu HTML tout en restant confinee a la meme origine.
        response.setHeader("Content-Security-Policy",
                isDocsPath(request.getRequestURI())
                        ? SWAGGER_CSP
                        : "default-src 'none'; frame-ancestors 'none'");
        response.setHeader("Strict-Transport-Security", "max-age=63072000; includeSubDomains");
        response.setHeader("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
        filterChain.doFilter(request, response);
    }
}
