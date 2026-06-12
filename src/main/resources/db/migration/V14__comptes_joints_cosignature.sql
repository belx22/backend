-- ============================================================================
-- V14 — Comptes-titres JOINTS : logins multiples + double signature des ordres.
-- ============================================================================
-- Un compte joint regroupe plusieurs signataires, chacun avec SON propre login
-- (tracabilite). Un ordre passe sur un compte joint n'est transmis qu'une fois
-- valide par TOUS les autres signataires ; a defaut (refus ou delai depasse), il
-- est rejete et l'emetteur est notifie.

-- 1) Rattachement co-signataire -> titulaire principal du compte.
--    NULL pour les comptes individuels et les acteurs internes.
ALTER TABLE users ADD COLUMN account_holder_id UUID REFERENCES users (id);
CREATE INDEX idx_users_account_holder ON users (account_holder_id);

-- 2) Nouveau statut d'ordre : en attente des signatures des co-titulaires.
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check CHECK (status IN
    ('SOUMIS','EN_VERIFICATION','EN_ATTENTE_ADJUDICATION',
     'TOTALEMENT_RETENU','PARTIELLEMENT_RETENU','NON_RETENU','ANNULE',
     'EN_ATTENTE_SIGNATURES'));

-- 3) Demandes de signature par ordre — une ligne par signataire attendu.
CREATE TABLE order_signatures (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id      UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    signatory_id  UUID NOT NULL REFERENCES users (id),
    status        TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','SIGNED','REJECTED','EXPIRED')),
    expires_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at    TIMESTAMPTZ,
    UNIQUE (order_id, signatory_id)
);
CREATE INDEX idx_order_signatures_order ON order_signatures (order_id);
CREATE INDEX idx_order_signatures_signatory ON order_signatures (signatory_id, status);
