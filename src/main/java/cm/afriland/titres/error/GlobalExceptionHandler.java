package cm.afriland.titres.error;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Traduit toute exception en reponse JSON {@code {"error":{"code","message"}}}.
 *
 * Securite : les erreurs internes sont journalisees cote serveur mais renvoyees
 * au client sous une forme generique — aucune fuite de detail technique.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<Object> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("error", Map.of("code", code, "message", message)));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException ex) {
        return body(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " : " + e.getDefaultMessage())
                .findFirst()
                .orElse("champ invalide");
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Donnees invalides : " + detail);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Object> handleBadInput(Exception ex) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Corps ou parametre de requete invalide.");
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Object> handleNotFound(NoHandlerFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Route inexistante.");
    }

    /** Violation d'unicite SQL (code 23505) — traitee comme conflit (409). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex) {
        String msg = ex.getMostSpecificCause().getMessage();
        if (msg != null && msg.contains("duplicate key")) {
            return body(HttpStatus.CONFLICT, "CONFLICT", "Cette ressource existe deja.");
        }
        log.error("violation d'integrite de donnees", ex);
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Donnees incoherentes ou en conflit.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleInternal(Exception ex) {
        log.error("erreur interne", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Une erreur interne est survenue.");
    }
}
