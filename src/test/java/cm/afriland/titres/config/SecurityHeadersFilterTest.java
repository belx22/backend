package cm.afriland.titres.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    // ─── Helper ───────────────────────────────────────────────────────────────

    private MockHttpServletResponse runFilter() throws ServletException, IOException {
        MockHttpServletRequest  req  = new MockHttpServletRequest();
        MockHttpServletResponse res  = new MockHttpServletResponse();
        FilterChain             chain = mock(FilterChain.class);
        filter.doFilterInternal(req, res, chain);
        return res;
    }

    // ─── En-têtes de sécurité ─────────────────────────────────────────────────

    @Test
    void ajoute_X_Content_Type_Options_nosniff() throws Exception {
        assertEquals("nosniff", runFilter().getHeader("X-Content-Type-Options"));
    }

    @Test
    void ajoute_X_Frame_Options_DENY() throws Exception {
        assertEquals("DENY", runFilter().getHeader("X-Frame-Options"));
    }

    @Test
    void ajoute_Referrer_Policy_no_referrer() throws Exception {
        assertEquals("no-referrer", runFilter().getHeader("Referrer-Policy"));
    }

    @Test
    void ajoute_Content_Security_Policy_restrictive() throws Exception {
        assertEquals("default-src 'none'; frame-ancestors 'none'",
                runFilter().getHeader("Content-Security-Policy"));
    }

    @Test
    void ajoute_Strict_Transport_Security_2ans() throws Exception {
        assertEquals("max-age=63072000; includeSubDomains",
                runFilter().getHeader("Strict-Transport-Security"));
    }

    @Test
    void ajoute_Permissions_Policy_geolocation_camera_microphone() throws Exception {
        assertEquals("geolocation=(), camera=(), microphone=()",
                runFilter().getHeader("Permissions-Policy"));
    }

    // ─── Chaîne de filtres ────────────────────────────────────────────────────

    @Test
    void appelle_filterChain_doFilter_apres_ajout_des_en_tetes() throws Exception {
        MockHttpServletRequest  req   = new MockHttpServletRequest();
        MockHttpServletResponse res   = new MockHttpServletResponse();
        FilterChain             chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }
}
