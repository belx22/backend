package cm.afriland.titres.config;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reglage GLOBAL de la capture faciale (migration V35), a ligne unique.
 *
 * <p>{@link #isEnabled()} est la source de verite consultee par les points qui
 * exigent une photo (inscription, cosignature). Combine au drapeau par compte
 * {@code users.prioritaire}, il donne la regle : <em>photo exigee = scan actif
 * ET personne non prioritaire</em>.</p>
 */
@Service
public class FaceScanSettingsService {

    private final JdbcTemplate jdbc;

    public FaceScanSettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** La capture faciale est-elle globalement exigee ? Defaut prudent : {@code true}. */
    public boolean isEnabled() {
        Boolean v = jdbc.query("SELECT enabled FROM face_scan_settings WHERE id = TRUE",
                rs -> rs.next() ? rs.getBoolean("enabled") : Boolean.TRUE);
        return v == null || v;
    }

    /** Active/desactive globalement la capture faciale. */
    public void setEnabled(boolean enabled, UUID par) {
        jdbc.update("UPDATE face_scan_settings SET enabled = ?, updated_at = now(), "
                + "updated_by = ? WHERE id = TRUE", enabled, par);
    }

    /** Vue exposee (API). */
    public Map<String, Object> view() {
        return Map.of("enabled", isEnabled());
    }
}
