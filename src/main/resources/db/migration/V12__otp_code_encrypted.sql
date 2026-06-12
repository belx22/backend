-- ============================================================================
-- V12 — Chiffrement au repos du code OTP de supervision.
--
-- Le code n'est plus stocke en clair : la colonne contient desormais le chiffre
-- AES-256-GCM (SecretCipher), dechiffre uniquement a la lecture par l'endpoint
-- back-office autorise. Protege les sauvegardes / dumps de base.
--
-- Les valeurs heritees (en clair, codes de test deja consommes) sont purgees :
-- elles ne seraient pas dechiffrables et n'ont aucune valeur operationnelle.
-- ============================================================================

ALTER TABLE mfa_challenges RENAME COLUMN code_plain TO code_enc;
UPDATE mfa_challenges SET code_enc = NULL;
