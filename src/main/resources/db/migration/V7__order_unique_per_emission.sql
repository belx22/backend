-- ============================================================================
-- Un client ne peut souscrire qu'une seule fois a une emission donnee.
-- Un ordre annule (statut ANNULE) ne bloque pas une nouvelle souscription :
-- l'index est partiel et n'indexe pas les lignes au statut ANNULE.
-- ============================================================================
CREATE UNIQUE INDEX uq_orders_active_per_client_emission
    ON orders (client_id, emission_id)
    WHERE status <> 'ANNULE';
