package cm.afriland.titres.security;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resout le parametre {@link ClientIp} : adresse IP du client, issue de
 * l'en-tete de proxy {@code X-Forwarded-For} sinon de la socket.
 */
public class ClientIpArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(ClientIp.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String ip = null;

        if (request != null) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                ip = forwarded.split(",")[0].trim();
            }
            if (ip == null || ip.isEmpty()) {
                ip = request.getRemoteAddr();
            }
        }
        if (ip == null || ip.isEmpty()) {
            ip = "inconnue";
        }
        return new ClientIp(ip);
    }
}
