-- ============================================================================
-- Signature electronique de l'ordre de souscription — CSFT §M2-O02.
-- ============================================================================
ALTER TABLE orders ADD COLUMN signature_data     TEXT;
ALTER TABLE orders ADD COLUMN signature_verified BOOLEAN;
