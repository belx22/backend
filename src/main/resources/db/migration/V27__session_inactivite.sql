-- ============================================================================
--  Expiration de session par INACTIVITE — controlee par le SERVEUR.
--
--  Defaut corrige : le jeton de rafraichissement vivait 30 jours dans un cookie.
--  Il suffisait de revenir sur le site (meme apres des heures) pour que
--  /auth/refresh rende une nouvelle session : l'utilisateur etait reconnecte
--  automatiquement. Le garde-fou d'inactivite existant (IdleService) est cote
--  NAVIGATEUR : onglet ferme, plus aucun minuteur — donc aucune expiration.
--
--  On ajoute une fenetre d'inactivite GLISSANTE, verifiee a chaque refresh :
--  passe ce delai sans activite, la session est morte, quoi que dise le cookie.
-- ============================================================================

-- Derniere utilisation effective de la session (mise a jour a chaque refresh).
ALTER TABLE refresh_tokens
    ADD COLUMN last_used_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Les sessions existantes sont considerees comme utilisees a l'instant de la
-- migration : elles expireront normalement a la premiere inactivite prolongee.
UPDATE refresh_tokens SET last_used_at = COALESCE(created_at, now());
