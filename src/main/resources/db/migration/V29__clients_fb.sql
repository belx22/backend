-- V29 — Base des clients Afriland deja existants (« BASE CLIENTS »).
--
-- Le referentiel bancaire vit hors de la plateforme : il est importe depuis un
-- fichier Excel/CSV. Il sert de PIVOT a l'auto-inscription — au moment ou un
-- prospect saisit son numero de compte, on sait s'il est deja client :
--
--   * numero connu   -> on reprend ses informations (agence, matricule,
--                       categorie, compte de depot, dirigeant, contacts) et il
--                       ne reste qu'a completer photo + piece d'identite ;
--   * numero inconnu -> statut NOUVEAU_CLIENT : l'administrateur est notifie et
--                       le prospect est invite par courriel a se presenter en
--                       agence pour l'ouverture de son compte-titres.

CREATE TABLE clients_fb (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    nom_prenom       TEXT NOT NULL,
    -- Numero tel qu'il figure dans le fichier : agence(5) + compte(11) + cle(2),
    -- espaces retires. Ex. « 00090 56010090000 48 » -> « 000905601009000048 ».
    numero_compte    TEXT NOT NULL CHECK (numero_compte ~ '^\d{18}$'),
    -- Compte especes = code banque 10005 + numero du fichier -> 23 chiffres (RIB).
    -- Genere en base : impossible de le desynchroniser du numero source.
    compte_especes   TEXT GENERATED ALWAYS AS ('10005' || numero_compte) STORED,

    agence           TEXT CHECK (agence ~ '^\d{5}$'),
    matricule        TEXT,
    categorie        TEXT,
    -- Compte de depot (compte-titres) — INTERNE : jamais montre au client.
    compte_depot     TEXT,
    assujetti_taxes  BOOLEAN,
    localisation     TEXT,
    dirigeant        TEXT,
    telephone1       TEXT,
    telephone2       TEXT,
    email            TEXT,

    imported_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    imported_by      UUID REFERENCES users (id),

    -- Un compte n'apparait qu'une fois : un re-import met a jour la ligne.
    UNIQUE (numero_compte)
);

CREATE INDEX idx_clients_fb_especes   ON clients_fb (compte_especes);
CREATE INDEX idx_clients_fb_depot     ON clients_fb (compte_depot);
CREATE INDEX idx_clients_fb_nom       ON clients_fb (lower(nom_prenom));

-- ─── Rattachement du dossier d'inscription au referentiel ────────────────────

ALTER TABLE registration_dossiers
    ADD COLUMN client_fb_id UUID REFERENCES clients_fb (id),
    -- FALSE = prospect inconnu du referentiel : « nouveau client », a orienter
    -- en agence. On le stocke plutot que de le recalculer : c'est l'etat constate
    -- AU MOMENT de l'inscription, et le referentiel, lui, evolue.
    ADD COLUMN client_connu  BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN registration_dossiers.client_connu IS
    'Le numero de compte etait-il present dans clients_fb lors de l''inscription ?';
