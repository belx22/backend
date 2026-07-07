package cm.afriland.titres.security;

import cm.afriland.titres.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthUserTest {

    private static AuthUser user(String role, boolean mustChange) {
        return new AuthUser(UUID.randomUUID(), role.toLowerCase() + "@afb.cm", role, mustChange);
    }

    // ─── require ──────────────────────────────────────────────────────────────

    @Test
    void require_permission_accordee_ne_leve_pas_exception() {
        assertDoesNotThrow(() -> user("AGENT", false).require(Permission.EMISSION_CREATE));
    }

    @Test
    void require_superviseur_peut_publier() {
        assertDoesNotThrow(() -> user("SUPERVISEUR", false).require(Permission.EMISSION_VALIDATE));
    }

    @Test
    void require_admin_peut_lire_audit() {
        assertDoesNotThrow(() -> user("ADMIN", false).require(Permission.AUDIT_READ));
    }

    @Test
    void require_permission_refusee_leve_ApiException_403() {
        ApiException ex = assertThrows(ApiException.class,
                () -> user("CLIENT_PP", false).require(Permission.EMISSION_CREATE));
        assertEquals(403, ex.getStatus().value());
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    void require_agent_ne_peut_pas_publier() {
        assertThrows(ApiException.class,
                () -> user("AGENT", false).require(Permission.EMISSION_VALIDATE));
    }

    @Test
    void require_client_pm_ne_peut_pas_valider_adjudication() {
        assertThrows(ApiException.class,
                () -> user("CLIENT_PM", false).require(Permission.ORDER_RESULT_VALIDATE));
    }

    // ─── isClient ─────────────────────────────────────────────────────────────

    @Test
    void isClient_vrai_pour_client_pp() {
        assertTrue(user("CLIENT_PP", false).isClient());
    }

    @Test
    void isClient_vrai_pour_client_pm() {
        assertTrue(user("CLIENT_PM", false).isClient());
    }

    @Test
    void isClient_faux_pour_agent() {
        assertFalse(user("AGENT", false).isClient());
    }

    @Test
    void isClient_faux_pour_superviseur() {
        assertFalse(user("SUPERVISEUR", false).isClient());
    }

    @Test
    void isClient_faux_pour_admin() {
        assertFalse(user("ADMIN", false).isClient());
    }

    // ─── isStaff ──────────────────────────────────────────────────────────────

    @Test
    void isStaff_vrai_pour_agent() {
        assertTrue(user("AGENT", false).isStaff());
    }

    @Test
    void isStaff_vrai_pour_superviseur() {
        assertTrue(user("SUPERVISEUR", false).isStaff());
    }

    @Test
    void isStaff_vrai_pour_admin() {
        assertTrue(user("ADMIN", false).isStaff());
    }

    @Test
    void isStaff_faux_pour_client_pp() {
        assertFalse(user("CLIENT_PP", false).isStaff());
    }

    // ─── mustChangePassword ───────────────────────────────────────────────────

    @Test
    void mustChangePassword_vrai_restitue_vrai() {
        assertTrue(user("ADMIN", true).mustChangePassword());
    }

    @Test
    void mustChangePassword_faux_restitue_faux() {
        assertFalse(user("AGENT", false).mustChangePassword());
    }

    // ─── record fields ────────────────────────────────────────────────────────

    @Test
    void record_expose_id_email_role() {
        UUID id = UUID.randomUUID();
        AuthUser u = new AuthUser(id, "test@afb.cm", "AGENT", false);
        assertEquals(id, u.id());
        assertEquals("test@afb.cm", u.email());
        assertEquals("AGENT", u.role());
    }
}
