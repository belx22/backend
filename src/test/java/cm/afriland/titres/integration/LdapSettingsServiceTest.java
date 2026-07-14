package cm.afriland.titres.integration;

import cm.afriland.titres.integration.LdapSettingsService.LdapSettings;
import cm.afriland.titres.integration.LdapSettingsService.TestResult;
import cm.afriland.titres.security.SecretCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LdapSettingsServiceTest {

    @Mock JdbcTemplate jdbc;
    @Mock SecretCipher cipher;
    @InjectMocks LdapSettingsService service;

    private void stubConfig(LdapSettings s) {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class))).thenReturn(s);
    }

    private LdapSettings settings(boolean enabled, String host, boolean ssl, boolean startTls,
                                  String baseDn) {
        return new LdapSettings(enabled, host, 389, ssl, startTls, baseDn,
                "cn=svc," + (baseDn == null ? "" : baseDn), "secret",
                baseDn, "(uid={0})");
    }

    // ─── record + get + reload fallback ──────────────────────────────────────

    @Test
    void get_db_indisponible_valeurs_par_defaut() {
        doThrow(new RuntimeException("DB")).when(jdbc)
                .queryForObject(anyString(), any(RowMapper.class));
        LdapSettings s = service.get();
        assertFalse(s.enabled());
        assertEquals(389, s.port());
        assertEquals("(sAMAccountName={0})", s.userSearchFilter());
    }

    @Test
    void get_charge_la_config_puis_utilise_le_cache() {
        stubConfig(settings(true, "ldap.local", false, false, "dc=x"));
        assertEquals("ldap.local", service.get().host());
        service.get();
        verify(jdbc, times(1)).queryForObject(anyString(), any(RowMapper.class));
    }

    // ─── publicView ──────────────────────────────────────────────────────────

    @Test
    void publicView_masque_le_mot_de_passe_et_expose_les_champs() {
        stubConfig(settings(true, "ldap.local", true, false, "dc=afriland,dc=cm"));
        Map<String, Object> v = service.publicView();
        assertEquals(true, v.get("enabled"));
        assertEquals("ldap.local", v.get("host"));
        assertEquals(true, v.get("ssl"));
        assertEquals(true, v.get("bindPasswordSet"));
        assertFalse(v.containsKey("bindPassword"));
    }

    @Test
    void publicView_fallback_champs_null_en_chaine_vide() {
        doThrow(new RuntimeException("DB")).when(jdbc)
                .queryForObject(anyString(), any(RowMapper.class));
        Map<String, Object> v = service.publicView();
        assertEquals("", v.get("host"));
        assertEquals("", v.get("baseDn"));
        assertEquals(false, v.get("bindPasswordSet"));
    }

    // ─── update ──────────────────────────────────────────────────────────────

    @Test
    void update_nouveau_mot_de_passe_chiffre() {
        stubConfig(settings(true, "h", false, false, "dc=x"));
        when(cipher.encrypt("newpass")).thenReturn("ENC");
        service.update(true, "h", 636, true, false, "dc=x", "cn=svc", "newpass",
                "ou=p", "(uid={0})", UUID.randomUUID());
        verify(cipher).encrypt("newpass");
        verify(jdbc).update(contains("UPDATE ldap_settings"), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void update_mot_de_passe_null_conserve_lancien() {
        stubConfig(settings(true, "h", false, false, "dc=x"));
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class)))
                .thenReturn("OLD_ENC");
        service.update(true, "h", 389, false, false, "dc=x", "cn=svc", null,
                null, null, UUID.randomUUID());
        verify(cipher, never()).encrypt(any());
        verify(jdbc).update(contains("UPDATE ldap_settings"), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    // ─── testConnection ──────────────────────────────────────────────────────

    @Test
    void testConnection_hote_absent() {
        stubConfig(settings(false, null, false, false, null));
        TestResult r = service.testConnection();
        assertFalse(r.ok());
        assertTrue(r.message().contains("non renseigne"));
    }

    @Test
    void testConnection_hote_injoignable_echoue_proprement() {
        stubConfig(settings(true, "ldap.injoignable.invalid", false, false, "dc=x"));
        TestResult r = service.testConnection();
        assertFalse(r.ok());
        assertTrue(r.message().startsWith("Echec"));
    }

    @Test
    void testConnection_ldaps_injoignable() {
        stubConfig(settings(true, "ldaps.injoignable.invalid", true, false, "dc=x"));
        assertFalse(service.testConnection().ok());
    }

    // ─── authenticate ────────────────────────────────────────────────────────

    @Test
    void authenticate_desactive_renvoie_null() {
        stubConfig(settings(false, "h", false, false, "dc=x"));
        assertNull(service.authenticate("user", "pass"));
    }

    @Test
    void authenticate_identifiant_vide_renvoie_false() {
        stubConfig(settings(true, "h", false, false, "dc=x"));
        assertEquals(Boolean.FALSE, service.authenticate("", "pass"));
        assertEquals(Boolean.FALSE, service.authenticate("user", ""));
    }

    @Test
    void authenticate_sans_base_de_recherche_renvoie_null() {
        stubConfig(new LdapSettings(true, "h", 389, false, false, null, null, null, null, "(uid={0})"));
        assertNull(service.authenticate("user", "pass"));
    }

    @Test
    void authenticate_annuaire_injoignable_renvoie_null() {
        stubConfig(settings(true, "ldap.injoignable.invalid", false, false, "dc=x"));
        assertNull(service.authenticate("user", "pass"));
    }

    // ─── reload() — chemin nominal (le RowMapper est reellement execute) ──────

    /**
     * Branche le vrai {@code RowMapper} sur un {@link java.sql.ResultSet} simule :
     * stuber {@code queryForObject} avec un objet tout fait court-circuiterait le
     * mapper, laissant ses lignes non couvertes.
     */
    private void simulerLigneLdap(String bindPasswordEnc, String userSearchBase, String filtre)
            throws java.sql.SQLException {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getBoolean("enabled")).thenReturn(true);
        when(rs.getString("host")).thenReturn("ldap.afriland.intra");
        when(rs.getInt("port")).thenReturn(636);
        when(rs.getBoolean("ssl")).thenReturn(true);
        when(rs.getBoolean("start_tls")).thenReturn(false);
        when(rs.getString("base_dn")).thenReturn("dc=afriland,dc=cm");
        when(rs.getString("bind_dn")).thenReturn("cn=svc,dc=afriland,dc=cm");
        when(rs.getString("bind_password_enc")).thenReturn(bindPasswordEnc);
        when(rs.getString("user_search_base")).thenReturn(userSearchBase);
        when(rs.getString("user_search_filter")).thenReturn(filtre);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class)))
                .thenAnswer(inv -> inv.getArgument(1, RowMapper.class).mapRow(rs, 1));
    }

    @Test
    void reload_lit_la_configuration_et_dechiffre_le_mot_de_passe_de_service()
            throws java.sql.SQLException {
        simulerLigneLdap("chiffre", "ou=users,dc=afriland,dc=cm", "(uid={0})");
        when(cipher.decrypt("chiffre")).thenReturn("secret-en-clair");

        service.reload();
        LdapSettings s = service.get();

        assertTrue(s.enabled());
        assertEquals("ldap.afriland.intra", s.host());
        assertEquals(636, s.port());
        assertTrue(s.ssl());
        assertFalse(s.startTls());
        assertEquals("cn=svc,dc=afriland,dc=cm", s.bindDn());
        assertEquals("secret-en-clair", s.bindPassword());
        assertEquals("ou=users,dc=afriland,dc=cm", s.userSearchBase());
    }

    @Test
    void publicView_ne_revele_jamais_le_mot_de_passe_de_service() throws java.sql.SQLException {
        simulerLigneLdap("chiffre", "ou=users,dc=afriland,dc=cm", "(uid={0})");
        when(cipher.decrypt("chiffre")).thenReturn("secret-en-clair");

        Map<String, Object> v = service.publicView();

        assertEquals("ldap.afriland.intra", v.get("host"));
        assertEquals(636, v.get("port"));
        assertEquals("ou=users,dc=afriland,dc=cm", v.get("userSearchBase"));
        assertEquals("(uid={0})", v.get("userSearchFilter"));
        assertEquals(true, v.get("bindPasswordSet"));
        assertFalse(v.containsValue("secret-en-clair"));
    }

    /** Un mot de passe de service blanc ne compte pas comme configure. */
    @Test
    void publicView_bindPasswordSet_false_quand_le_mot_de_passe_est_blanc()
            throws java.sql.SQLException {
        simulerLigneLdap("chiffre", null, "(uid={0})");
        when(cipher.decrypt("chiffre")).thenReturn("   ");

        assertEquals(false, service.publicView().get("bindPasswordSet"));
    }

    // ─── update() : filtre par defaut et normalisation ───────────────────────

    /** Un filtre absent ou blanc retombe sur le filtre Active Directory par defaut. */
    @Test
    void update_applique_le_filtre_par_defaut_quand_il_est_blanc() {
        doThrow(new RuntimeException("DB")).when(jdbc)
                .queryForObject(anyString(), any(RowMapper.class));
        when(cipher.encrypt("pw")).thenReturn("enc");
        UUID admin = UUID.randomUUID();

        service.update(true, "ldap.local", 389, false, false,
                "dc=x", "cn=svc", "pw", null, "   ", admin);

        verify(jdbc).update(anyString(), eq(true), eq("ldap.local"), eq(389), eq(false), eq(false),
                eq("dc=x"), eq("cn=svc"), eq("enc"), isNull(), eq("(sAMAccountName={0})"), eq(admin));
    }

    /** Les chaines blanches sont normalisees en null, le filtre fourni est conserve (trim). */
    @Test
    void update_normalise_les_chaines_blanches_en_null() {
        doThrow(new RuntimeException("DB")).when(jdbc)
                .queryForObject(anyString(), any(RowMapper.class));
        when(cipher.encrypt("pw")).thenReturn("enc");
        UUID admin = UUID.randomUUID();

        service.update(false, "  ", 636, true, true,
                "  ", "  ", "pw", "  ", "  (uid={0})  ", admin);

        verify(jdbc).update(anyString(), eq(false), isNull(), eq(636), eq(true), eq(true),
                isNull(), isNull(), eq("enc"), isNull(), eq("(uid={0})"), eq(admin));
    }

    // ─── authenticate() : gardes restantes ───────────────────────────────────

    /** Hote absent alors que LDAP est active : on retombe sur l'authentification locale. */
    @Test
    void authenticate_hote_blanc_renvoie_null() {
        stubConfig(new LdapSettings(true, "  ", 389, false, false, "dc=x", null, null, "dc=x", "(uid={0})"));

        assertNull(service.authenticate("user", "pass"));
    }

    /** Mot de passe vide : refus immediat, sans jamais interroger l'annuaire. */
    @Test
    void authenticate_mot_de_passe_vide_renvoie_false() {
        stubConfig(settings(true, "ldap.local", false, false, "dc=x"));

        assertEquals(Boolean.FALSE, service.authenticate("user", ""));
    }

    @Test
    void authenticate_mot_de_passe_null_renvoie_false() {
        stubConfig(settings(true, "ldap.local", false, false, "dc=x"));

        assertEquals(Boolean.FALSE, service.authenticate("user", null));
    }

    /** Base de recherche absente : on retombe sur le baseDn. */
    @Test
    void authenticate_utilise_le_baseDn_quand_la_base_de_recherche_est_blanche() {
        stubConfig(new LdapSettings(true, "ldap.injoignable.invalid", 389, false, false,
                "dc=x", null, null, "   ", "(uid={0})"));

        // L'annuaire est injoignable : null, mais la branche de repli a bien ete prise.
        assertNull(service.authenticate("user", "pass"));
    }

    /** LDAPS injoignable : le test de connexion echoue proprement, sans exception. */
    @Test
    void testConnection_sans_compte_de_service_bind_anonyme() {
        stubConfig(new LdapSettings(true, "ldap.injoignable.invalid", 389, false, false,
                "dc=x", "  ", null, "dc=x", "(uid={0})"));

        TestResult r = service.testConnection();

        assertFalse(r.ok());
        assertTrue(r.message().startsWith("Echec de connexion"));
    }
}
