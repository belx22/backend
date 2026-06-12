package cm.afriland.titres.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonde de sante — utilisee par Docker et les superviseurs de service.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code GET /health} — verifie la disponibilite de la base de donnees. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbOk;
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            dbOk = true;
        } catch (RuntimeException e) {
            dbOk = false;
        }
        HttpStatus status = dbOk ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(Map.of(
                "status", dbOk ? "ok" : "degraded",
                "database", dbOk ? "up" : "down"));
    }
}
