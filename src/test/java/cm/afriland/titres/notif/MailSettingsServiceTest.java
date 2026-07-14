package cm.afriland.titres.notif;

import cm.afriland.titres.config.AppProperties;
import cm.afriland.titres.security.SecretCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Map;
import java.util.UUID;

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

    // ─── reload() — chemin nominal (le RowMapper est reellement execute) ──────

    /**
     * Branche le vrai {@code RowMapper} sur un {@link java.sql.ResultSet} simule :
     * stuber {@code queryForObject} court-circuiterait le mapper, laissant ses
     * lignes (et {@code decryptPasswordSafely}) non couvertes.
     */
    private void simulerLigneMail(String passwordEnc, String logoUrl, String signature)
            throws java.sql.SQLException {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getString("host")).thenReturn("smtp.afriland.cm");
        when(rs.getInt("port")).thenReturn(587);
        when(rs.getString("username")).thenReturn("dft@afriland.cm");
        when(rs.getString("password_enc")).thenReturn(passwordEnc);
        when(rs.getString("from_address")).thenReturn("no-reply@afriland.cm");
        when(rs.getString("from_name")).thenReturn("Afriland DFT");
        when(rs.getBoolean("auth")).thenReturn(true);
        when(rs.getBoolean("starttls")).thenReturn(true);
        when(rs.getBoolean("enabled")).thenReturn(true);
        when(rs.getString("logo_url")).thenReturn(logoUrl);
        when(rs.getString("signature")).thenReturn(signature);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class)))
                .thenAnswer(inv -> inv.getArgument(1, RowMapper.class).mapRow(rs, 1));
        lenient().when(props.hasSmtpEnvConfig()).thenReturn(false);
    }

    @Test
    void reload_lit_la_configuration_et_dechiffre_le_mot_de_passe() throws java.sql.SQLException {
        simulerLigneMail("chiffre", "https://cdn/logo.png", "Service DFT");
        when(cipher.decrypt("chiffre")).thenReturn("motdepasse");

        service.reload();
        var s = service.get();

        assertEquals("smtp.afriland.cm", s.host());
        assertEquals(587, s.port());
        assertEquals("motdepasse", s.password());
        assertEquals("https://cdn/logo.png", s.logoUrl());
        assertEquals("Service DFT", s.signature());
        assertTrue(s.usable());
    }

    /**
     * Secret de chiffrement change : le mot de passe devient illisible. Le reste
     * de la configuration doit rester charge, avec un mot de passe null.
     */
    @Test
    void reload_tolere_un_mot_de_passe_indechiffrable() throws java.sql.SQLException {
        simulerLigneMail("chiffre-avec-ancien-secret", null, null);
        when(cipher.decrypt(anyString())).thenThrow(new RuntimeException("cle invalide"));

        service.reload();
        var s = service.get();

        assertEquals("smtp.afriland.cm", s.host());
        assertNull(s.password());
        // auth=true sans mot de passe -> envoi impossible tant qu'il n'est pas re-saisi.
        assertFalse(s.usable());
    }

    @Test
    void reload_ne_dechiffre_pas_un_mot_de_passe_blanc() throws java.sql.SQLException {
        simulerLigneMail("   ", null, null);

        service.reload();

        assertNull(service.get().password());
        verify(cipher, never()).decrypt(anyString());
    }

    // ─── publicView() / logoUrl() / brand() sur valeurs renseignees ───────────

    @Test
    void publicView_expose_les_valeurs_non_nulles_sans_le_mot_de_passe()
            throws java.sql.SQLException {
        simulerLigneMail("chiffre", "https://cdn/logo.png", "Service DFT");
        when(cipher.decrypt("chiffre")).thenReturn("motdepasse");
        service.reload();

        Map<String, Object> v = service.publicView();

        assertEquals("smtp.afriland.cm", v.get("host"));
        assertEquals("dft@afriland.cm", v.get("username"));
        assertEquals("https://cdn/logo.png", v.get("logoUrl"));
        assertEquals("Service DFT", v.get("signature"));
        assertEquals(true, v.get("passwordSet"));
        assertFalse(v.containsValue("motdepasse"), "le mot de passe ne doit jamais sortir");
    }

    @Test
    void logoUrl_utilise_l_url_configuree_quand_elle_existe() throws java.sql.SQLException {
        simulerLigneMail(null, "https://cdn/logo.png", null);

        assertEquals("https://cdn/logo.png", service.logoUrl());
    }

    @Test
    void brand_ajoute_un_pied_de_page_quand_une_signature_existe() throws java.sql.SQLException {
        simulerLigneMail(null, "https://cdn/logo.png", "Service DFT");

        String html = service.brand("<p>corps</p>");

        assertTrue(html.contains("Service DFT"));
        assertTrue(html.contains("border-top:1px solid #eee"));
        assertTrue(html.contains("<p>corps</p>"));
    }

    // ─── envOverride() ───────────────────────────────────────────────────────

    /**
     * Expediteur laisse au defaut : Office365 exige que le « from » soit le compte
     * authentifie, donc l'adresse de connexion prend le relais.
     */
    @Test
    void envOverride_remplace_l_expediteur_par_defaut_par_le_compte_authentifie() {
        doThrow(new RuntimeException("DB")).when(jdbc)
                .queryForObject(anyString(), any(RowMapper.class));
        when(props.hasSmtpEnvConfig()).thenReturn(true);
        when(props.getSmtpHost()).thenReturn("smtp.office365.com");
        when(props.getSmtpPort()).thenReturn(587);
        when(props.getSmtpUsername()).thenReturn("dft@afriland.cm");
        when(props.getSmtpPassword()).thenReturn("secret");
        when(props.getMailFrom()).thenReturn("no-reply@afriland.cm");
        when(props.getMailFromName()).thenReturn("Afriland");
        when(props.isSmtpAuth()).thenReturn(true);
        when(props.isSmtpStarttls()).thenReturn(true);

        var s = service.get();

        assertEquals("dft@afriland.cm", s.fromAddress());
        assertEquals("smtp.office365.com", s.host());
        assertTrue(s.enabled());
        assertTrue(service.isEnvManaged());
    }

    /** Un expediteur dedie explicite est conserve tel quel. */
    @Test
    void envOverride_conserve_un_expediteur_dedie() {
        doThrow(new RuntimeException("DB")).when(jdbc)
                .queryForObject(anyString(), any(RowMapper.class));
        when(props.hasSmtpEnvConfig()).thenReturn(true);
        when(props.getSmtpHost()).thenReturn("smtp.office365.com");
        when(props.getSmtpPort()).thenReturn(587);
        when(props.getSmtpUsername()).thenReturn("compte@afriland.cm");
        when(props.getSmtpPassword()).thenReturn("secret");
        when(props.getMailFrom()).thenReturn("titres@afriland.cm");
        when(props.getMailFromName()).thenReturn("Afriland");
        when(props.isSmtpAuth()).thenReturn(true);
        when(props.isSmtpStarttls()).thenReturn(true);

        assertEquals("titres@afriland.cm", service.get().fromAddress());
    }

    /** Mot de passe d'environnement vide : normalise en null. */
    @Test
    void envOverride_normalise_un_mot_de_passe_vide_en_null() {
        doThrow(new RuntimeException("DB")).when(jdbc)
                .queryForObject(anyString(), any(RowMapper.class));
        when(props.hasSmtpEnvConfig()).thenReturn(true);
        when(props.getSmtpHost()).thenReturn("smtp.ex.com");
        when(props.getSmtpPort()).thenReturn(587);
        when(props.getSmtpUsername()).thenReturn("  ");
        when(props.getSmtpPassword()).thenReturn("   ");
        when(props.getMailFrom()).thenReturn("titres@afriland.cm");
        when(props.getMailFromName()).thenReturn("Afriland");
        when(props.isSmtpAuth()).thenReturn(false);
        when(props.isSmtpStarttls()).thenReturn(true);

        var s = service.get();

        assertNull(s.password());
        assertNull(s.username());
        assertTrue(service.usable());
    }

    // ─── update() ────────────────────────────────────────────────────────────

    /** update() ne lit jamais le cache : seule la requete de reload() doit echouer. */
    private void reloadEchoue() {
        doThrow(new RuntimeException("DB")).when(jdbc)
                .queryForObject(anyString(), any(RowMapper.class));
    }

    /** Mot de passe non nul : il est chiffre avant ecriture. */
    @Test
    void update_chiffre_le_nouveau_mot_de_passe() {
        reloadEchoue();
        when(cipher.encrypt("nouveau")).thenReturn("chiffre");
        UUID admin = UUID.randomUUID();

        service.update("smtp.ex.com", 587, "user", "nouveau", "from@ex.com", "Nom",
                true, true, true, "https://cdn/logo.png", "Signature", admin);

        verify(jdbc).update(anyString(), eq("smtp.ex.com"), eq(587), eq("user"), eq("chiffre"),
                eq("from@ex.com"), eq("Nom"), eq(true), eq(true), eq(true),
                eq("https://cdn/logo.png"), eq("Signature"), eq(admin));
    }

    /** Mot de passe nul : l'ancien secret est relu en base et conserve. */
    @Test
    void update_conserve_le_mot_de_passe_existant_quand_aucun_n_est_fourni() {
        reloadEchoue();
        when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenReturn("ancien-chiffre");
        UUID admin = UUID.randomUUID();

        service.update("smtp.ex.com", 587, "user", null, "from@ex.com", "Nom",
                true, true, true, null, null, admin);

        verify(cipher, never()).encrypt(anyString());
        verify(jdbc).update(anyString(), eq("smtp.ex.com"), eq(587), eq("user"),
                eq("ancien-chiffre"), eq("from@ex.com"), eq("Nom"), eq(true), eq(true), eq(true),
                isNull(), isNull(), eq(admin));
    }

    /** Les chaines blanches sont normalisees en null (trimToNull). */
    @Test
    void update_normalise_les_chaines_blanches_en_null() {
        reloadEchoue();
        when(cipher.encrypt("")).thenReturn(null);
        UUID admin = UUID.randomUUID();

        service.update("  ", 587, "  ", "", "  ", "  ", false, false, false, "  ", "  ", admin);

        verify(jdbc).update(anyString(), isNull(), eq(587), isNull(), isNull(),
                isNull(), isNull(), eq(false), eq(false), eq(false), isNull(), isNull(), eq(admin));
    }

    // ─── buildSender() ───────────────────────────────────────────────────────

    @Test
    void buildSender_reporte_hote_port_et_identifiants() throws java.sql.SQLException {
        simulerLigneMail("chiffre", null, null);
        when(cipher.decrypt("chiffre")).thenReturn("motdepasse");

        JavaMailSenderImpl sender = service.buildSender();

        assertEquals("smtp.afriland.cm", sender.getHost());
        assertEquals(587, sender.getPort());
        assertEquals("dft@afriland.cm", sender.getUsername());
        assertEquals("motdepasse", sender.getPassword());
        assertEquals("true", sender.getJavaMailProperties().get("mail.smtp.auth"));
        assertEquals("true", sender.getJavaMailProperties().get("mail.smtp.starttls.enable"));
        assertEquals("10000", sender.getJavaMailProperties().get("mail.smtp.timeout"));
    }

    /** Sans identifiants, le sender ne doit pas les positionner. */
    @Test
    void buildSender_sans_identifiants() {
        useDbFallback();

        JavaMailSenderImpl sender = service.buildSender();

        assertNull(sender.getUsername());
        assertNull(sender.getPassword());
        assertEquals("smtp", sender.getJavaMailProperties().get("mail.transport.protocol"));
    }
}
