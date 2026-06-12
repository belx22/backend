-- ============================================================================
-- Reduction du modele d'acteurs aux 4 profils metier retenus :
--   CLIENT_PP / CLIENT_PM (client investisseur), AGENT (agent tresorerie),
--   SUPERVISEUR, ADMIN.
-- Les roles CONFORMITE et DIRECTION sont retires du systeme.
--
-- Corrige aussi une lacune : la permission ACCOUNT_BALANCE_READ (introduite
-- apres V5) n'avait jamais ete inseree dans la matrice RBAC persistee, donc
-- n'etait accordee a personne au runtime (Rbac.loadMatrix lit la base).
-- ============================================================================

-- 1) Neutraliser d'eventuelles references avant suppression (securite).
UPDATE emissions SET created_by = NULL
    WHERE created_by IN (SELECT id FROM users WHERE role IN ('CONFORMITE','DIRECTION'));
UPDATE emissions SET validated_by = NULL
    WHERE validated_by IN (SELECT id FROM users WHERE role IN ('CONFORMITE','DIRECTION'));
UPDATE emissions SET rejected_by = NULL
    WHERE rejected_by IN (SELECT id FROM users WHERE role IN ('CONFORMITE','DIRECTION'));
UPDATE orders SET validated_by_agent = NULL
    WHERE validated_by_agent IN (SELECT id FROM users WHERE role IN ('CONFORMITE','DIRECTION'));

-- 2) Supprimer la matrice et les comptes des deux roles retires.
DELETE FROM role_permissions WHERE role IN ('CONFORMITE','DIRECTION');
DELETE FROM users WHERE role IN ('CONFORMITE','DIRECTION');

-- 3) Resserrer les contraintes CHECK sur les roles autorises.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('CLIENT_PP','CLIENT_PM','AGENT','SUPERVISEUR','ADMIN'));

ALTER TABLE role_permissions DROP CONSTRAINT IF EXISTS role_permissions_role_check;
ALTER TABLE role_permissions ADD CONSTRAINT role_permissions_role_check
    CHECK (role IN ('CLIENT_PP','CLIENT_PM','AGENT','SUPERVISEUR','ADMIN'));

-- 4) Combler la lacune ACCOUNT_BALANCE_READ dans la matrice persistee.
INSERT INTO role_permissions (role, permission) VALUES
    ('AGENT','ACCOUNT_BALANCE_READ'),
    ('SUPERVISEUR','ACCOUNT_BALANCE_READ'),
    ('ADMIN','ACCOUNT_BALANCE_READ')
ON CONFLICT (role, permission) DO NOTHING;
