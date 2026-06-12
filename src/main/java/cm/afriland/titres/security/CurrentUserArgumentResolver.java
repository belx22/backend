package cm.afriland.titres.security;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import cm.afriland.titres.error.ApiException;

/**
 * Resout les parametres de controleur {@link AuthUser} et {@link OptionalAuthUser}
 * a partir de l'en-tete {@code Authorization: Bearer <jeton>}.
 *
 * - {@code AuthUser}         : exige un jeton valide (401 sinon).
 * - {@code OptionalAuthUser} : jeton facultatif ; un jeton present mais invalide
 *   provoque tout de meme une erreur 401.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final JwtService jwtService;

    public CurrentUserArgumentResolver(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return type.equals(AuthUser.class) || type.equals(OptionalAuthUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        boolean optional = parameter.getParameterType().equals(OptionalAuthUser.class);
        String header = webRequest.getHeader("Authorization");

        if (header == null || header.isBlank()) {
            if (optional) {
                return new OptionalAuthUser(null);
            }
            throw ApiException.unauthorized("Jeton d'acces manquant.");
        }

        String token;
        if (header.startsWith("Bearer ")) {
            token = header.substring(7).trim();
        } else if (header.startsWith("bearer ")) {
            token = header.substring(7).trim();
        } else {
            throw ApiException.unauthorized("Format d'autorisation invalide.");
        }

        AuthUser user;
        try {
            user = jwtService.verify(token);
        } catch (RuntimeException e) {
            throw ApiException.unauthorized("Jeton d'acces invalide ou expire.");
        }
        return optional ? new OptionalAuthUser(user) : user;
    }
}
