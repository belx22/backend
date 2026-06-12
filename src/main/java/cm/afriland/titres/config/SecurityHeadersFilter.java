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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.setHeader("Strict-Transport-Security", "max-age=63072000; includeSubDomains");
        response.setHeader("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
        filterChain.doFilter(request, response);
    }
}
