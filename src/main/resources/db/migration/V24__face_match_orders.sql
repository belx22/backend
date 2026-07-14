-- ============================================================================
--  Comparaison faciale 1:1 — « est-ce bien le titulaire qui passe cet ordre ? »
--
--  A l'ouverture du compte, on enregistre une EMPREINTE faciale (descripteur
--  128 dimensions). A chaque ordre soumis, une nouvelle photo est prise ; le
--  SERVEUR compare les deux empreintes (distance euclidienne) et enregistre le
--  resultat. La comparaison est POSTERIEURE a la soumission : elle ne bloque
--  jamais le client — elle alimente la revue du back-office.
-- ============================================================================

-- Empreinte faciale de reference, capturee a l'ouverture du compte.
-- JSON : tableau de 128 flottants.
ALTER TABLE face_captures ADD COLUMN descriptor TEXT;

-- Controle facial rattache a un ordre soumis.
CREATE TABLE order_face_checks (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    user_id             UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- Photo prise au moment de l'ordre.
    nom_fichier         TEXT NOT NULL,
    chemin              TEXT NOT NULL,
    descriptor          TEXT,                        -- empreinte 128-d du jour
    sha256              TEXT,

    -- Vivacite (anti-photo) de la capture du jour.
    liveness_score      NUMERIC(4, 3) CHECK (liveness_score BETWEEN 0 AND 1),
    challenge_type      TEXT CHECK (challenge_type IN ('BLINK', 'TURN_HEAD', 'SMILE')),

    -- Resultat de la comparaison SERVEUR avec l'empreinte d'ouverture.
    distance            NUMERIC(6, 4),               -- distance euclidienne
    matched             BOOLEAN,                     -- distance < seuil
    -- NON_COMPARABLE : empreinte d'ouverture absente ou visage non exploitable.
    match_status        TEXT NOT NULL DEFAULT 'NON_COMPARABLE'
        CHECK (match_status IN ('CORRESPOND', 'DIFFERENT', 'NON_COMPARABLE')),

    -- Revue humaine par le back-office (l'automatique ne fait pas foi seul).
    verification_status TEXT NOT NULL DEFAULT 'EN_ATTENTE'
        CHECK (verification_status IN ('EN_ATTENTE', 'VALIDE', 'REJETE')),
    verified_by         UUID REFERENCES users (id),
    verified_at         TIMESTAMPTZ,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_face_checks_order ON order_face_checks (order_id);
CREATE INDEX idx_order_face_checks_user  ON order_face_checks (user_id);
-- Les ordres a revoir en priorite : visage different ou non comparable.
CREATE INDEX idx_order_face_checks_arevoir ON order_face_checks (match_status)
    WHERE match_status <> 'CORRESPOND';
