-- ============================================================================
-- Dossiers clients investisseurs — CSFT §3.2 (onboarding PP / PM).
-- ============================================================================

-- Profil investisseur (1-1 avec users, CLIENT_PP / CLIENT_PM)
CREATE TABLE client_profiles (
    user_id        UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    type_personne  TEXT NOT NULL CHECK (type_personne IN ('PP','PM')),
    raison_sociale TEXT NOT NULL,
    rccm           TEXT,
    compte_statut  TEXT NOT NULL DEFAULT 'ACTIF'
        CHECK (compte_statut IN ('ACTIF','BLOQUE','SUSPENDU','CLOTURE')),
    date_ouverture DATE NOT NULL DEFAULT current_date,
    created_by     UUID REFERENCES users (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Adresses du client (fiscale, postale, domicile, siege)
CREATE TABLE client_adresses (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        TEXT NOT NULL CHECK (type IN ('FISCALE','POSTALE','DOMICILE','SIEGE')),
    residence   TEXT,
    rue         TEXT NOT NULL,
    code_postal TEXT,
    ville       TEXT NOT NULL,
    pays        TEXT NOT NULL,
    ordre       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_client_adresses_user ON client_adresses (user_id);

-- Contacts / signataires rattaches au compte
CREATE TABLE client_contacts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type                TEXT NOT NULL
        CHECK (type IN ('TITULAIRE','MANDATAIRE','REPRESENTANT_LEGAL','CONTACT_ORDRE','URGENCE')),
    nom                 TEXT NOT NULL,
    prenom              TEXT,
    civilite            TEXT,
    fonction            TEXT,
    telephone_portable  TEXT,
    telephone_domicile  TEXT,
    telephone_bureau    TEXT,
    email               TEXT,
    whatsapp            TEXT,
    piece_identite      TEXT,
    numero_piece        TEXT,
    date_validite_piece TEXT,
    lien_parente        TEXT,
    ordre               INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_client_contacts_user ON client_contacts (user_id);

-- Sous-comptes titres (conservation, nantissement, gage...)
CREATE TABLE sous_comptes_titres (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    numero          TEXT NOT NULL,
    libelle         TEXT NOT NULL,
    type            TEXT NOT NULL
        CHECK (type IN ('CONSERVATION','NANTISSEMENT','GAGE','SEQUESTRE','TRANSIT')),
    statut          TEXT NOT NULL DEFAULT 'ACTIF'
        CHECK (statut IN ('ACTIF','BLOQUE','CLOTURE')),
    date_ouverture  DATE NOT NULL DEFAULT current_date,
    positions_count INTEGER NOT NULL DEFAULT 0,
    valeur_totale   BIGINT NOT NULL DEFAULT 0 CHECK (valeur_totale >= 0),
    observations    TEXT,
    ordre           INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_sous_comptes_user ON sous_comptes_titres (user_id);
