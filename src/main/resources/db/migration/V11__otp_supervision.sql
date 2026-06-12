-- ============================================================================
-- V11 — Supervision des codes OTP (relais back-office).
--
-- Besoin metier : permettre au back-office (admin / agent) de consulter le code
-- OTP envoye a un utilisateur ou un client, afin de le relayer par un autre
-- canal en cas de non-reception (ou de le communiquer si le client est present).
--
-- La verification du code reste basee sur l'empreinte (code_hash) ; code_plain
-- n'est utilise QUE pour l'affichage de supervision, sur des codes a duree de
-- vie courte (TTL otp_settings) et n'est jamais journalise.
-- ============================================================================

ALTER TABLE mfa_challenges ADD COLUMN code_plain TEXT;
