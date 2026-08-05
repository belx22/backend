-- ============================================================================
-- V35 — Reglage GLOBAL : activation/desactivation de la capture faciale.
--
--  Interrupteur unique, pilote par l'administrateur, qui coupe la capture faciale
--  (photo + vivacite) A L'INSCRIPTION et A LA SOUMISSION/COSIGNATURE, pour TOUS.
--  Il se combine avec le drapeau par compte `users.prioritaire` (V34) : un point
--  n'exige la photo que si le scan est globalement actif ET que la personne n'est
--  pas prioritaire.
--
--  Ne touche QUE la preuve faciale : la signature manuscrite, l'OTP, le RBAC et
--  les autres controles restent inchanges. Defaut : ACTIF (le scan reste exige
--  tant qu'un administrateur ne l'a pas explicitement coupe).
-- ============================================================================

CREATE TABLE face_scan_settings (
    -- Table a ligne unique (id = TRUE) — meme motif que otp_settings / mail_settings.
    id         BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (id)
);

INSERT INTO face_scan_settings (id, enabled) VALUES (TRUE, TRUE);
