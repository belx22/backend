-- V31 — Catégorie « client » (segment métier) + alignement sur la BASE CLIENTS.
--
-- Le client de la plateforme (client_profiles) ne portait qu'une catégorie
-- INVESTISSEUR (users.categorie : QUALIFIE / NON_QUALIFIE). La « BASE CLIENTS »
-- de la banque (clients_fb) porte, elle, une catégorie MÉTIER bien plus riche
-- (Personnes physiques, Société de Bourse, Établissements financiers, …) ainsi
-- que d'autres attributs (matricule, dirigeant, assujettissement aux taxes,
-- localisation, second téléphone).
--
-- On AJOUTE ces éléments au profil client — sans toucher à users.categorie, qui
-- garde sa sémantique investisseur (QUALIFIE / NON_QUALIFIE) indépendante.

ALTER TABLE client_profiles
    -- Catégorie métier : liste FIGÉE des 12 valeurs réellement présentes dans la
    -- base. Toute autre valeur est refusée ; NULL reste toléré (profil non encore
    -- rattaché au référentiel).
    ADD COLUMN categorie_client TEXT CHECK (categorie_client IS NULL OR categorie_client IN (
        'Personnes physiques',
        'Personne morale',
        'Entreprises non financières',
        'Etablissements de microfinance',
        'Etablissements financiers',
        'Société de Gestion',
        'Société de Bourse',
        'Investisseurs institutionnels',
        'Assurance',
        'Administrations privées',
        'Administrations publiques',
        'Compte propre'
    )),
    -- Attributs repris de la base clients (clients_fb) pour aligner le profil.
    ADD COLUMN matricule       TEXT,
    ADD COLUMN dirigeant       TEXT,
    -- Tri-état : TRUE (OUI), FALSE (NON), NULL (inconnu) — comme clients_fb.
    ADD COLUMN assujetti_taxes BOOLEAN,
    ADD COLUMN localisation    TEXT,
    ADD COLUMN telephone2      TEXT;

COMMENT ON COLUMN client_profiles.categorie_client IS
    'Catégorie métier (segment) issue de la BASE CLIENTS, distincte de users.categorie (QUALIFIE/NON_QUALIFIE).';
