package cm.afriland.titres.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Montant en toutes lettres (français) — porté par l'attestation de propriété. */
class AttestationPdfServiceTest {

    @Test
    void enLettres_couvre_les_regles_du_francais() {
        assertThat(AttestationPdfService.enLettres(0)).isEqualTo("zéro");
        assertThat(AttestationPdfService.enLettres(1)).isEqualTo("un");
        assertThat(AttestationPdfService.enLettres(21)).isEqualTo("vingt et un");
        assertThat(AttestationPdfService.enLettres(71)).isEqualTo("soixante et onze");
        assertThat(AttestationPdfService.enLettres(80)).isEqualTo("quatre-vingts");
        assertThat(AttestationPdfService.enLettres(81)).isEqualTo("quatre-vingt-un");
        assertThat(AttestationPdfService.enLettres(91)).isEqualTo("quatre-vingt-onze");
        assertThat(AttestationPdfService.enLettres(100)).isEqualTo("cent");
        assertThat(AttestationPdfService.enLettres(200)).isEqualTo("deux cents");
        assertThat(AttestationPdfService.enLettres(201)).isEqualTo("deux cent un");
        assertThat(AttestationPdfService.enLettres(1000)).isEqualTo("mille");
        assertThat(AttestationPdfService.enLettres(1_000_000)).isEqualTo("un million");
        assertThat(AttestationPdfService.enLettres(2_000_000)).isEqualTo("deux millions");
    }

    /** Le cas du document de référence : 100 000 000 → « cent millions ». */
    @Test
    void enLettres_cent_millions() {
        assertThat(AttestationPdfService.enLettres(100_000_000L)).isEqualTo("cent millions");
    }
}
