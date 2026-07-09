package cm.afriland.titres.security;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Controle d'acces base sur les roles (RBAC) — CSFT §1.3 et §M6-S03.
 *
 * La matrice role -> permissions est persistee en base ({@code role_permissions})
 * puis chargee en memoire. Un administrateur peut l'ajuster a chaud via
 * {@code PUT /permissions} ; chaque controle d'acces lit la copie memoire.
 */
@Component
public class Rbac {

    private static final Logger log = LoggerFactory.getLogger(Rbac.class);

    /** Les cinq roles du systeme — 4 acteurs metier (le client investisseur
     *  se decline en PP et PM). CSFT §1.3. */
    public static final List<String> ROLES = List.of(
            "CLIENT_PP", "CLIENT_PM", "AGENT", "SUPERVISEUR", "ADMIN");

    /** Matrice RBAC en memoire — initialisee aux valeurs par defaut. Reference
     *  atomique : chaque rechargement publie une nouvelle map immuable d'un bloc. */
    private static final java.util.concurrent.atomic.AtomicReference<Map<String, Set<Permission>>> matrix =
            new java.util.concurrent.atomic.AtomicReference<>(defaultMatrix());

    private final JdbcTemplate jdbc;

    public Rbac(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Matrice par defaut (principe du moindre privilege). */
    private static Map<String, Set<Permission>> defaultMatrix() {
        Map<String, Set<Permission>> m = new HashMap<>();
        for (String r : ROLES) {
            m.put(r, EnumSet.noneOf(Permission.class));
        }
        // AGENT — operations courantes : il saisit l'adjudication (ORDER_RESULT)
        // mais ne la valide pas. Pas de gestion des utilisateurs internes ni audit.
        m.put("AGENT", EnumSet.of(Permission.EMISSION_CREATE, Permission.ORDER_VALIDATE,
                Permission.ORDER_RESULT, Permission.CLIENT_CREATE, Permission.CLIENT_MANAGE,
                Permission.DOCUMENT_UPLOAD, Permission.ACCOUNT_BALANCE_READ));
        // SUPERVISEUR — validation : publie les emissions et VALIDE l'adjudication
        // saisie par l'agent (ORDER_RESULT_VALIDATE). Pas d'audit (reserve a l'admin).
        m.put("SUPERVISEUR", EnumSet.of(Permission.EMISSION_CREATE, Permission.EMISSION_VALIDATE,
                Permission.ORDER_VALIDATE, Permission.ORDER_RESULT_VALIDATE, Permission.CLIENT_CREATE,
                Permission.CLIENT_MANAGE, Permission.REPORTING_READ, Permission.CONFIG_MARCHE,
                Permission.DOCUMENT_UPLOAD, Permission.ACCOUNT_BALANCE_READ));
        // ADMIN — profils internes, audit et configuration. Ne fait AUCUNE
        // adjudication (ni saisie ni validation d'ordres). Peut publier les emissions.
        m.put("ADMIN", EnumSet.of(Permission.EMISSION_VALIDATE, Permission.EMISSION_DELETE,
                Permission.CLIENT_CREATE, Permission.CLIENT_MANAGE, Permission.USER_MANAGE,
                Permission.AUDIT_READ, Permission.REPORTING_READ, Permission.CONFIG_MARCHE,
                Permission.ACCOUNT_BALANCE_READ));
        return m;
    }

    /** Charge la matrice depuis la base. Conserve les valeurs par defaut si vide. */
    public void loadMatrix() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, permission FROM role_permissions");
        if (rows.isEmpty()) {
            log.warn("Table role_permissions vide — matrice RBAC par defaut conservee");
            return;
        }
        Map<String, Set<Permission>> m = new HashMap<>();
        for (String r : ROLES) {
            m.put(r, EnumSet.noneOf(Permission.class));
        }
        for (Map<String, Object> row : rows) {
            String role = (String) row.get("role");
            Permission p = Permission.fromName((String) row.get("permission"));
            if (p != null && m.containsKey(role)) {
                m.get(role).add(p);
            }
        }
        matrix.set(m);
        log.info("Matrice RBAC chargee depuis la base");
    }

    /** Indique si un role detient une permission donnee (lecture memoire). */
    public static boolean roleHasPermission(String role, Permission perm) {
        Set<Permission> perms = matrix.get().get(role);
        return perms != null && perms.contains(perm);
    }

    /** Capture la matrice courante : role -> liste triee des noms de permissions. */
    public static Map<String, List<String>> matrixSnapshot() {
        Map<String, List<String>> snapshot = new TreeMap<>();
        for (Map.Entry<String, Set<Permission>> e : matrix.get().entrySet()) {
            List<String> names = new ArrayList<>();
            for (Permission p : e.getValue()) {
                names.add(p.name());
            }
            names.sort(String::compareTo);
            snapshot.put(e.getKey(), names);
        }
        return snapshot;
    }

    /** Vrai si le role correspond a un client investisseur (PP ou PM). */
    public static boolean isClient(String role) {
        return "CLIENT_PP".equals(role) || "CLIENT_PM".equals(role);
    }

    /** Vrai si le role correspond a un acteur interne (back-office). */
    public static boolean isStaff(String role) {
        return switch (role == null ? "" : role) {
            case "AGENT", "SUPERVISEUR", "ADMIN" -> true;
            default -> false;
        };
    }
}
