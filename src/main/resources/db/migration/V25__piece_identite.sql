-- ============================================================================
--  Piece d'identite du parcours d'inscription (CNI / carte de sejour / passeport).
--
--  Le navigateur controle la QUALITE (nettete, resolution) et lit le document
--  (OCR) pour s'assurer qu'il s'agit bien d'une piece d'identite — un selfie ou
--  un document quelconque est refuse a la saisie. Le texte extrait et les
--  indicateurs sont conserves ici : le BACK-OFFICE reste l'autorite et voit le
--  document ET ce qui en a ete lu avant de valider le dossier.
-- ============================================================================

ALTER TABLE justificatifs ADD COLUMN document_type TEXT
    CHECK (document_type IN ('CNI', 'PASSEPORT', 'CARTE_SEJOUR'));

-- Recto / verso d'une meme piece.
ALTER TABLE justificatifs ADD COLUMN cote TEXT
    CHECK (cote IN ('RECTO', 'VERSO'));

-- Texte reellement lu sur le document (controle du back-office).
ALTER TABLE justificatifs ADD COLUMN ocr_texte TEXT;

-- Indicateurs de qualite de l'image.
ALTER TABLE justificatifs ADD COLUMN nettete INTEGER;
ALTER TABLE justificatifs ADD COLUMN largeur INTEGER;
ALTER TABLE justificatifs ADD COLUMN hauteur INTEGER;

-- Revue humaine de la piece.
ALTER TABLE justificatifs ADD COLUMN verification_status TEXT NOT NULL DEFAULT 'EN_ATTENTE'
    CHECK (verification_status IN ('EN_ATTENTE', 'VALIDE', 'REJETE'));
ALTER TABLE justificatifs ADD COLUMN verified_by UUID REFERENCES users (id);
ALTER TABLE justificatifs ADD COLUMN verified_at TIMESTAMPTZ;

CREATE INDEX idx_justificatifs_type ON justificatifs (dossier_id, type);
