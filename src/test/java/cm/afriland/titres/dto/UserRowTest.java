package cm.afriland.titres.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Projection {@link UserRow} → {@link UserProfile}, et surtout la regle
 * {@code compteJoint} qui pilote l'affichage du menu « Validations ».
 */
class UserRowTest {

    private static final UUID TITULAIRE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static UserRow user(String typeCompte, UUID accountHolderId) {
        return new UserRow(UUID.randomUUID(), "client@example.cm", "hash", "CLIENT_PP",
                "MBALLA", "Jean", "ACTIF", "CT-1", "CE-1", 5_000L, "NON_QUALIFIE",
                typeCompte, "+237690000000", false, 0, (OffsetDateTime) null, accountHolderId);
    }

    @Test
    void titulaire_d_un_compte_joint() {
        assertThat(user("JOINT", null).compteJoint()).isTrue();
    }

    @Test
    void titulaire_d_un_compte_indivis() {
        assertThat(user("INDIVIS", null).compteJoint()).isTrue();
    }

    /**
     * Le co-signataire ne porte AUCUN {@code type_compte} : son appartenance au
     * compte joint passe uniquement par {@code account_holder_id}. C'est pourtant
     * lui que l'on sollicite pour valider les ordres — il doit voir le menu.
     */
    @Test
    void cosignataire_sans_type_de_compte_participe_bien_a_un_compte_joint() {
        assertThat(user(null, TITULAIRE).compteJoint()).isTrue();
    }

    @Test
    void compte_individuel_n_est_pas_un_compte_joint() {
        assertThat(user("INDIVIDUEL", null).compteJoint()).isFalse();
    }

    @Test
    void type_de_compte_absent_et_sans_titulaire_n_est_pas_un_compte_joint() {
        assertThat(user(null, null).compteJoint()).isFalse();
    }

    @Test
    void demembre_n_est_pas_un_compte_joint() {
        assertThat(user("DEMEMBRE", null).compteJoint()).isFalse();
    }

    // ─── Projection vers UserProfile ──────────────────────────────────────────

    @Test
    void toProfile_reporte_le_drapeau_compte_joint_et_masque_l_empreinte() {
        UserProfile p = user("JOINT", null).toProfile();

        assertThat(p.compteJoint()).isTrue();
        assertThat(p.email()).isEqualTo("client@example.cm");
        assertThat(p.solde()).isEqualTo(5_000L);
    }

    /** Le solde disparait pour un client, mais le drapeau compteJoint reste. */
    @Test
    void withoutBalance_efface_le_solde_sans_perdre_le_drapeau() {
        UserProfile p = user(null, TITULAIRE).toProfile().withoutBalance();

        assertThat(p.solde()).isNull();
        assertThat(p.compteJoint()).isTrue();
        assertThat(p.compteTitres()).isEqualTo("CT-1");
    }
}
