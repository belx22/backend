package cm.afriland.titres.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RbacTest {

    // ─── AGENT ───────────────────────────────────────────────────────────────

    @Test
    void agent_peut_creer_emissions() {
        assertTrue(Rbac.roleHasPermission("AGENT", Permission.EMISSION_CREATE));
    }

    @Test
    void agent_peut_valider_ordres() {
        assertTrue(Rbac.roleHasPermission("AGENT", Permission.ORDER_VALIDATE));
    }

    @Test
    void agent_peut_saisir_adjudication() {
        assertTrue(Rbac.roleHasPermission("AGENT", Permission.ORDER_RESULT));
    }

    @Test
    void agent_peut_creer_clients() {
        assertTrue(Rbac.roleHasPermission("AGENT", Permission.CLIENT_CREATE));
    }

    @Test
    void agent_ne_peut_pas_publier_emissions() {
        assertFalse(Rbac.roleHasPermission("AGENT", Permission.EMISSION_VALIDATE));
    }

    @Test
    void agent_ne_peut_pas_valider_adjudication() {
        assertFalse(Rbac.roleHasPermission("AGENT", Permission.ORDER_RESULT_VALIDATE));
    }

    @Test
    void agent_ne_peut_pas_gerer_utilisateurs() {
        assertFalse(Rbac.roleHasPermission("AGENT", Permission.USER_MANAGE));
    }

    @Test
    void agent_ne_peut_pas_lire_audit() {
        assertFalse(Rbac.roleHasPermission("AGENT", Permission.AUDIT_READ));
    }

    @Test
    void agent_ne_peut_pas_supprimer_emissions() {
        assertFalse(Rbac.roleHasPermission("AGENT", Permission.EMISSION_DELETE));
    }

    // ─── SUPERVISEUR ─────────────────────────────────────────────────────────

    @Test
    void superviseur_peut_publier_emissions() {
        assertTrue(Rbac.roleHasPermission("SUPERVISEUR", Permission.EMISSION_VALIDATE));
    }

    @Test
    void superviseur_peut_valider_adjudication() {
        assertTrue(Rbac.roleHasPermission("SUPERVISEUR", Permission.ORDER_RESULT_VALIDATE));
    }

    /**
     * L'adjudication ne compte plus qu'un seul niveau : le resultat saisi prend
     * effet immediatement (ORDER_RESULT). Le superviseur ne detenait plus que
     * ORDER_RESULT_VALIDATE — la permission d'une etape disparue — et se voyait
     * donc refuser l'application du resultat qu'il venait de saisir.
     */
    @Test
    void superviseur_peut_SAISIR_le_resultat_d_adjudication() {
        assertTrue(Rbac.roleHasPermission("SUPERVISEUR", Permission.ORDER_RESULT),
                "un superviseur doit pouvoir appliquer un resultat d'adjudication");
    }

    @Test
    void superviseur_peut_lire_reporting() {
        assertTrue(Rbac.roleHasPermission("SUPERVISEUR", Permission.REPORTING_READ));
    }

    @Test
    void superviseur_ne_peut_pas_lire_audit() {
        assertFalse(Rbac.roleHasPermission("SUPERVISEUR", Permission.AUDIT_READ));
    }

    @Test
    void superviseur_ne_peut_pas_gerer_utilisateurs() {
        assertFalse(Rbac.roleHasPermission("SUPERVISEUR", Permission.USER_MANAGE));
    }

    @Test
    void superviseur_ne_peut_pas_supprimer_emissions() {
        assertFalse(Rbac.roleHasPermission("SUPERVISEUR", Permission.EMISSION_DELETE));
    }

    // ─── ADMIN ───────────────────────────────────────────────────────────────

    @Test
    void admin_peut_gerer_utilisateurs() {
        assertTrue(Rbac.roleHasPermission("ADMIN", Permission.USER_MANAGE));
    }

    @Test
    void admin_peut_lire_audit() {
        assertTrue(Rbac.roleHasPermission("ADMIN", Permission.AUDIT_READ));
    }

    @Test
    void admin_peut_supprimer_emissions() {
        assertTrue(Rbac.roleHasPermission("ADMIN", Permission.EMISSION_DELETE));
    }

    @Test
    void admin_peut_publier_emissions() {
        assertTrue(Rbac.roleHasPermission("ADMIN", Permission.EMISSION_VALIDATE));
    }

    @Test
    void admin_ne_peut_pas_valider_ordres() {
        assertFalse(Rbac.roleHasPermission("ADMIN", Permission.ORDER_VALIDATE));
    }

    @Test
    void admin_ne_peut_pas_saisir_adjudication() {
        assertFalse(Rbac.roleHasPermission("ADMIN", Permission.ORDER_RESULT));
    }

    @Test
    void admin_ne_peut_pas_valider_adjudication() {
        assertFalse(Rbac.roleHasPermission("ADMIN", Permission.ORDER_RESULT_VALIDATE));
    }

    // ─── CLIENT_PP / CLIENT_PM ────────────────────────────────────────────────

    @Test
    void client_pp_sans_permission_backoffice() {
        assertFalse(Rbac.roleHasPermission("CLIENT_PP", Permission.EMISSION_CREATE));
        assertFalse(Rbac.roleHasPermission("CLIENT_PP", Permission.ORDER_VALIDATE));
        assertFalse(Rbac.roleHasPermission("CLIENT_PP", Permission.USER_MANAGE));
        assertFalse(Rbac.roleHasPermission("CLIENT_PP", Permission.AUDIT_READ));
    }

    @Test
    void client_pm_sans_permission_backoffice() {
        assertFalse(Rbac.roleHasPermission("CLIENT_PM", Permission.EMISSION_VALIDATE));
        assertFalse(Rbac.roleHasPermission("CLIENT_PM", Permission.ORDER_RESULT));
        assertFalse(Rbac.roleHasPermission("CLIENT_PM", Permission.REPORTING_READ));
    }

    // ─── Rôles inconnus ──────────────────────────────────────────────────────

    @Test
    void role_inconnu_retourne_faux() {
        assertFalse(Rbac.roleHasPermission("INCONNU", Permission.EMISSION_CREATE));
    }

    @Test
    void role_null_retourne_faux() {
        assertFalse(Rbac.roleHasPermission(null, Permission.EMISSION_CREATE));
    }

    // ─── isClient ─────────────────────────────────────────────────────────────

    @Test
    void isClient_vrai_pour_client_pp() {
        assertTrue(Rbac.isClient("CLIENT_PP"));
    }

    @Test
    void isClient_vrai_pour_client_pm() {
        assertTrue(Rbac.isClient("CLIENT_PM"));
    }

    @Test
    void isClient_faux_pour_agent() {
        assertFalse(Rbac.isClient("AGENT"));
    }

    @Test
    void isClient_faux_pour_superviseur() {
        assertFalse(Rbac.isClient("SUPERVISEUR"));
    }

    @Test
    void isClient_faux_pour_admin() {
        assertFalse(Rbac.isClient("ADMIN"));
    }

    @Test
    void isClient_faux_pour_null() {
        assertFalse(Rbac.isClient(null));
    }

    // ─── isStaff ──────────────────────────────────────────────────────────────

    @Test
    void isStaff_vrai_pour_agent() {
        assertTrue(Rbac.isStaff("AGENT"));
    }

    @Test
    void isStaff_vrai_pour_superviseur() {
        assertTrue(Rbac.isStaff("SUPERVISEUR"));
    }

    @Test
    void isStaff_vrai_pour_admin() {
        assertTrue(Rbac.isStaff("ADMIN"));
    }

    @Test
    void isStaff_faux_pour_client_pp() {
        assertFalse(Rbac.isStaff("CLIENT_PP"));
    }

    @Test
    void isStaff_faux_pour_client_pm() {
        assertFalse(Rbac.isStaff("CLIENT_PM"));
    }

    @Test
    void isStaff_faux_pour_null() {
        assertFalse(Rbac.isStaff(null));
    }

    // ─── matrixSnapshot ───────────────────────────────────────────────────────

    @Test
    void matrixSnapshot_contient_les_5_roles() {
        Map<String, List<String>> snap = Rbac.matrixSnapshot();
        assertTrue(snap.containsKey("AGENT"));
        assertTrue(snap.containsKey("SUPERVISEUR"));
        assertTrue(snap.containsKey("ADMIN"));
        assertTrue(snap.containsKey("CLIENT_PP"));
        assertTrue(snap.containsKey("CLIENT_PM"));
    }

    @Test
    void matrixSnapshot_permissions_triees_alphabetiquement() {
        List<String> perms = Rbac.matrixSnapshot().get("AGENT");
        assertNotNull(perms);
        for (int i = 0; i < perms.size() - 1; i++) {
            assertTrue(perms.get(i).compareTo(perms.get(i + 1)) <= 0,
                    "Les permissions doivent être triées : " + perms.get(i) + " > " + perms.get(i + 1));
        }
    }

    @Test
    void matrixSnapshot_agent_contient_emission_create() {
        assertTrue(Rbac.matrixSnapshot().get("AGENT").contains("EMISSION_CREATE"));
    }

    @Test
    void matrixSnapshot_superviseur_contient_emission_validate() {
        assertTrue(Rbac.matrixSnapshot().get("SUPERVISEUR").contains("EMISSION_VALIDATE"));
    }

    @Test
    void matrixSnapshot_admin_contient_user_manage() {
        assertTrue(Rbac.matrixSnapshot().get("ADMIN").contains("USER_MANAGE"));
    }
}
