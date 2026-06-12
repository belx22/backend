package cm.afriland.titres.security;

/**
 * Permissions granulaires (CSFT §M6-S03). Le nom de l'enum est aussi le nom
 * canonique persiste en base (table {@code role_permissions}) et utilise par l'API.
 */
public enum Permission {
    EMISSION_CREATE,
    EMISSION_VALIDATE,
    EMISSION_DELETE,
    ORDER_VALIDATE,
    ORDER_RESULT,
    ORDER_RESULT_VALIDATE,
    CLIENT_CREATE,
    CLIENT_MANAGE,
    USER_MANAGE,
    AUDIT_READ,
    REPORTING_READ,
    CONFIG_MARCHE,
    DOCUMENT_UPLOAD,
    ACCOUNT_BALANCE_READ;

    /** Reconstruit une permission depuis son nom canonique, ou {@code null}. */
    public static Permission fromName(String name) {
        if (name == null) return null;
        try {
            return Permission.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
