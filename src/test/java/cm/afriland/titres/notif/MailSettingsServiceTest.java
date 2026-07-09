package cm.afriland.titres.notif;

import cm.afriland.titres.config.AppProperties;
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
class MailSettingsServiceTest {

    @Mock JdbcTemplate jdbc;
    @Mock SecretCipher cipher;
    @Mock AppProperties props;
    @InjectMocks MailSettingsService service;

    private void useDbFallback() {
        doThrow(new RuntimeException("DB"))
                .when(jdbc).queryForObject(anyString(), any(RowMapper.class));
        when(props.hasSmtpEnvConfig()).thenReturn(false);
    }

    // ─── MailSettings.usable() ───────────────────────────────────────────────

    @Test
    void usable_false_si_disabled() {
        var s = new MailSettingsService.MailSettings(
                "smtp.ex.com", 587, "user", "pass", "from@ex.com", "Name",
                true, true, false, null, null);
        assertFalse(s.usable());
    }

    @Test
    void usable_false_si_host_null() {
        var s = new MailSettingsService.MailSettings(
                null, 587, "user", "pass", "from@ex.com", "Name",
                true, true, true, null, null);
        assertFalse(s.usable());
    }

    @Test
    void usable_false_si_host_vide() {
        var s = new MailSettingsService.MailSettings(
                "  ", 587, "user", "pass", "from@ex.com", "Name",
                true, true, true, null, null);
        assertFalse(s.usable());
    }

    @Test
    void usable_false_si_auth_true_et_password_null() {
        var s = new MailSettingsService.MailSettings(
                "smtp.ex.com", 587, "user", null, "from@ex.com", "Name",
                true, true, true, null, null);
        assertFalse(s.usable());
    }

    @Test
    void usable_false_si_auth_true_et_password_vide() {
        var s = new MailSettingsService.MailSettings(
                "smtp.ex.com", 587, "user", "  ", "from@ex.com", "Name",
                true, true, true, null, null);
        assertFalse(s.usable());
    }

    @Test
    void usable_true_si_auth_false_sans_password() {
        var s = new MailSettingsService.MailSettings(
                "smtp.ex.com", 25, null, null, "from@ex.com", "Name",
                false, false, true, null, null);
        assertTrue(s.usable());
    }

    @Test
    void usable_true_si_auth_true_avec_password() {
        var s = new MailSettingsService.MailSettings(
                "smtp.ex.com", 587, "user", "secret", "from@ex.com", "Name",
                true, true, true, null, null);
        assertTrue(s.usable());
    }

    // ─── get() / reload() fallback ───────────────────────────────────────────

    @Test
    void get_db_indisponible_retourne_valeurs_par_defaut() {
        useDbFallback();
        MailSettingsService.MailSettings s = service.get();
        assertNull(s.host());
        assertFalse(s.enabled());
        assertEquals("no-reply@afriland.cm", s.fromAddress());
        assertEquals("Afriland First Bank - DFT", s.fromName());
    }

    @Test
    void get_env_override_prioritaire_sur_db() {
        when(props.hasSmtpEnvConfig()).thenReturn(true);
        when(props.getSmtpHost()).thenReturn("env.smtp.com");
        when(props.getSmtpPort()).thenReturn(465);
        when(props.getSmtpUsername()).thenReturn("envuser");
        when(props.getSmtpPassword()).thenReturn("envpass");
        when(props.getMailFrom()).thenReturn("noreply@env.com");
        when(props.getMailFromName()).thenReturn("Env Name");
        when(props.isSmtpAuth()).thenReturn(true);
        when(props.isSmtpStarttls()).thenReturn(false);
        doThrow(new RuntimeException("DB")).when(jdbc).queryForObject(anyString(), any(RowMapper.class));

        MailSettingsService.MailSettings s = service.get();
        assertEquals("env.smtp.com", s.host());
        assertEquals(465, s.port());
        assertTrue(s.enabled());
        assertEquals("noreply@env.com", s.fromAddress());
    }

    // ─── publicView() ────────────────────────────────────────────────────────

    @Test
    void publicView_contient_tous_les_champs_attendus() {
        useDbFallback();
        Map<String, Object> v = service.publicView();
        for (String key : new String[]{"host", "port", "username", "fromAddress",
                "fromName", "auth", "starttls", "enabled", "logoUrl",
                "signature", "passwordSet", "envManaged"}) {
            assertTrue(v.containsKey(key), "champ manquant : " + key);
        }
    }

    @Test
    void publicView_host_null_affiche_chaine_vide() {
        useDbFallback();
        assertEquals("", service.publicView().get("host"));
    }

    @Test
    void publicView_passwordSet_false_quand_pas_de_password() {
        useDbFallback();
        assertEquals(false, service.publicView().get("passwordSet"));
    }

    @Test
    void publicView_envManaged_false_sans_env_smtp() {
        useDbFallback(); // hasSmtpEnvConfig() = false → envManaged = false
        assertEquals(false, service.publicView().get("envManaged"));
    }

    // ─── logoUrl() ───────────────────────────────────────────────────────────

    @Test
    void logoUrl_retombe_sur_frontend_quand_non_configure() {
        useDbFallback();
        when(props.getPrimaryFrontendOrigin()).thenReturn("https://app.example.com");
        assertEquals("https://app.example.com/Logo_Afriland.png", service.logoUrl());
    }

    // ─── brand() ─────────────────────────────────────────────────────────────

    @Test
    void brand_contient_le_contenu_fourni() {
        useDbFallback();
        when(props.getPrimaryFrontendOrigin()).thenReturn("https://app.example.com");
        String html = service.brand("<p>Bonjour</p>");
        assertTrue(html.contains("<p>Bonjour</p>"));
    }

    @Test
    void brand_contient_balise_img_avec_logo() {
        useDbFallback();
        when(props.getPrimaryFrontendOrigin()).thenReturn("https://front.example.com");
        String html = service.brand("content");
        assertTrue(html.contains("<img"));
        assertTrue(html.contains("Logo_Afriland.png"));
    }

    @Test
    void brand_sans_signature_pas_de_footer_style() {
        useDbFallback(); // signature = null dans le fallback
        when(props.getPrimaryFrontendOrigin()).thenReturn("https://app.example.com");
        String html = service.brand("test");
        assertFalse(html.contains("border-top:1px solid #eee"));
    }

    @Test
    void brand_retourne_div_englobante() {
        useDbFallback();
        when(props.getPrimaryFrontendOrigin()).thenReturn("https://app.example.com");
        String html = service.brand("x");
        assertTrue(html.startsWith("<div"));
        assertTrue(html.endsWith("</div>"));
    }
}
