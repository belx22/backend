-- ============================================================================
-- Parametres LDAP / Active Directory configurables par l'administrateur depuis
-- l'espace d'administration (sans modifier le code ni les variables d'env).
--
-- Table singleton (une seule ligne, id = TRUE). Le mot de passe du compte de
-- service (bind) est stocke CHIFFRE (AES-GCM) — jamais en clair, jamais reexpose
-- par l'API.
-- ============================================================================
CREATE TABLE ldap_settings (
    id                  BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    host                TEXT,
    port                INTEGER NOT NULL DEFAULT 389 CHECK (port > 0 AND port <= 65535),
    ssl                 BOOLEAN NOT NULL DEFAULT FALSE,   -- LDAPS (ldaps://)
    start_tls           BOOLEAN NOT NULL DEFAULT FALSE,   -- STARTTLS sur connexion ldap://
    base_dn             TEXT,                              -- ex : DC=afriland,DC=cm
    bind_dn             TEXT,                              -- compte de service ex : CN=svc-svt,OU=Services,DC=afriland,DC=cm
    bind_password_enc   TEXT,
    user_search_base    TEXT,                              -- ex : OU=Utilisateurs,DC=afriland,DC=cm
    user_search_filter  TEXT NOT NULL DEFAULT '(sAMAccountName={0})',
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID REFERENCES users (id)
);

-- Ligne unique initiale (LDAP desactive par defaut).
INSERT INTO ldap_settings (id) VALUES (TRUE);
