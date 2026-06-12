-- ============================================================================
-- Personnalisation de l'habillage des e-mails sortants (CSFT §M5 — messagerie).
--
-- Permet a l'administrateur de definir, sans toucher au code :
--   logo_url  : URL du logo affiche en en-tete de chaque e-mail (defaut : logo
--               Afriland servi par le frontend). Vide => repli automatique.
--   signature : message libre (HTML simple autorise) affiche en pied de chaque
--               e-mail (coordonnees, mention legale, « ne pas repondre »…).
-- Tous les e-mails (OTP, reinitialisation, diffusion du catalogue) sont rendus
-- dans un gabarit commun : en-tete logo + contenu + signature.
-- ============================================================================
ALTER TABLE mail_settings
    ADD COLUMN logo_url  TEXT,
    ADD COLUMN signature TEXT;

-- Signature par defaut (modifiable depuis l'espace d'administration).
UPDATE mail_settings
SET signature = 'Afriland First Bank — Direction des Ressources et Investissements.<br/>'
                || 'Cet e-mail est automatique, merci de ne pas y répondre.'
WHERE id = TRUE;
