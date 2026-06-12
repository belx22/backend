package cm.afriland.titres.error;

import org.springframework.http.HttpStatus;

/**
 * Erreur applicative unifiee. Chaque instance porte un statut HTTP, un code
 * stable et un message destine au client. Les details techniques internes ne
 * sont jamais exposes (cf. {@link GlobalExceptionHandler}).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    public static ApiException tooManyRequests() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                "Trop de tentatives. Reessayez plus tard.");
    }

    /** Echoue avec 400 si la condition n'est pas remplie (equivalent de {@code ensure}). */
    public static void ensure(boolean condition, String message) {
        if (!condition) {
            throw badRequest(message);
        }
    }
}
