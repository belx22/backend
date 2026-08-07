package cm.afriland.titres.security;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;

import cm.afriland.titres.config.AppProperties;
import cm.afriland.titres.error.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resolution du parametre {@link AuthUser} / {@link OptionalAuthUser} depuis
 * l'en-tete {@code Authorization}.
 */
class CurrentUserArgumentResolverTest {

    private JwtService jwt;
    private KeycloakTokenService keycloak;
    private AppProperties props;
    private CurrentUserArgumentResolver resolver;
    private NativeWebRequest webRequest;

    @BeforeEach
    void setUp() {
        jwt = mock(JwtService.class);
        keycloak = mock(KeycloakTokenService.class);
        props = mock(AppProperties.class);
        // Keycloak desactive par defaut : un jeton HS256 invalide reste un 401.
        resolver = new CurrentUserArgumentResolver(jwt, keycloak, props);
        webRequest = mock(NativeWebRequest.class);
    }

    private AuthUser user() {
        return new AuthUser(UUID.randomUUID(), "u@afb.cm", "AGENT", false);
    }

    @SuppressWarnings("rawtypes")
    private MethodParameter param(Class<?> type) {
        MethodParameter p = mock(MethodParameter.class);
        doReturn(type).when(p).getParameterType();
        return p;
    }

    private Object resolve(Class<?> type) {
        return resolver.resolveArgument(param(type), null, webRequest, null);
    }

    // ─── supportsParameter ──────────────────────────────────────────────────

    @Test
    void supporte_AuthUser_et_OptionalAuthUser_uniquement() {
        assertThat(resolver.supportsParameter(param(AuthUser.class))).isTrue();
        assertThat(resolver.supportsParameter(param(OptionalAuthUser.class))).isTrue();
        assertThat(resolver.supportsParameter(param(String.class))).isFalse();
    }

    // ─── AuthUser (obligatoire) ─────────────────────────────────────────────

    @Test
    void AuthUser_sans_en_tete_leve_401() {
        when(webRequest.getHeader("Authorization")).thenReturn(null);
        assertThatThrownBy(() -> resolve(AuthUser.class))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void AuthUser_en_tete_vide_leve_401() {
        when(webRequest.getHeader("Authorization")).thenReturn("   ");
        assertThatThrownBy(() -> resolve(AuthUser.class))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void AuthUser_format_non_bearer_leve_401() {
        when(webRequest.getHeader("Authorization")).thenReturn("Basic abc");
        assertThatThrownBy(() -> resolve(AuthUser.class))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void AuthUser_jeton_invalide_leve_401() {
        when(webRequest.getHeader("Authorization")).thenReturn("Bearer mauvais");
        when(jwt.verify("mauvais")).thenThrow(new RuntimeException("ko"));
        assertThatThrownBy(() -> resolve(AuthUser.class))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void AuthUser_jeton_valide_renvoie_l_utilisateur() {
        AuthUser u = user();
        when(webRequest.getHeader("Authorization")).thenReturn("Bearer bon");
        when(jwt.verify("bon")).thenReturn(u);
        assertThat(resolve(AuthUser.class)).isSameAs(u);
    }

    @Test
    void AuthUser_bascule_sur_keycloak_quand_HS256_echoue_et_issuer_configure() {
        AuthUser u = user();
        when(webRequest.getHeader("Authorization")).thenReturn("Bearer kc");
        when(jwt.verify("kc")).thenThrow(new RuntimeException("pas un jeton HS256"));
        when(props.isKeycloakEnabled()).thenReturn(true);
        when(keycloak.verify("kc")).thenReturn(u);
        assertThat(resolve(AuthUser.class)).isSameAs(u);
    }

    @Test
    void AuthUser_prefixe_bearer_minuscule_accepte() {
        AuthUser u = user();
        when(webRequest.getHeader("Authorization")).thenReturn("bearer bon");
        when(jwt.verify("bon")).thenReturn(u);
        assertThat(resolve(AuthUser.class)).isSameAs(u);
    }

    @Test
    void AuthUser_jeton_ebarbe() {
        AuthUser u = user();
        when(webRequest.getHeader("Authorization")).thenReturn("Bearer   espace  ");
        when(jwt.verify("espace")).thenReturn(u);
        assertThat(resolve(AuthUser.class)).isSameAs(u);
    }

    // ─── OptionalAuthUser (facultatif) ──────────────────────────────────────

    @Test
    void OptionalAuthUser_sans_en_tete_renvoie_vide() {
        when(webRequest.getHeader("Authorization")).thenReturn(null);
        Object r = resolve(OptionalAuthUser.class);
        assertThat(r).isInstanceOf(OptionalAuthUser.class);
        assertThat(((OptionalAuthUser) r).isPresent()).isFalse();
        assertThat(((OptionalAuthUser) r).value()).isNull();
    }

    @Test
    void OptionalAuthUser_jeton_valide_encapsule_l_utilisateur() {
        AuthUser u = user();
        when(webRequest.getHeader("Authorization")).thenReturn("Bearer bon");
        when(jwt.verify("bon")).thenReturn(u);
        Object r = resolve(OptionalAuthUser.class);
        assertThat(((OptionalAuthUser) r).value()).isSameAs(u);
    }

    @Test
    void OptionalAuthUser_jeton_present_mais_invalide_leve_401() {
        when(webRequest.getHeader("Authorization")).thenReturn("Bearer mauvais");
        when(jwt.verify("mauvais")).thenThrow(new RuntimeException("ko"));
        assertThatThrownBy(() -> resolve(OptionalAuthUser.class))
                .isInstanceOf(ApiException.class);
    }
}
