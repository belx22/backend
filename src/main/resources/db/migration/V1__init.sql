-- ============================================================================
-- Schema initial — Plateforme Valeurs du Tresor CEMAC
-- Conforme au CSFT AFB_DFT_PXXX_DGSTP_SPECS_V1.0_2026 — §6 Modele de donnees.
-- Securite : contraintes CHECK strictes, cles etrangeres, unicite, index.
-- ============================================================================

-- Utilisateurs (clients + acteurs internes) — CSFT §1.3
CREATE TABLE users (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                   TEXT NOT NULL UNIQUE,
    password_hash           TEXT NOT NULL,
    role                    TEXT NOT NULL
        CHECK (role IN ('CLIENT_PP','CLIENT_PM','AGENT','SUPERVISEUR','ADMIN','CONFORMITE','DIRECTION')),
    nom                     TEXT NOT NULL,
    prenom                  TEXT,
    statut                  TEXT NOT NULL DEFAULT 'ACTIF'
        CHECK (statut IN ('ACTIF','SUSPENDU')),
    compte_titres           TEXT,
    compte_especes          TEXT,
    solde                   BIGINT CHECK (solde IS NULL OR solde >= 0),
    categorie               TEXT CHECK (categorie IS NULL OR categorie IN ('QUALIFIE','NON_QUALIFIE')),
    type_compte             TEXT,
    failed_login_attempts   INTEGER NOT NULL DEFAULT 0,
    locked_until            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_role ON users (role);

-- Emissions de titres publics — CSFT §6.1
CREATE TABLE emissions (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                     TEXT NOT NULL UNIQUE,
    isin                     TEXT NOT NULL UNIQUE CHECK (char_length(isin) = 12),
    libelle                  TEXT NOT NULL CHECK (char_length(libelle) <= 150),
    nature                   TEXT NOT NULL CHECK (nature IN ('BTA','OTA')),
    pays_code                TEXT NOT NULL CHECK (pays_code IN ('CMR','GAB','CGO','TCD','RCA','GNQ')),
    date_emission            DATE NOT NULL,
    ouverture_souscription   TIMESTAMPTZ NOT NULL,
    fermeture_souscription   TIMESTAMPTZ NOT NULL,
    date_echeance            DATE NOT NULL,
    date_reglement           DATE NOT NULL,
    duree_jours              INTEGER NOT NULL CHECK (duree_jours > 0),
    valeur_nominale_unitaire BIGINT NOT NULL CHECK (valeur_nominale_unitaire > 0),
    montant_global           BIGINT NOT NULL CHECK (montant_global > 0),
    taux_nominal             DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK (taux_nominal >= 0),
    montant_minimum          BIGINT NOT NULL CHECK (montant_minimum > 0),
    frequence_coupon         TEXT CHECK (frequence_coupon IN ('ANNUEL','SEMESTRIEL','TRIMESTRIEL')),
    mode_adjudication        TEXT NOT NULL CHECK (mode_adjudication IN ('PRIX','TAUX')),
    description              TEXT CHECK (description IS NULL OR char_length(description) <= 2000),
    observation              TEXT,
    status                   TEXT NOT NULL DEFAULT 'BROUILLON'
        CHECK (status IN ('BROUILLON','PUBLIE','CLOTURE','ARCHIVE')),
    created_by               UUID REFERENCES users (id),
    validated_by             UUID REFERENCES users (id),
    date_validation          TIMESTAMPTZ,
    rejection_motif          TEXT,
    rejected_by              UUID REFERENCES users (id),
    rejected_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (date_echeance > date_emission),
    CHECK (fermeture_souscription >= ouverture_souscription)
);
CREATE INDEX idx_emissions_status ON emissions (status);
CREATE INDEX idx_emissions_nature ON emissions (nature);

-- Ordres de souscription — CSFT §6.2
CREATE TABLE orders (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference           TEXT NOT NULL UNIQUE,
    emission_id         UUID NOT NULL REFERENCES emissions (id),
    client_id           UUID NOT NULL REFERENCES users (id),
    isin                TEXT NOT NULL,
    volume              INTEGER NOT NULL CHECK (volume > 0),
    montant             BIGINT NOT NULL CHECK (montant > 0),
    taux_soumis         DOUBLE PRECISION NOT NULL CHECK (taux_soumis >= 0),
    status              TEXT NOT NULL DEFAULT 'SOUMIS'
        CHECK (status IN ('SOUMIS','EN_VERIFICATION','EN_ATTENTE_ADJUDICATION',
                          'TOTALEMENT_RETENU','PARTIELLEMENT_RETENU','NON_RETENU','ANNULE')),
    compte_especes      TEXT NOT NULL,
    compte_titres       TEXT NOT NULL,
    canal               TEXT NOT NULL DEFAULT 'EN_LIGNE'
        CHECK (canal IN ('EN_LIGNE','GUICHET','EMAIL')),
    montant_adjuge      BIGINT CHECK (montant_adjuge IS NULL OR montant_adjuge >= 0),
    taux_adjuge         DOUBLE PRECISION,
    volume_alloue       INTEGER CHECK (volume_alloue IS NULL OR volume_alloue >= 0),
    commentaire_resultat TEXT,
    motif_annulation    TEXT,
    notes               TEXT,
    validated_by_agent  UUID REFERENCES users (id),
    date_validation_agent TIMESTAMPTZ,
    ip_soumission       TEXT,
    date_soumission     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_client ON orders (client_id);
CREATE INDEX idx_orders_emission ON orders (emission_id);
CREATE INDEX idx_orders_status ON orders (status);

-- Journal d'audit immuable — CSFT §M6 (append-only)
CREATE TABLE audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    horodatage  TIMESTAMPTZ NOT NULL DEFAULT now(),
    utilisateur TEXT NOT NULL,
    action      TEXT NOT NULL,
    resultat    TEXT NOT NULL CHECK (resultat IN ('SUCCES','ECHEC')),
    reference   TEXT NOT NULL DEFAULT '—',
    ip          TEXT
);
CREATE INDEX idx_audit_horodatage ON audit_log (horodatage DESC);
CREATE INDEX idx_audit_reference ON audit_log (reference);

-- Jetons de rafraichissement (rotation + revocation)
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  TEXT NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_user ON refresh_tokens (user_id);

-- Defis MFA (OTP) — CSFT §M6-S01
CREATE TABLE mfa_challenges (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash   TEXT NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    attempts    INTEGER NOT NULL DEFAULT 0,
    consumed    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mfa_user ON mfa_challenges (user_id);
