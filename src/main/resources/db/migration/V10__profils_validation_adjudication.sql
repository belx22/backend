-- ============================================================================
-- V10 — 4 profils, validation d'adjudication, mot de passe a la 1re connexion,
--       parametres OTP configurables par l'administrateur.
-- ============================================================================

-- 1. Changement de mot de passe obligatoire a la premiere connexion.
--    Pose a TRUE pour tout compte cree par l'admin (interne) ou par l'agent
--    (client) : l'utilisateur doit definir son propre mot de passe au 1er login.
ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

-- Telephone du client (sert a la creation de compte et a l'envoi d'OTP par SMS).
ALTER TABLE users ADD COLUMN telephone TEXT;

-- 2. Workflow de validation d'adjudication.
--    L'agent saisit un resultat (PROPOSE) qui reste invisible au client ; le
--    superviseur le valide, l'ordre prend alors son statut final et le client
--    est notifie. Tant que resultat_propose est pose et resultat_valide_at NULL,
--    l'ordre est en attente de validation superviseur.
ALTER TABLE orders ADD COLUMN resultat_propose     TEXT
    CHECK (resultat_propose IS NULL OR resultat_propose IN
           ('TOTALEMENT_RETENU','PARTIELLEMENT_RETENU','NON_RETENU'));
ALTER TABLE orders ADD COLUMN resultat_propose_par UUID REFERENCES users (id);
ALTER TABLE orders ADD COLUMN resultat_propose_at  TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN resultat_valide_par  UUID REFERENCES users (id);
ALTER TABLE orders ADD COLUMN resultat_valide_at   TIMESTAMPTZ;

-- 3. Matrice RBAC — 4 profils metier (Client, Agent, Superviseur, Admin).
--    * AGENT       : operations courantes, SAISIT l'adjudication (ORDER_RESULT).
--    * SUPERVISEUR : publie les emissions, VALIDE l'adjudication (ORDER_RESULT_VALIDATE).
--    * ADMIN       : profils internes + audit + configuration ; AUCUNE adjudication.
--    * CLIENT_*    : aucune permission interne.
DELETE FROM role_permissions;
INSERT INTO role_permissions (role, permission) VALUES
    ('AGENT','EMISSION_CREATE'),
    ('AGENT','ORDER_VALIDATE'),
    ('AGENT','ORDER_RESULT'),
    ('AGENT','CLIENT_CREATE'),
    ('AGENT','CLIENT_MANAGE'),
    ('AGENT','DOCUMENT_UPLOAD'),
    ('AGENT','ACCOUNT_BALANCE_READ'),
    ('SUPERVISEUR','EMISSION_CREATE'),
    ('SUPERVISEUR','EMISSION_VALIDATE'),
    ('SUPERVISEUR','ORDER_VALIDATE'),
    ('SUPERVISEUR','ORDER_RESULT_VALIDATE'),
    ('SUPERVISEUR','CLIENT_CREATE'),
    ('SUPERVISEUR','CLIENT_MANAGE'),
    ('SUPERVISEUR','REPORTING_READ'),
    ('SUPERVISEUR','CONFIG_MARCHE'),
    ('SUPERVISEUR','DOCUMENT_UPLOAD'),
    ('SUPERVISEUR','ACCOUNT_BALANCE_READ'),
    ('ADMIN','EMISSION_VALIDATE'),
    ('ADMIN','EMISSION_DELETE'),
    ('ADMIN','CLIENT_CREATE'),
    ('ADMIN','CLIENT_MANAGE'),
    ('ADMIN','USER_MANAGE'),
    ('ADMIN','AUDIT_READ'),
    ('ADMIN','REPORTING_READ'),
    ('ADMIN','CONFIG_MARCHE'),
    ('ADMIN','ACCOUNT_BALANCE_READ');

-- 4. Parametres OTP configurables par l'administrateur (« serveur OTP »).
--    Le canal SMS est prepare : l'URL et la cle d'API (chiffree AES-GCM) peuvent
--    etre renseignees ; tant qu'aucune passerelle n'est fournie, l'envoi SMS est
--    simule (journalise). Le canal EMAIL s'appuie sur le SMTP existant.
CREATE TABLE otp_settings (
    id              BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    canal           TEXT NOT NULL DEFAULT 'EMAIL'
        CHECK (canal IN ('EMAIL','SMS','EMAIL_SMS','LOG')),
    longueur        INTEGER NOT NULL DEFAULT 6 CHECK (longueur BETWEEN 4 AND 8),
    ttl_secondes    INTEGER NOT NULL DEFAULT 300 CHECK (ttl_secondes BETWEEN 60 AND 1800),
    max_tentatives  INTEGER NOT NULL DEFAULT 5 CHECK (max_tentatives BETWEEN 1 AND 10),
    sms_api_url     TEXT,
    sms_api_key_enc TEXT,
    sms_expediteur  TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID REFERENCES users (id)
);
INSERT INTO otp_settings (id) VALUES (TRUE);
