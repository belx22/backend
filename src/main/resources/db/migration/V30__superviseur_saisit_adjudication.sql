-- V30 — Le superviseur peut saisir le resultat d'adjudication.
--
-- L'adjudication est passee a UN SEUL niveau : le resultat saisi prend effet
-- immediatement (permission ORDER_RESULT), sans etape de validation par un
-- second acteur. Or V10 avait retire ORDER_RESULT au superviseur pour ne lui
-- laisser que ORDER_RESULT_VALIDATE — la permission de valider la proposition
-- d'un agent, etape qui n'existe plus.
--
-- Consequence : un superviseur saisissait un resultat d'adjudication et se
-- voyait refuser son application (403). L'ordre restait indefiniment en
-- EN_ATTENTE_ADJUDICATION.
--
-- Il conserve ORDER_RESULT_VALIDATE : d'anciens ordres peuvent encore attendre
-- la validation d'un resultat propose sous le flux a deux niveaux.

INSERT INTO role_permissions (role, permission)
VALUES ('SUPERVISEUR', 'ORDER_RESULT')
ON CONFLICT DO NOTHING;
