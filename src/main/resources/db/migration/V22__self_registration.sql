-- ============================================================================
--  Auto-inscription client — ouverture de compte-titres en ligne (Annexe 1).
--
--  Le DOSSIER d'inscription est une entite de workflow distincte du client
--  definitif : tant qu'il n'est pas VALIDE par le back-office, rien n'entre
--  dans client_profiles/_adresses/_contacts. A la validation, le dossier
--  alimente ces tables existantes (reutilisation de la logique ClientController).
-- ============================================================================

-- ── Dossier d'inscription ───────────────────────────────────────────────────
CREATE TABLE registration_dossiers (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type_personne      TEXT NOT NULL CHECK (type_personne IN ('PP', 'PM')),
    compte_especes     TEXT NOT NULL,                       -- 18 caracteres (Annexe 1)
    type_compte        TEXT NOT NULL DEFAULT 'INDIVIDUEL'
        CHECK (type_compte IN ('INDIVIDUEL', 'JOINT', 'INDIVISION', 'DEMEMBRE')),
    statut             TEXT NOT NULL DEFAULT 'BROUILLON'
        CHECK (statut IN ('BROUILLON', 'EN_ATTENTE_VERIFICATION',
                          'EN_VERIFICATION', 'VALIDE', 'REJETE')),
    langue             TEXT NOT NULL DEFAULT 'FR' CHECK (langue IN ('FR', 'EN')),
    convention_version TEXT,                                -- version acceptee (renseignee a l'acceptation)
    motif_rejet        TEXT,
    submitted_at       TIMESTAMPTZ,
    validated_by       UUID REFERENCES users (id),
    validated_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_registration_dossiers_user   ON registration_dossiers (user_id);
CREATE INDEX idx_registration_dossiers_statut ON registration_dossiers (statut);

-- ── Titulaires (principal + co-titulaires : joint / indivision / demembre) ───
CREATE TABLE dossier_titulaires (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id          UUID NOT NULL REFERENCES registration_dossiers (id) ON DELETE CASCADE,
    role_titulaire      TEXT NOT NULL DEFAULT 'PRINCIPAL'
        CHECK (role_titulaire IN ('PRINCIPAL', 'COTITULAIRE_A', 'COTITULAIRE_B',
                                  'COTITULAIRE_C', 'COTITULAIRE_D',
                                  'USUFRUIT', 'NUE_PROPRIETE',
                                  'REPRESENTANT_A', 'REPRESENTANT_B')),
    civilite            TEXT,
    nom                 TEXT NOT NULL,
    nom_jeune_fille     TEXT,
    prenom              TEXT,
    date_naissance      DATE,
    lieu_naissance      TEXT,
    pays_naissance      TEXT,
    nationalite         TEXT,
    numero_contribuable TEXT,
    qualite             TEXT,                               -- « Qualite » (demembre / representant)
    -- Personne morale (si type_personne = PM et role = PRINCIPAL)
    raison_sociale      TEXT,
    rccm                TEXT,
    -- Restriction (mineur / majeur protege)
    restriction_type    TEXT NOT NULL DEFAULT 'AUCUNE'
        CHECK (restriction_type IN ('AUCUNE',
                                    'MINEUR_ADMIN_LEGALE', 'MINEUR_TUTELLE',
                                    'MP_SAUVEGARDE', 'MP_HABILITATION',
                                    'MP_CURATELLE', 'MP_TUTELLE')),
    ordre               INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_dossier_titulaires_dossier ON dossier_titulaires (dossier_id);

-- ── Adresses (fiscale obligatoire + postale optionnelle) ────────────────────
CREATE TABLE dossier_adresses (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id   UUID NOT NULL REFERENCES registration_dossiers (id) ON DELETE CASCADE,
    titulaire_id UUID REFERENCES dossier_titulaires (id) ON DELETE CASCADE,
    type         TEXT NOT NULL CHECK (type IN ('FISCALE', 'POSTALE')),
    residence    TEXT,
    rue          TEXT NOT NULL,
    code_postal  TEXT,
    ville        TEXT NOT NULL,
    pays         TEXT NOT NULL,
    ordre        INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_dossier_adresses_dossier ON dossier_adresses (dossier_id);

-- ── Coordonnees ─────────────────────────────────────────────────────────────
CREATE TABLE dossier_contacts (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id         UUID NOT NULL REFERENCES registration_dossiers (id) ON DELETE CASCADE,
    titulaire_id       UUID REFERENCES dossier_titulaires (id) ON DELETE CASCADE,
    telephone_portable TEXT,
    telephone_domicile TEXT,
    email              TEXT
);
CREATE INDEX idx_dossier_contacts_dossier ON dossier_contacts (dossier_id);

-- ── Pieces justificatives televersees ───────────────────────────────────────
CREATE TABLE justificatifs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id    UUID NOT NULL REFERENCES registration_dossiers (id) ON DELETE CASCADE,
    titulaire_id  UUID REFERENCES dossier_titulaires (id) ON DELETE CASCADE,
    type          TEXT NOT NULL
        CHECK (type IN ('PIECE_IDENTITE', 'JUSTIF_DOMICILE', 'POUVOIRS_REP', 'DECISION_JUSTICE')),
    nom_fichier   TEXT NOT NULL,                            -- nom original
    chemin        TEXT NOT NULL,                            -- chemin de stockage (hors webroot)
    mime_type     TEXT,
    taille_octets BIGINT CHECK (taille_octets >= 0),
    sha256        TEXT,
    uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_justificatifs_dossier ON justificatifs (dossier_id);

-- ── Capture faciale + vivacite (le coeur de la demande) ─────────────────────
CREATE TABLE face_captures (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id          UUID NOT NULL REFERENCES registration_dossiers (id) ON DELETE CASCADE,
    titulaire_id        UUID REFERENCES dossier_titulaires (id) ON DELETE CASCADE,
    nom_fichier         TEXT NOT NULL,                      -- « stocke le nom »
    chemin              TEXT NOT NULL,                      -- « et le chemin en BD » (image capturee)
    landmarks_path      TEXT,                               -- JSON des 468 points 3D (rejeu marqueurs BO)
    liveness_score      NUMERIC(4, 3) CHECK (liveness_score BETWEEN 0 AND 1),
    liveness_passed     BOOLEAN NOT NULL DEFAULT FALSE,
    challenge_type      TEXT CHECK (challenge_type IN ('BLINK', 'TURN_HEAD', 'SMILE')),
    capture_width       INTEGER,
    capture_height      INTEGER,
    sha256              TEXT,
    verification_status TEXT NOT NULL DEFAULT 'EN_ATTENTE'
        CHECK (verification_status IN ('EN_ATTENTE', 'VALIDE', 'REJETE')),
    verified_by         UUID REFERENCES users (id),
    verified_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_face_captures_dossier ON face_captures (dossier_id);

-- ── Versions publiees de la convention (reference legale) ───────────────────
CREATE TABLE convention_versions (
    version      TEXT NOT NULL,
    langue       TEXT NOT NULL DEFAULT 'FR' CHECK (langue IN ('FR', 'EN')),
    titre        TEXT NOT NULL,
    contenu_html TEXT NOT NULL,                             -- les 18 articles
    pdf_path     TEXT,                                      -- PDF telechargeable (optionnel)
    is_current   BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (version, langue)
);
-- Une seule version courante par langue.
CREATE UNIQUE INDEX uq_convention_current
    ON convention_versions (langue) WHERE is_current;

-- ── Acceptations « lu et approuve » (preuve de consentement) ────────────────
CREATE TABLE convention_acceptations (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id         UUID NOT NULL REFERENCES registration_dossiers (id) ON DELETE CASCADE,
    titulaire_id       UUID NOT NULL REFERENCES dossier_titulaires (id) ON DELETE CASCADE,
    convention_version TEXT NOT NULL,                       -- version acceptee
    mention_saisie     TEXT NOT NULL,                       -- texte reellement tape (« lu et approuve »)
    nom                TEXT NOT NULL,
    prenom             TEXT NOT NULL,
    qualite            TEXT NOT NULL,
    signature_data     TEXT NOT NULL,                       -- PNG dataURL (comme orders.signature_data)
    lieu               TEXT NOT NULL,                       -- « Fait a … »
    date_acceptation   DATE NOT NULL,                       -- « le … »
    ip                 TEXT,
    user_agent         TEXT,
    recap_pdf_path     TEXT,                                -- justificatif genere
    accepted_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Un titulaire n'accepte qu'une fois une version donnee.
    UNIQUE (titulaire_id, convention_version)
);
CREATE INDEX idx_convention_acceptations_dossier ON convention_acceptations (dossier_id);
