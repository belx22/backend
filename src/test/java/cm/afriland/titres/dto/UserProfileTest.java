package cm.afriland.titres.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Ce qu'un CLIENT a le droit de voir de son propre profil.
 *
 * <p>Deux informations sont strictement reservees au back-office : le
 * <b>solde</b> (la source de verite est le serveur AIF) et le <b>compte de
 * depot</b> (p. ex. {@code CCEICMCXX345}, identifiant interne au depositaire).
 * Les masquer a l'affichage ne suffirait pas : ils resteraient lisibles dans la
 * reponse de l'API.</p>
 */
class UserProfileTest {

    private static UserProfile complet() {
        return new UserProfile(UUID.randomUUID(), "NGONO", "Alice", "alice@example.cm",
                "CLIENT_PP", "ACTIF", "CCEICMCXX345", "10005000905601009000048",
                7_500_000L, "NON_QUALIFIE", "INDIVIDUEL", "+237690000000", false, false);
    }

    @Test
    void pourClient_efface_le_solde_ET_le_compte_de_depot() {
        UserProfile vu = complet().pourClient();

        assertNull(vu.solde(), "le solde ne doit jamais partir vers un client");
        assertNull(vu.compteTitres(), "le compte de depot ne doit jamais partir vers un client");
    }

    @Test
    void pourClient_conserve_tout_le_reste() {
        UserProfile source = complet();
        UserProfile vu = source.pourClient();

        assertEquals(source.id(), vu.id());
        assertEquals("NGONO", vu.nom());
        assertEquals("Alice", vu.prenom());
        assertEquals("alice@example.cm", vu.email());
        assertEquals("CLIENT_PP", vu.role());
        assertEquals("ACTIF", vu.statut());
        // Le compte ESPECES, lui, appartient au client : il le voit.
        assertEquals("10005000905601009000048", vu.compteEspeces());
        assertEquals("NON_QUALIFIE", vu.categorie());
        assertEquals("INDIVIDUEL", vu.typeCompte());
        assertEquals("+237690000000", vu.telephone());
    }

    @Test
    void withoutBalance_n_efface_que_le_solde() {
        UserProfile vu = complet().withoutBalance();
        assertNull(vu.solde());
        assertEquals("CCEICMCXX345", vu.compteTitres());
    }

    @Test
    void withoutCompteTitres_n_efface_que_le_compte_de_depot() {
        UserProfile vu = complet().withoutCompteTitres();
        assertNull(vu.compteTitres());
        assertEquals(7_500_000L, vu.solde());
    }

    /** Le masquage doit resister a une double application (idempotent). */
    @Test
    void pourClient_est_idempotent() {
        UserProfile vu = complet().pourClient().pourClient();
        assertNull(vu.solde());
        assertNull(vu.compteTitres());
        assertEquals("10005000905601009000048", vu.compteEspeces());
    }
}
