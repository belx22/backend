package cm.afriland.titres.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Balayage des signatures de co-titulaires expirees : chaque branche de
 * {@link JointSignatureScheduler#rejectExpired()} est exercee (rien a faire,
 * rejet effectif, ordre deja traite entre-temps, panne de la base).
 */
@ExtendWith(MockitoExtension.class)
class JointSignatureSchedulerTest {

    @Mock JdbcTemplate jdbc;
    @InjectMocks JointSignatureScheduler scheduler;

    private static final UUID ORDER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACCOUNT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static Map<String, Object> expiredOrder() {
        return Map.of("id", ORDER, "client_id", ACCOUNT, "reference", "ORD-2026-0001");
    }

    /** Aucune signature expiree : on sort sans ecrire une seule ligne. */
    @Test
    void neTouchePasALaBaseQuandAucunOrdreExpire() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        scheduler.rejectExpired();

        verify(jdbc, never()).update(anyString());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    /**
     * Cas nominal : les signatures passent EXPIRED, l'ordre passe ANNULE et
     * tous les signataires du compte sont notifies.
     *
     * <p>Les stubs de {@code update} sont {@code lenient} : {@code update(sql)}
     * et {@code update(sql, args...)} sont la <em>meme</em> methode variadique,
     * si bien qu'en mode strict l'appel sans argument leverait un
     * {@code PotentialStubbingProblem} — que le {@code catch} du scheduler
     * avalerait, masquant l'echec.</p>
     */
    @Test
    void rejetteLOrdreEtNotifieLesSignataires() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(expiredOrder()));
        lenient().when(jdbc.update(contains("UPDATE orders SET status = 'ANNULE'"), eq(ORDER))).thenReturn(1);

        scheduler.rejectExpired();

        verify(jdbc).update(contains("UPDATE order_signatures SET status = 'EXPIRED'"));
        verify(jdbc).update(contains("UPDATE orders SET status = 'ANNULE'"), eq(ORDER));
        verify(jdbc).update(
                contains("INSERT INTO notifications"),
                contains("ORD-2026-0001"), eq("ORD-2026-0001"), eq(ACCOUNT), eq(ACCOUNT));
    }

    /**
     * L'ordre a change d'etat entre la selection et la mise a jour (course avec
     * une validation manuelle) : aucune notification ne doit partir.
     */
    @Test
    void neNotifiePasSiLOrdreNEstPlusEnAttenteDeSignatures() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(expiredOrder()));
        lenient().when(jdbc.update(contains("UPDATE orders SET status = 'ANNULE'"), eq(ORDER))).thenReturn(0);

        scheduler.rejectExpired();

        verify(jdbc, never()).update(contains("INSERT INTO notifications"),
                any(), any(), any(), any());
    }

    /** Une panne de la base est journalisee, jamais propagee : le balayage suivant doit avoir lieu. */
    @Test
    void avaleLesErreursPourNePasInterrompreLesBalayagesSuivants() {
        when(jdbc.queryForList(anyString())).thenThrow(new RuntimeException("base indisponible"));

        assertThatCode(() -> scheduler.rejectExpired()).doesNotThrowAnyException();
    }
}
