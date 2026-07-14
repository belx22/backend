package cm.afriland.titres.integration;

import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import java.io.IOException;

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.StartTlsRequest;
import javax.naming.ldap.StartTlsResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import cm.afriland.titres.security.SecretCipher;
import jakarta.annotation.PostConstruct;

/**
 * Parametres LDAP / Active Directory persistes en base et configurables par
 * l'administrateur. Le mot de passe du compte de service (bind) est chiffre au
 * repos (AES-GCM) et n'est jamais expose par l'API.
 *
 * <p>La methode {@link #testConnection()} tente une liaison (bind) reelle vers
 * l'annuaire avec les parametres enregistres — utile pour valider la
 * configuration depuis l'interface d'administration.</p>
 */
@Service
public class LdapSettingsService {

    private static final Logger log = LoggerFactory.getLogger(LdapSettingsService.class);

    private static final String DEFAULT_USER_FILTER = "(sAMAccountName={0})";
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final java.util.concurrent.atomic.AtomicReference<LdapSettings> cache =
            new java.util.concurrent.atomic.AtomicReference<>();

    public LdapSettingsService(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Vue interne ; {@code bindPassword} est en clair (jamais serialise vers l'API). */
    public record LdapSettings(
            boolean enabled, String host, int port, boolean ssl, boolean startTls,
            String baseDn, String bindDn, String bindPassword,
            String userSearchBase, String userSearchFilter) {
    }

    public record TestResult(boolean ok, String message) {
    }

    @PostConstruct
    public void reload() {
        try {
            cache.set(jdbc.queryForObject(
                    "SELECT enabled, host, port, ssl, start_tls, base_dn, bind_dn, bind_password_enc, "
                            + "user_search_base, user_search_filter FROM ldap_settings WHERE id = TRUE",
                    (rs, n) -> new LdapSettings(
                            rs.getBoolean("enabled"),
                            rs.getString("host"),
                            rs.getInt("port"),
                            rs.getBoolean("ssl"),
                            rs.getBoolean("start_tls"),
                            rs.getString("base_dn"),
                            rs.getString("bind_dn"),
                            cipher.decrypt(rs.getString("bind_password_enc")),
                            rs.getString("user_search_base"),
                            rs.getString("user_search_filter"))));
        } catch (RuntimeException e) {
            log.warn("Parametres LDAP indisponibles : {}", e.getMessage());
            cache.set(new LdapSettings(false, null, 389, false, false,
                    null, null, null, null, DEFAULT_USER_FILTER));
        }
    }

    public LdapSettings get() {
        if (cache.get() == null) reload();
        return cache.get();
    }

    /** Met a jour les parametres. {@code newPassword} null = inchange ; "" = efface. */
    public void update(boolean enabled, String host, int port, boolean ssl, boolean startTls,
                       String baseDn, String bindDn, String newPassword,
                       String userSearchBase, String userSearchFilter, UUID adminId) {
        String passwordEnc;
        if (newPassword == null) {
            passwordEnc = jdbc.query("SELECT bind_password_enc FROM ldap_settings WHERE id = TRUE",
                    rs -> rs.next() ? rs.getString("bind_password_enc") : null);
        } else {
            passwordEnc = cipher.encrypt(newPassword);
        }
        jdbc.update(
                "UPDATE ldap_settings SET enabled = ?, host = ?, port = ?, ssl = ?, start_tls = ?, "
                        + "base_dn = ?, bind_dn = ?, bind_password_enc = ?, user_search_base = ?, "
                        + "user_search_filter = ?, updated_at = now(), updated_by = ? WHERE id = TRUE",
                enabled, trimToNull(host), port, ssl, startTls,
                trimToNull(baseDn), trimToNull(bindDn), passwordEnc,
                trimToNull(userSearchBase),
                userSearchFilter == null || userSearchFilter.isBlank()
                        ? DEFAULT_USER_FILTER : userSearchFilter.trim(),
                adminId);
        reload();
    }

    /** Expose les parametres pour l'API (sans mot de passe). */
    public Map<String, Object> publicView() {
        LdapSettings s = get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled());
        m.put("host", s.host() == null ? "" : s.host());
        m.put("port", s.port());
        m.put("ssl", s.ssl());
        m.put("startTls", s.startTls());
        m.put("baseDn", s.baseDn() == null ? "" : s.baseDn());
        m.put("bindDn", s.bindDn() == null ? "" : s.bindDn());
        m.put("userSearchBase", s.userSearchBase() == null ? "" : s.userSearchBase());
        m.put("userSearchFilter", s.userSearchFilter() == null ? "" : s.userSearchFilter());
        m.put("bindPasswordSet", s.bindPassword() != null && !s.bindPassword().isBlank());
        return m;
    }

    /** Tente une liaison (bind) vers l'annuaire avec les parametres enregistres. */
    public TestResult testConnection() {
        LdapSettings s = get();
        if (s.host() == null || s.host().isBlank()) {
            return new TestResult(false, "Hote LDAP non renseigne.");
        }
        String principal = (s.bindDn() != null && !s.bindDn().isBlank()) ? s.bindDn() : null;
        String url = (s.ssl() ? "ldaps://" : "ldap://") + s.host() + ":" + s.port();
        try {
            openContext(s, principal, s.bindPassword()).close();
            return new TestResult(true, "Connexion LDAP reussie (" + url + ").");
        } catch (Exception e) {
            log.info("Echec du test LDAP vers {} : {}", url, e.getMessage());
            return new TestResult(false, "Echec de connexion : " + e.getMessage());
        }
    }

    /**
     * Verifie un couple identifiant/mot de passe via l'annuaire (recherche du DN
     * puis bind aux identifiants de l'utilisateur).
     *
     * @return {@code true} si authentifie, {@code false} si le mot de passe est
     *         refuse, {@code null} si l'utilisateur est introuvable dans l'annuaire
     *         ou si LDAP est indisponible/desactive (le mode hybride peut alors
     *         retomber sur l'authentification locale).
     */
    public Boolean authenticate(String identifier, String password) {
        LdapSettings s = get();
        if (!s.enabled() || s.host() == null || s.host().isBlank()) return null;
        if (identifier == null || identifier.isBlank() || password == null || password.isEmpty()) return false;
        String searchBase = (s.userSearchBase() != null && !s.userSearchBase().isBlank())
                ? s.userSearchBase() : s.baseDn();
        if (searchBase == null || searchBase.isBlank()) return null;
        String principal = (s.bindDn() != null && !s.bindDn().isBlank()) ? s.bindDn() : null;

        LdapContext ctx = null;
        try {
            ctx = openContext(s, principal, s.bindPassword());   // bind compte de service (ou anonyme)
            SearchControls sc = new SearchControls();
            sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
            sc.setCountLimit(1);
            sc.setReturningAttributes(new String[0]);
            String filter = (s.userSearchFilter() == null || s.userSearchFilter().isBlank())
                    ? DEFAULT_USER_FILTER : s.userSearchFilter();
            NamingEnumeration<SearchResult> res =
                    ctx.search(searchBase, filter, new Object[]{ identifier }, sc);
            String userDn = res.hasMore() ? res.next().getNameInNamespace() : null;
            res.close();
            if (userDn == null) return null;                     // introuvable dans l'annuaire
            try {
                openContext(s, userDn, password).close();        // bind aux identifiants de l'utilisateur
                return true;
            } catch (AuthenticationException ae) {
                return false;                                    // mot de passe refuse
            }
        } catch (Exception e) {
            log.info("Authentification LDAP impossible pour {} : {}", identifier, e.getMessage());
            return null;                                         // annuaire indisponible
        } finally {
            if (ctx != null) try { ctx.close(); } catch (NamingException ignored) { /* */ }
        }
    }

    /** Ouvre un contexte LDAP, avec liaison simple (bind) si principal fourni. */
    private LdapContext openContext(LdapSettings s, String principal, String credentials)
            throws NamingException, IOException {
        String url = (s.ssl() ? "ldaps://" : "ldap://") + s.host() + ":" + s.port();
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put("com.sun.jndi.ldap.connect.timeout", "5000");
        env.put("com.sun.jndi.ldap.read.timeout", "5000");
        if (s.startTls() && !s.ssl()) {
            // Connexion puis negociation STARTTLS, puis bind authentifie.
            LdapContext ctx = new InitialLdapContext(env, null);
            StartTlsResponse tls = (StartTlsResponse) ctx.extendedOperation(new StartTlsRequest());
            tls.negotiate();
            if (principal != null) {
                ctx.addToEnvironment(Context.SECURITY_AUTHENTICATION, "simple");
                ctx.addToEnvironment(Context.SECURITY_PRINCIPAL, principal);
                ctx.addToEnvironment(Context.SECURITY_CREDENTIALS, credentials == null ? "" : credentials);
                ctx.reconnect(null);
            }
            return ctx;   // la fermeture du contexte ferme la connexion (et TLS)
        }
        if (principal != null) {
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, principal);
            env.put(Context.SECURITY_CREDENTIALS, credentials == null ? "" : credentials);
        }
        return new InitialLdapContext(env, null);
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
