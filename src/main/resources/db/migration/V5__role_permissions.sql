-- ============================================================================
-- Matrice RBAC role -> permissions — CSFT §M6-S03.
-- ============================================================================
CREATE TABLE role_permissions (
    role       TEXT NOT NULL
        CHECK (role IN ('CLIENT_PP','CLIENT_PM','AGENT','SUPERVISEUR','ADMIN','CONFORMITE','DIRECTION')),
    permission TEXT NOT NULL,
    PRIMARY KEY (role, permission)
);

-- Matrice par defaut (principe du moindre privilege, CSFT §1.3).
INSERT INTO role_permissions (role, permission) VALUES
    ('AGENT','EMISSION_CREATE'),
    ('AGENT','ORDER_VALIDATE'),
    ('AGENT','ORDER_RESULT'),
    ('AGENT','CLIENT_CREATE'),
    ('AGENT','CLIENT_MANAGE'),
    ('AGENT','USER_MANAGE'),
    ('AGENT','DOCUMENT_UPLOAD'),
    ('SUPERVISEUR','EMISSION_CREATE'),
    ('SUPERVISEUR','EMISSION_VALIDATE'),
    ('SUPERVISEUR','ORDER_VALIDATE'),
    ('SUPERVISEUR','ORDER_RESULT'),
    ('SUPERVISEUR','CLIENT_CREATE'),
    ('SUPERVISEUR','CLIENT_MANAGE'),
    ('SUPERVISEUR','USER_MANAGE'),
    ('SUPERVISEUR','AUDIT_READ'),
    ('SUPERVISEUR','REPORTING_READ'),
    ('SUPERVISEUR','CONFIG_MARCHE'),
    ('SUPERVISEUR','DOCUMENT_UPLOAD'),
    ('ADMIN','EMISSION_CREATE'),
    ('ADMIN','EMISSION_VALIDATE'),
    ('ADMIN','EMISSION_DELETE'),
    ('ADMIN','ORDER_VALIDATE'),
    ('ADMIN','ORDER_RESULT'),
    ('ADMIN','CLIENT_CREATE'),
    ('ADMIN','CLIENT_MANAGE'),
    ('ADMIN','USER_MANAGE'),
    ('ADMIN','AUDIT_READ'),
    ('ADMIN','REPORTING_READ'),
    ('ADMIN','CONFIG_MARCHE'),
    ('ADMIN','DOCUMENT_UPLOAD'),
    ('CONFORMITE','REPORTING_READ'),
    ('DIRECTION','REPORTING_READ');
