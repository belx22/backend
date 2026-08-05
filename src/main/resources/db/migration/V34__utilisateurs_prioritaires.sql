-- ============================================================================
-- V34 — Utilisateurs PRIORITAIRES : dispense de controle facial sur les ordres
--
--  Certains investisseurs (institutionnels, comptes propres, partenaires connus
--  du guichet) n'ont pas a se prendre en photo pour passer un ordre : leur
--  identite est etablie en amont, a l'ouverture du compte, par l'ADMINISTRATEUR
--  de la plateforme qui cree lui-meme leur compte. Leur imposer une capture
--  faciale a chaque operation n'apporte aucune garantie supplementaire et
--  bloque un flux a fort volume.
--
--  Consequences, et elles sont volontairement limitees a la PREUVE FACIALE :
--   • soumission d'un ordre — la photo n'est plus demandee ;
--   • cosignature d'un compte joint — la signature manuscrite reste exigee,
--     seule la photo tombe (c'est le visage que l'on dispense, pas le consentement) ;
--   • back-office — l'absence de photo s'affiche « DISPENSE » et non
--     « NON_COMPARABLE », pour ne pas polluer la file des ordres a revoir.
--
--  Ce que le drapeau ne change PAS : l'OTP, la signature, le RBAC, les plafonds.
--
--  Attribution : reservee a l'ADMIN (cf. ClientController / UserController).
--  Un compte auto-inscrit en ligne naît donc a FALSE et le reste tant qu'un
--  administrateur ne l'a pas explicitement marque — le defaut est le controle.
-- ============================================================================

ALTER TABLE users ADD COLUMN prioritaire BOOLEAN NOT NULL DEFAULT FALSE;

-- Index partiel : les prioritaires sont une minorite, on n'indexe qu'eux.
CREATE INDEX idx_users_prioritaire ON users (prioritaire) WHERE prioritaire;

COMMENT ON COLUMN users.prioritaire IS
    'Utilisateur prioritaire : dispense de capture faciale pour ses operations. '
    'Positionne uniquement par un administrateur de la plateforme.';
