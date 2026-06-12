-- ============================================================================
-- Notifications in-app — CSFT §M2-S03.
-- ============================================================================
CREATE TABLE notifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        TEXT NOT NULL DEFAULT 'INFO'
        CHECK (type IN ('INFO', 'SUCCESS', 'WARN', 'ERROR')),
    titre       TEXT NOT NULL,
    message     TEXT NOT NULL,
    reference   TEXT,
    lu          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications (user_id, lu);
CREATE INDEX idx_notifications_created ON notifications (created_at DESC);
