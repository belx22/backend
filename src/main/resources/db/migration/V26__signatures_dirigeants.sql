-- ============================================================================
--  Signatures des dirigeants — apposees sur les documents generes par la
--  plateforme (avis d'opere, attestations...).
--
--  L'image est stockee en data URI (comme la signature du client sur un ordre,
--  cf. orders.signature_data) : les documents sont rendus en HTML puis captures
--  en PDF cote navigateur, une URL externe y serait inutilisable (et interdite
--  par la CSP). Le volume est faible : quelques signatures, quelques dizaines de
--  Ko chacune.
-- ============================================================================
CREATE TABLE signatures_dirigeants (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nom            TEXT NOT NULL,
    fonction       TEXT NOT NULL,              -- ex. « Directeur General »
    -- Image de la signature : data URI (image/png|jpeg|webp), base64.
    signature_data TEXT NOT NULL,
    -- Seules les signatures actives sont apposees sur les documents.
    actif          BOOLEAN NOT NULL DEFAULT TRUE,
    -- Ordre d'affichage (de gauche a droite) sur le document.
    ordre          INTEGER NOT NULL DEFAULT 0,
    created_by     UUID REFERENCES users (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Les documents ne lisent que les signatures actives, dans l'ordre.
CREATE INDEX idx_signatures_dirigeants_actives
    ON signatures_dirigeants (ordre) WHERE actif;
