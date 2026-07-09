package cm.afriland.titres.notif;

import cm.afriland.titres.security.SecretCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtpSettingsServiceTest {

    @Mock JdbcTemplate jdbc;
    @Mock SecretCipher cipher;
    @InjectMocks OtpSettingsService service;

    private void simulerDbIndisponible() {
        doThrow(new RuntimeException("DB down"))
                .when(jdbc).queryForObject(anyString(), any(RowMapper.class));
    }

    // ─── OtpSettings.emailEnabled() / smsEnabled() ───────────────────────────

    @Test
    void emailEnabled_vrai_pour_canal_EMAIL() {
        var s = new OtpSettingsService.OtpSettings("EMAIL", 6, 300, 5, null, null, null);
        assertTrue(s.emailEnabled());
        assertFalse(s.smsEnabled());
    }

    @Test
    void smsEnabled_vrai_pour_canal_SMS() {
        var s = new OtpSettingsService.OtpSettings("SMS", 6, 300, 5, null, null, null);
        assertFalse(s.emailEnabled());
        assertTrue(s.smsEnabled());
    }

    @Test
    void email_et_sms_vrais_pour_canal_EMAIL_SMS() {
        var s = new OtpSettingsService.OtpSettings("EMAIL_SMS", 6, 300, 5, null, null, null);
        assertTrue(s.emailEnabled());
        assertTrue(s.smsEnabled());
    }

    @Test
    void email_et_sms_faux_pour_canal_inconnu() {
        var s = new OtpSettingsService.OtpSettings("PUSH", 6, 300, 5, null, null, null);
        assertFalse(s.emailEnabled());
        assertFalse(s.smsEnabled());
    }

    // ─── reload() — fallback sur DB indisponible ──────────────────────────────

    @Test
    void reload_exception_jdbc_applique_valeurs_par_defaut() {
        simulerDbIndisponible();
        service.reload();
        OtpSettingsService.OtpSettings s = service.get();
        assertEquals("EMAIL",  s.canal());
        assertEquals(6,        s.longueur());
        assertEquals(300,      s.ttlSecondes());
        assertEquals(5,        s.maxTentatives());
        assertNull(s.smsApiUrl());
        assertNull(s.smsApiKey());
        assertNull(s.smsExpediteur());
    }

    // ─── get() — déclenche reload() si cache null ────────────────────────────

    @Test
    void get_appelle_reload_automatiquement_si_cache_null() {
        simulerDbIndisponible();
        // cache est null (pas de @PostConstruct en test unitaire)
        OtpSettingsService.OtpSettings s = service.get();
        assertNotNull(s);
        assertEquals("EMAIL", s.canal()); // valeurs par défaut de secours
    }

    @Test
    void get_retourne_cache_sans_recharger_une_deuxieme_fois() {
        simulerDbIndisponible();
        service.get(); // 1er appel → reload()
        service.get(); // 2e appel → cache hit
        verify(jdbc, times(1)).queryForObject(anyString(), any(RowMapper.class));
    }

    // ─── publicView() ─────────────────────────────────────────────────────────

    @Test
    void publicView_contient_tous_les_champs_attendus() {
        simulerDbIndisponible();
        Map<String, Object> v = service.publicView();
        assertTrue(v.containsKey("canal"));
        assertTrue(v.containsKey("longueur"));
        assertTrue(v.containsKey("ttlSecondes"));
        assertTrue(v.containsKey("maxTentatives"));
        assertTrue(v.containsKey("smsApiUrl"));
        assertTrue(v.containsKey("smsExpediteur"));
        assertTrue(v.containsKey("smsApiKeySet"));
    }

    @Test
    void publicView_smsApiUrl_chaine_vide_quand_null() {
        simulerDbIndisponible();
        Map<String, Object> v = service.publicView();
        assertEquals("", v.get("smsApiUrl"));
    }

    @Test
    void publicView_smsExpediteur_chaine_vide_quand_null() {
        simulerDbIndisponible();
        Map<String, Object> v = service.publicView();
        assertEquals("", v.get("smsExpediteur"));
    }

    @Test
    void publicView_smsApiKeySet_false_quand_cle_nulle() {
        simulerDbIndisponible();
        Map<String, Object> v = service.publicView();
        assertEquals(false, v.get("smsApiKeySet"));
    }

    @Test
    void publicView_canal_correspond_au_cache() {
        simulerDbIndisponible();
        Map<String, Object> v = service.publicView();
        assertEquals("EMAIL", v.get("canal")); // valeur par défaut
    }
}
