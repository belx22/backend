package cm.afriland.titres.notif;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CredentialDeliveryTest {

    @Mock EmailService email;
    @InjectMocks CredentialDelivery delivery;
    @Captor ArgumentCaptor<String> html;

    // ── Masquage du numero de compte ─────────────────────────────────────────

    @Test
    void maskCompte_garde_les_5_premiers_et_3_derniers() {
        assertThat(CredentialDelivery.maskCompte("CCEICMCXX007")).isEqualTo("CCEICxxxx007");
        assertThat(CredentialDelivery.maskCompte("10005000905601009000048"))
                .isEqualTo("10005" + "x".repeat(15) + "048");
    }

    @Test
    void maskCompte_numero_court_ou_null_inchange() {
        assertThat(CredentialDelivery.maskCompte("12345678")).isEqualTo("12345678");
        assertThat(CredentialDelivery.maskCompte(null)).isEmpty();
    }

    // ── Salutation selon le type + compte masque dans l'e-mail ───────────────

    @Test
    void email_personne_physique_bonjour_et_compte_masque() {
        delivery.sendInitialPassword("jean@x.cm", "690", "MBALLA Jean",
                "CCEICMCXX007", false, "pwd", false);

        verify(email).dispatchOne(anyString(), anyString(), html.capture());
        assertThat(html.getValue())
                .contains("Bonjour Mr/Mme MBALLA Jean")
                .contains("CCEICxxxx007")          // masque affiche
                .doesNotContain("CCEICMCXX007");   // jamais le numero complet
    }

    @Test
    void email_personne_morale_a_l_attention_de() {
        delivery.sendInitialPassword("dg@acme.cm", null, "ACME SA",
                "CCEICMCXX008", true, "pwd", false);

        verify(email).dispatchOne(anyString(), anyString(), html.capture());
        assertThat(html.getValue())
                .contains("À l'attention de ACME SA")
                .doesNotContain("Bonjour");
    }

    // ── E-mail « compte ouvert » a la validation du dossier ──────────────────

    @Test
    void compte_ouvert_pp_bonjour_et_compte_masque() {
        delivery.sendAccountOpened("jean@x.cm", "MBALLA Jean", "CCEICMCXX007", false);

        verify(email).dispatchOne(anyString(), anyString(), html.capture());
        assertThat(html.getValue())
                .contains("Bonjour Mr/Mme MBALLA Jean")
                .contains("ouverture de votre")
                .contains("CCEICxxxx007")
                .doesNotContain("CCEICMCXX007");
    }

    @Test
    void compte_ouvert_pm_a_l_attention_de() {
        delivery.sendAccountOpened("dg@acme.cm", "ACME SA", "CCEICMCXX008", true);

        verify(email).dispatchOne(anyString(), anyString(), html.capture());
        assertThat(html.getValue()).contains("À l'attention de ACME SA");
    }

    @Test
    void compte_ouvert_email_invalide_aucun_envoi() {
        delivery.sendAccountOpened("pas-un-email", "X", "CCEICMCXX009", false);
        verifyNoInteractions(email);
    }
}
