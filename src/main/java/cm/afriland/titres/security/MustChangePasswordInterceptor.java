package cm.afriland.titres.security;

import java.util.Set;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Tant qu'un utilisateur n'a pas remplace son mot de passe provisoire
 * ({@code must_change_password = true}, claim JWT {@code mcp}), son jeton ne
 * donne acces qu'au strict necessaire : consulter son profil et changer son mot
 * de passe. Toute autre route est refusee (403). Defense contre l'usage d'un
 * compte a identifiants initiaux transmis par e-mail/SMS.
 */
public class MustChangePasswordInterceptor implements HandlerInterceptor {

    private final JwtService jwt;

    /** Routes autorisees malgre le drapeau (changement de mot de passe + sortie). */
    private static final Set<String> ALLOWED = Set.of(
            "/api/v1/auth/change-password",
            "/api/v1/auth/me",
            "/api/v1/auth/logout");

    public MustChangePasswordInterceptor(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (ALLOWED.contains(path)) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null) {
            return true; // pas de jeton : la resolution d'argument gerera le 401.
        }
        String lower = header.toLowerCase();
        if (!lower.startsWith("bearer ")) {
            return true;
        }
        String token = header.substring(7).trim();
        AuthUser user;
        try {
            user = jwt.verify(token);
        } catch (RuntimeException e) {
            return true; // jeton invalide : laisse le resolveur renvoyer 401.
        }

        if (user.mustChangePassword()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":"
                            + "\"Vous devez d'abord changer votre mot de passe provisoire.\"}}");
            response.getWriter().flush();
            return false;
        }
        return true;
    }
}
