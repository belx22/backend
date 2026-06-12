-- ============================================================================
-- Parametres de messagerie (SMTP) configurables par l'administrateur depuis
-- l'espace d'administration — sans modifier le code ni les variables d'env.
--
-- Table singleton (une seule ligne, id = TRUE). Le mot de passe est stocke
-- CHIFFRE (AES-GCM, cle derivee du secret applicatif) — jamais en clair, et
-- jamais reexpose par l'API.
-- ============================================================================
CREATE TABLE mail_settings (
    id            BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    host          TEXT,
    port          INTEGER NOT NULL DEFAULT 587 CHECK (port > 0 AND port <= 65535),
    username      TEXT,
    password_enc  TEXT,
    from_address  TEXT NOT NULL DEFAULT 'no-reply@afriland.cm',
    from_name     TEXT NOT NULL DEFAULT 'Afriland First Bank - DFT',
    auth          BOOLEAN NOT NULL DEFAULT TRUE,
    starttls      BOOLEAN NOT NULL DEFAULT TRUE,
    enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    UUID REFERENCES users (id)
);

-- Ligne unique initiale (valeurs par defaut, messagerie desactivee).
INSERT INTO mail_settings (id) VALUES (TRUE);
