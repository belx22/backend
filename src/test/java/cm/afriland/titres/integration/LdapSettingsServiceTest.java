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
}
