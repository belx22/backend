-- ============================================================================
-- Reinitialisation de mot de passe en self-service (CSFT §M6-S01).
--
-- L'utilisateur demande un lien depuis « Mot de passe oublie ». Un jeton opaque
-- a usage unique est genere, son empreinte SHA-256 est stockee ici (jamais la
-- valeur en clair), et le lien est envoye par e-mail. Le jeton expire au bout
-- d'une heure et devient inutilisable une fois consomme (used_at renseigne).
-- ============================================================================
CREATE TABLE password_reset_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  TEXT NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens (user_id);
