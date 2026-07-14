-- V28 — Le compte-titres n'est plus un prerequis pour soumettre un ordre.
--
-- A l'inscription en ligne, le client ne renseigne que son COMPTE ESPECES ; le
-- COMPTE-TITRES lui est attribue par un agent du back-office. Il doit pourtant
-- pouvoir soumettre un ordre entre-temps : l'ordre est alors enregistre sans
-- compte-titres, et le back-office le voit signale en rouge tant que le compte
-- n'est pas renseigne.
--
-- Le compte especes, lui, reste obligatoire : il est saisi des l'inscription.

ALTER TABLE orders ALTER COLUMN compte_titres DROP NOT NULL;

COMMENT ON COLUMN orders.compte_titres IS
    'Compte-titres du client au moment de l''ordre ; NULL tant que le back-office ne l''a pas attribue.';
