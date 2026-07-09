package cm.afriland.titres.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

class ApiExceptionTest {

    // ─── Factories ────────────────────────────────────────────────────────────

    @Test
    void notFound_code_et_statut() {
        ApiException e = ApiException.notFound("ressource introuvable");
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        assertEquals("NOT_FOUND", e.getCode());
        assertEquals("ressource introuvable", e.getMessage());
    }

    @Test
    void badRequest_code_et_statut() {
        ApiException e = ApiException.badRequest("requête invalide");
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals("BAD_REQUEST", e.getCode());
        assertEquals("requête invalide", e.getMessage());
    }

    @Test
    void unauthorized_code_et_statut() {
        ApiException e = ApiException.unauthorized("non authentifié");
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
        assertEquals("UNAUTHORIZED", e.getCode());
    }

    @Test
    void forbidden_code_et_statut() {
        ApiException e = ApiException.forbidden("accès refusé");
        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
        assertEquals("FORBIDDEN", e.getCode());
        assertEquals("accès refusé", e.getMessage());
    }

    @Test
    void conflict_code_et_statut() {
        ApiException e = ApiException.conflict("doublon détecté");
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertEquals("CONFLICT", e.getCode());
    }

    @Test
    void tooManyRequests_code_et_statut() {
        ApiException e = ApiException.tooManyRequests();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        assertEquals("TOO_MANY_REQUESTS", e.getCode());
        assertNotNull(e.getMessage());
        assertFalse(e.getMessage().isBlank());
    }

    @Test
    void constructeur_direct() {
        ApiException e = new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CUSTOM", "detail");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatus());
        assertEquals("CUSTOM", e.getCode());
        assertEquals("detail", e.getMessage());
    }

    // ─── ensure ───────────────────────────────────────────────────────────────

    @Test
    void ensure_condition_vraie_ne_leve_pas_exception() {
        assertDoesNotThrow(() -> ApiException.ensure(true, "ne doit pas lever"));
    }

    @Test
    void ensure_condition_fausse_leve_badRequest() {
        ApiException ex = assertThrows(ApiException.class,
                () -> ApiException.ensure(false, "condition non remplie"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("condition non remplie", ex.getMessage());
    }

    @Test
    void ensure_condition_fausse_code_BAD_REQUEST() {
        ApiException ex = assertThrows(ApiException.class,
                () -> ApiException.ensure(false, "err"));
        assertEquals("BAD_REQUEST", ex.getCode());
    }

    // ─── est une RuntimeException ────────────────────────────────────────────

    @Test
    void apiException_est_une_RuntimeException() {
        assertInstanceOf(RuntimeException.class, ApiException.notFound("x"));
    }
}
