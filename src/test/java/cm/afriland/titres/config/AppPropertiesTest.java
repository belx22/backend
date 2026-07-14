package cm.afriland.titres.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppPropertiesTest {

    private static AppProperties valid() {
        AppProperties p = new AppProperties();
        p.setJwtSecret("secret-valide-au-moins-32-caracteres-ok!");
        return p;
    }

    // ─── validate ─────────────────────────────────────────────────────────────

    @Test
    void validate_passe_avec_32_chars_exactement() {
        AppProperties p = new AppProperties();
        p.setJwtSecret("12345678901234567890123456789012"); // 32 chars
        assertDoesNotThrow(p::validate);
    }

    @Test
    void validate_passe_avec_secret_plus_long() {
        assertDoesNotThrow(valid()::validate);
    }

    @Test
    void validate_echoue_secret_31_chars() {
        AppProperties p = new AppProperties();
        p.setJwtSecret("1234567890123456789012345678901"); // 31 chars
        assertThrows(IllegalStateException.class, p::validate);
    }

    @Test
    void validate_echoue_secret_vide() {
        AppProperties p = new AppProperties();
        p.setJwtSecret("");
        assertThrows(IllegalStateException.class, p::validate);
    }

    @Test
    void validate_echoue_secret_null() {
        assertThrows(IllegalStateException.class, new AppProperties()::validate);
    }

    // ─── getPrimaryFrontendOrigin ─────────────────────────────────────────────

    @Test
    void primaryOrigin_origine_unique() {
        AppProperties p = valid();
        p.setFrontendOrigin("http://localhost:4200");
        assertEquals("http://localhost:4200", p.getPrimaryFrontendOrigin());
    }

    @Test
    void primaryOrigin_premiere_sur_multi() {
        AppProperties p = valid();
        p.setFrontendOrigin("https://app.afriland.cm, http://localhost:4200");
        assertEquals("https://app.afriland.cm", p.getPrimaryFrontendOrigin());
    }

    @Test
    void primaryOrigin_retire_slash_final() {
        AppProperties p = valid();
        p.setFrontendOrigin("https://app.afriland.cm/");
        assertEquals("https://app.afriland.cm", p.getPrimaryFrontendOrigin());
    }

    @Test
    void primaryOrigin_null_retourne_localhost() {
        AppProperties p = valid();
        p.setFrontendOrigin(null);
        assertEquals("http://localhost:4200", p.getPrimaryFrontendOrigin());
    }

    @Test
    void primaryOrigin_vide_retourne_localhost() {
        AppProperties p = valid();
        p.setFrontendOrigin("");
        assertEquals("http://localhost:4200", p.getPrimaryFrontendOrigin());
    }

    @Test
    void primaryOrigin_espace_retourne_localhost() {
        AppProperties p = valid();
        p.setFrontendOrigin("   ");
        assertEquals("http://localhost:4200", p.getPrimaryFrontendOrigin());
    }

    // ─── getAuthMode ──────────────────────────────────────────────────────────

    @Test
    void authMode_local_par_defaut() {
        assertEquals("LOCAL", valid().getAuthMode());
    }

    @Test
    void authMode_ldap_reconnu() {
        AppProperties p = valid();
        p.setAuthMode("LDAP");
        assertEquals("LDAP", p.getAuthMode());
    }

    @Test
    void authMode_ldap_then_local_reconnu() {
        AppProperties p = valid();
        p.setAuthMode("LDAP_THEN_LOCAL");
        assertEquals("LDAP_THEN_LOCAL", p.getAuthMode());
    }

    @Test
    void authMode_minuscule_normalise_en_majuscule() {
        AppProperties p = valid();
        p.setAuthMode("local");
        assertEquals("LOCAL", p.getAuthMode());
    }

    @Test
    void authMode_inconnu_retourne_local() {
        AppProperties p = valid();
        p.setAuthMode("OAUTH2");
        assertEquals("LOCAL", p.getAuthMode());
    }

    @Test
    void authMode_null_retourne_local() {
        AppProperties p = valid();
        p.setAuthMode(null);
        assertEquals("LOCAL", p.getAuthMode());
    }

    // ─── valeurs par défaut ───────────────────────────────────────────────────

    @Test
    void accessTokenTtl_defaut_5_minutes() {
        assertEquals(300L, new AppProperties().getAccessTokenTtl());
    }

    @Test
    void sessionIdleTimeout_defaut_15_minutes() {
        assertEquals(900L, new AppProperties().getSessionIdleTimeout());
    }

    /**
     * Invariant de securite : le jeton d'acces doit expirer BIEN AVANT la fenetre
     * d'inactivite. C'est son renouvellement qui prouve l'activite ; avec deux
     * durees egales, un utilisateur actif serait deconnecte a tort au moment meme
     * ou il renouvelle.
     */
    @Test
    void le_jeton_d_acces_expire_bien_avant_la_fenetre_d_inactivite() {
        AppProperties p = new AppProperties();
        assertTrue(p.getAccessTokenTtl() * 2 <= p.getSessionIdleTimeout(),
                "le jeton d'acces doit durer au plus la moitie de la fenetre d'inactivite");
    }

    @Test
    void refreshTokenTtl_defaut_30_jours() {
        assertEquals(2_592_000L, new AppProperties().getRefreshTokenTtl());
    }

    @Test
    void hasSmtpEnvConfig_faux_par_defaut() {
        assertFalse(new AppProperties().hasSmtpEnvConfig());
    }

    @Test
    void hasSmtpEnvConfig_vrai_quand_host_renseigne() {
        AppProperties p = new AppProperties();
        p.setSmtpHost("smtp.afriland.cm");
        assertTrue(p.hasSmtpEnvConfig());
    }

    @Test
    void hasSmtpEnvConfig_faux_quand_host_vide() {
        AppProperties p = new AppProperties();
        p.setSmtpHost("   ");
        assertFalse(p.hasSmtpEnvConfig());
    }
}
