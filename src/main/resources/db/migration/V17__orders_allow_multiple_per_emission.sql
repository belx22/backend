-- ============================================================================
-- Evolution metier : un client peut desormais soumettre PLUSIEURS ordres sur
-- une meme emission. L'unicite « un ordre actif par client et par emission »
-- (V7) est donc levee. Le frontend confirme par un pop-up lorsqu'un ordre actif
-- existe deja sur le meme titre, mais n'empeche plus la re-soumission.
-- ============================================================================
DROP INDEX IF EXISTS uq_orders_active_per_client_emission;
