package cm.afriland.titres.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock JdbcTemplate jdbc;
    @InjectMocks AuditService auditService;

    // ─── log() — chemin nominal ───────────────────────────────────────────────

    @Test
    void log_appelle_jdbc_update_avec_bons_parametres() {
        auditService.log("user-1", "CONNEXION", AuditService.SUCCES, "ref-1", "127.0.0.1");
        verify(jdbc).update(
                contains("INSERT INTO audit_log"),
                eq("user-1"), eq("CONNEXION"), eq(AuditService.SUCCES),
                eq("ref-1"), eq("127.0.0.1"));
    }

    @Test
    void log_utilise_sql_insert_into_audit_log() {
        auditService.log("u", "A", "R", "ref", "1.2.3.4");
        verify(jdbc).update(contains("audit_log"), any(), any(), any(), any(), any());
    }

    // ─── log() — exception avalée ────────────────────────────────────────────

    @Test
    void log_exception_jdbc_ne_propage_pas() {
        doThrow(new RuntimeException("DB down"))
                .when(jdbc).update(anyString(), any(), any(), any(), any(), any());
        assertDoesNotThrow(() ->
                auditService.log("user-2", "ACTION", AuditService.ECHEC, "ref-2", "10.0.0.1"));
    }

    @Test
    void log_exception_jdbc_retourne_normalement() {
        doThrow(new RuntimeException("connexion perdue"))
                .when(jdbc).update(anyString(), any(), any(), any(), any(), any());
        // doit terminer sans exception quelle que soit la cause
        auditService.log("u", "B", AuditService.ECHEC, "r", "0.0.0.0");
        // si on arrive ici, le test est réussi
    }

    // ─── constantes publiques ─────────────────────────────────────────────────

    @Test
    void constante_SUCCES_vaut_SUCCES() {
        assertEquals("SUCCES", AuditService.SUCCES);
    }

    @Test
    void constante_ECHEC_vaut_ECHEC() {
        assertEquals("ECHEC", AuditService.ECHEC);
    }
}
