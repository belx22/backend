package cm.afriland.titres.error;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unchecked")
    private String code(ResponseEntity<Object> r) {
        return (String) ((Map<String, Object>) ((Map<String, Object>) r.getBody()).get("error")).get("code");
    }

    /** Methode factice servant a construire un MethodParameter reel. */
    @SuppressWarnings("unused")
    static void dummy(String x) { /* cible du MethodParameter */ }

    private MethodArgumentNotValidException validationEx(boolean withFieldError) throws Exception {
        MethodParameter mp = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummy", String.class), 0);
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "obj");
        if (withFieldError) {
            br.addError(new FieldError("obj", "email", "format invalide"));
        }
        return new MethodArgumentNotValidException(mp, br);
    }

    @Test
    void handleApi_reprend_statut_et_code() {
        ResponseEntity<Object> r = handler.handleApi(ApiException.notFound("absent"));
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
        assertEquals("NOT_FOUND", code(r));
    }

    @Test
    void handleValidation_400_avec_premier_champ() throws Exception {
        ResponseEntity<Object> r = handler.handleValidation(validationEx(true));
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("BAD_REQUEST", code(r));
    }

    @Test
    void handleValidation_sans_champ_message_par_defaut() throws Exception {
        assertEquals(HttpStatus.BAD_REQUEST, handler.handleValidation(validationEx(false)).getStatusCode());
    }

    @Test
    void handleBadInput_400() {
        ResponseEntity<Object> r = handler.handleBadInput(
                new HttpMessageNotReadableException("json casse"));
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("BAD_REQUEST", code(r));
    }

    @Test
    void handleDataIntegrity_duplicate_key_409() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "wrap", new SQLException("ERROR: duplicate key value violates unique constraint"));
        ResponseEntity<Object> r = handler.handleDataIntegrity(ex);
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertEquals("CONFLICT", code(r));
    }

    @Test
    void handleDataIntegrity_autre_cause_400() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "wrap", new SQLException("violates check constraint xyz"));
        ResponseEntity<Object> r = handler.handleDataIntegrity(ex);
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("BAD_REQUEST", code(r));
    }

    @Test
    void handleInternal_500_generique() {
        ResponseEntity<Object> r = handler.handleInternal(new RuntimeException("boom"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, r.getStatusCode());
        assertEquals("INTERNAL_ERROR", code(r));
    }
}
