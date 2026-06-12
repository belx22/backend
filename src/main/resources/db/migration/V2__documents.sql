-- ============================================================================
-- Livrables reglementaires — CSFT §M4.
-- ============================================================================
CREATE TABLE documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference       TEXT NOT NULL UNIQUE,
    type            TEXT NOT NULL,
    titre           TEXT NOT NULL CHECK (char_length(titre) BETWEEN 1 AND 200),
    client_id       UUID NOT NULL REFERENCES users (id),
    uploaded_by     UUID NOT NULL REFERENCES users (id),
    mime_type       TEXT NOT NULL DEFAULT 'application/pdf',
    taille          TEXT,
    contenu         TEXT NOT NULL,
    telechargements INTEGER NOT NULL DEFAULT 0,
    date_generation TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_documents_client ON documents (client_id);
