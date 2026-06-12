-- ============================================================================
-- Configuration de l'affichage du marche primaire public (CSFT §M1 — CONFIG_MARCHE).
--
-- Determine quelles emissions sont visibles cote public : statuts retenus et
-- fenetre de dates optionnelle. Auparavant detenue uniquement cote navigateur
-- (signal local), cette configuration est desormais persistee en base afin
-- d'etre partagee entre tous les acteurs et de survivre aux rechargements.
--
-- Table singleton (une seule ligne, id = TRUE).
--   statuses : liste CSV de statuts d'emission (BROUILLON, PUBLIE, CLOTURE, ARCHIVE).
--   date_du / date_au : bornes optionnelles sur la date d'emission (NULL = pas de borne).
-- ============================================================================
CREATE TABLE marche_config (
    id          BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    statuses    TEXT NOT NULL DEFAULT 'PUBLIE',
    date_du     DATE,
    date_au     DATE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  UUID REFERENCES users (id)
);

-- Ligne unique initiale : seules les emissions PUBLIE sont visibles, sans borne de dates.
INSERT INTO marche_config (id) VALUES (TRUE);
