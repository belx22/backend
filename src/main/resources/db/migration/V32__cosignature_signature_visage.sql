-- ============================================================================
-- V32 — Compte JOINT : la cosignature exige signature + capture faciale, et le
--       back-office peut passer outre l'attente des autres signataires.
-- ============================================================================
-- Jusqu'ici un co-signataire validait par un simple booleen (`approve`). On
-- aligne desormais la validation d'un ordre sur ce qui est exige a l'INSCRIPTION :
-- le co-signataire signe (trace manuscrite) ET presente son visage. La photo du
-- jour est enregistree dans `order_face_checks` (deja porteur de order_id +
-- user_id : une ligne par personne, aucune table nouvelle n'est necessaire) et
-- comparee par le SERVEUR a l'empreinte capturee a l'ouverture de son compte.

-- 1) Trace de la signature manuscrite du co-signataire (PNG dataURL), comme
--    `orders.signature_data` pour l'emetteur.
ALTER TABLE order_signatures ADD COLUMN signature_data TEXT;

-- 2) Nouveau statut BYPASSED : le back-office a valide l'ordre sans attendre la
--    reponse de ce signataire. On ne reutilise pas EXPIRED (delai depasse) ni
--    REJECTED (refus explicite) : la distinction est la trace de QUI a tranche.
ALTER TABLE order_signatures DROP CONSTRAINT IF EXISTS order_signatures_status_check;
ALTER TABLE order_signatures ADD CONSTRAINT order_signatures_status_check CHECK (status IN
    ('PENDING', 'SIGNED', 'REJECTED', 'EXPIRED', 'BYPASSED'));

-- 3) Tracabilite de la validation forcee, au niveau de l'ordre : qui a decide de
--    transmettre sans l'accord de tous, et quand. Sans cela, un ordre joint
--    transmis avec des signatures PENDING serait inexplicable a posteriori.
ALTER TABLE orders ADD COLUMN signatures_bypassed_by UUID REFERENCES users (id);
ALTER TABLE orders ADD COLUMN signatures_bypassed_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN signatures_bypass_motif TEXT;

-- 4) La photo du co-signataire est rattachee a (order_id, user_id). L'index de
--    V24 porte sur order_id seul ; on garantit ici qu'une personne n'a qu'un
--    controle facial par ordre, pour que la vue back-office (une ligne par
--    personne) reste sans ambiguite.
DELETE FROM order_face_checks a USING order_face_checks b
    WHERE a.order_id = b.order_id AND a.user_id = b.user_id AND a.ctid < b.ctid;
CREATE UNIQUE INDEX idx_order_face_checks_order_user ON order_face_checks (order_id, user_id);
