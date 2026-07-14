package cm.afriland.titres.security;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifie le filtre « mot de passe provisoire » : tant que {@code mcp} est vrai,
 * seules les routes de la liste blanche passent ; toute autre est bloquee (403).
 */
class MustChangePasswordInterceptorTest {

    private JwtService jwt;
    private MustChangePasswordInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter body;

    @BeforeEach
    void setUp() throws Exception {
        jwt = mock(JwtService.class);
        interceptor = new MustChangePasswordInterceptor(jwt);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
    }

    private AuthUser user(boolean mustChange) {
        return new AuthUser(UUID.randomUUID(), "u@afb.cm", "CLIENT_PP", mustChange);
    }

    private void requete(String method, String uri, String authHeader) {
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getHeader("Authorization")).thenReturn(authHeader);
    }

    @Test
    void requete_OPTIONS_toujours_autorisee() throws Exception {
        requete("OPTIONS", "/api/v1/orders", null);
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(jwt, never()).verify(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void routes_de_la_liste_blanche_toujours_autorisees() throws Exception {
        for (String uri : new String[]{
                "/api/v1/auth/change-password", "/api/v1/auth/me", "/api/v1/auth/logout"}) {
            requete("GET", uri, "Bearer x");
            assertThat(interceptor.preHandle(request, response, new Object()))
                    .as(uri).isTrue();
        }
        verify(jwt, never()).verify(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void sans_en_tete_Authorization_laisse_passer() throws Exception {
        requete("GET", "/api/v1/orders", null);
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void en_tete_non_bearer_laisse_passer() throws Exception {
        requete("GET", "/api/v1/orders", "Basic abc");
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void jeton_invalide_laisse_le_resolveur_gerer_le_401() throws Exception {
        requete("GET", "/api/v1/orders", "Bearer mauvais");
        when(jwt.verify("mauvais")).thenThrow(new RuntimeException("jwt ko"));
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void utilisateur_sans_contrainte_passe() throws Exception {
        requete("GET", "/api/v1/orders", "Bearer ok");
        when(jwt.verify("ok")).thenReturn(user(false));
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void utilisateur_a_changer_est_bloque_403_avec_corps_json() throws Exception {
        requete("GET", "/api/v1/orders", "Bearer mcp");
        when(jwt.verify("mcp")).thenReturn(user(true));

        boolean autorise = interceptor.preHandle(request, response, new Object());

        assertThat(autorise).isFalse();
        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(response).setContentType("application/json;charset=UTF-8");
        assertThat(body.toString())
                .contains("PASSWORD_CHANGE_REQUIRED")
                .contains("changer votre mot de passe");
    }

    @Test
    void le_jeton_bearer_est_ebarbe_avant_verification() throws Exception {
        requete("GET", "/api/v1/orders", "Bearer   tok-espaces  ");
        when(jwt.verify("tok-espaces")).thenReturn(user(false));
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(jwt).verify("tok-espaces");
    }

    @Test
    void le_prefixe_bearer_est_insensible_a_la_casse() throws Exception {
        requete("GET", "/api/v1/orders", "BEARER tok");
        when(jwt.verify("tok")).thenReturn(user(true));
        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
    }
}
