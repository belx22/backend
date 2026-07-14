package cm.afriland.titres.notif;

import cm.afriland.titres.security.SecretCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

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

    // ─── reload() — chemin nominal (le RowMapper est reellement execute) ──────

    /**
     * Branche le vrai {@code RowMapper} sur un {@link ResultSet} simule : sans
     * cela, stuber {@code queryForObject} court-circuiterait le mapper et ses
     * lignes resteraient non couvertes.
     */
    private void simulerLigneOtp(String canal, String url, String cleChiffree, String expediteur)
            throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("canal")).thenReturn(canal);
        when(rs.getInt("longueur")).thenReturn(8);
        when(rs.getInt("ttl_secondes")).thenReturn(600);
        when(rs.getInt("max_tentatives")).thenReturn(3);
        when(rs.getString("sms_api_url")).thenReturn(url);
        when(rs.getString("sms_api_key_enc")).thenReturn(cleChiffree);
        when(rs.getString("sms_expediteur")).thenReturn(expediteur);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class)))
                .thenAnswer(inv -> inv.getArgument(1, RowMapper.class).mapRow(rs, 1));
    }

    @Test
    void reload_lit_les_parametres_et_dechiffre_la_cle_sms() throws SQLException {
        simulerLigneOtp("EMAIL_SMS", "https://sms.example.cm", "chiffre", "AFRILAND");
        when(cipher.decrypt("chiffre")).thenReturn("cle-en-clair");

        service.reload();
        var s = service.get();

        assertEquals("EMAIL_SMS", s.canal());
        assertEquals(8, s.longueur());
        assertEquals(600, s.ttlSecondes());
        assertEquals(3, s.maxTentatives());
        assertEquals("https://sms.example.cm", s.smsApiUrl());
        assertEquals("cle-en-clair", s.smsApiKey());
        assertEquals("AFRILAND", s.smsExpediteur());
    }

    @Test
    void publicView_expose_les_valeurs_non_nulles_sans_jamais_la_cle() throws SQLException {
        simulerLigneOtp("SMS", "https://sms.example.cm", "chiffre", "AFRILAND");
        when(cipher.decrypt("chiffre")).thenReturn("cle-en-clair");
        service.reload();

        Map<String, Object> v = service.publicView();

        assertEquals("https://sms.example.cm", v.get("smsApiUrl"));
        assertEquals("AFRILAND", v.get("smsExpediteur"));
        assertEquals(true, v.get("smsApiKeySet"));
        assertFalse(v.containsValue("cle-en-clair"), "la cle d'API ne doit jamais sortir");
    }

    /** Une cle presente mais blanche ne compte pas comme configuree. */
    @Test
    void publicView_smsApiKeySet_false_quand_la_cle_est_blanche() throws SQLException {
        simulerLigneOtp("SMS", null, "chiffre", null);
        when(cipher.decrypt("chiffre")).thenReturn("   ");
        service.reload();

        assertEquals(false, service.publicView().get("smsApiKeySet"));
    }

    // ─── update() ─────────────────────────────────────────────────────────────

    /** {@code newApiKey} non nul : la cle est chiffree avant ecriture. */
    @Test
    void update_chiffre_la_nouvelle_cle_sms() {
        simulerDbIndisponible();
        when(cipher.encrypt("nouvelle-cle")).thenReturn("chiffre");
        UUID admin = UUID.randomUUID();

        service.update("SMS", 6, 300, 5, "https://sms.example.cm", "nouvelle-cle", "AFB", admin);

        verify(cipher).encrypt("nouvelle-cle");
        verify(jdbc).update(anyString(), eq("SMS"), eq(6), eq(300), eq(5),
                eq("https://sms.example.cm"), eq("chiffre"), eq("AFB"), eq(admin));
    }

    /** {@code newApiKey} nul : la cle deja stockee est relue et conservee telle quelle. */
    @Test
    void update_conserve_la_cle_existante_quand_aucune_nouvelle_n_est_fournie() {
        simulerDbIndisponible();
        when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenReturn("ancienne-chiffree");
        UUID admin = UUID.randomUUID();

        service.update("EMAIL", 6, 300, 5, null, null, null, admin);

        verify(cipher, never()).encrypt(anyString());
        verify(jdbc).update(anyString(), eq("EMAIL"), eq(6), eq(300), eq(5),
                isNull(), eq("ancienne-chiffree"), isNull(), eq(admin));
    }

    /** URL et expediteur blancs sont normalises en null (trimToNull). */
    @Test
    void update_normalise_les_chaines_blanches_en_null() {
        simulerDbIndisponible();
        when(cipher.encrypt("k")).thenReturn("enc");
        UUID admin = UUID.randomUUID();

        service.update("EMAIL", 6, 300, 5, "   ", "k", "  ", admin);

        verify(jdbc).update(anyString(), eq("EMAIL"), eq(6), eq(300), eq(5),
                isNull(), eq("enc"), isNull(), eq(admin));
    }

    /** Les espaces de bord d'une valeur reelle sont retires, la valeur conservee. */
    @Test
    void update_retire_les_espaces_de_bord() {
        simulerDbIndisponible();
        when(cipher.encrypt("k")).thenReturn("enc");
        UUID admin = UUID.randomUUID();

        service.update("SMS", 6, 300, 5, "  https://sms.cm  ", "k", "  AFB  ", admin);

        verify(jdbc).update(anyString(), eq("SMS"), eq(6), eq(300), eq(5),
                eq("https://sms.cm"), eq("enc"), eq("AFB"), eq(admin));
    }

    /** update() rafraichit le cache : la valeur lue ensuite est la nouvelle. */
    @Test
    void update_recharge_le_cache() {
        simulerDbIndisponible();
        when(cipher.encrypt("k")).thenReturn("enc");

        service.update("SMS", 6, 300, 5, null, "k", null, UUID.randomUUID());

        // reload() a ete rappele : la DB etant indisponible, on retombe sur les defauts.
        verify(jdbc, atLeastOnce()).queryForObject(anyString(), any(RowMapper.class));
        assertNotNull(service.get());
    }
}
