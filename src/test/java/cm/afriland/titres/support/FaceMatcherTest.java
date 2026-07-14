package cm.afriland.titres.support;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comparaison faciale 1:1 — la DECISION est prise ici, cote serveur.
 * Un client modifie ne peut pas s'auto-declarer « reconnu ».
 */
class FaceMatcherTest {

    /** Descripteur constant de dimension 128. */
    private static List<Double> descripteur(double valeur) {
        List<Double> v = new ArrayList<>(FaceMatcher.DIMENSION);
        for (int i = 0; i < FaceMatcher.DIMENSION; i++) v.add(valeur);
        return v;
    }

    // ─── encode / decode ────────────────────────────────────────────────────

    @Test
    void encode_refuse_un_descripteur_absent_ou_de_mauvaise_dimension() {
        assertThat(FaceMatcher.encode(null)).isNull();
        assertThat(FaceMatcher.encode(List.of(0.1, 0.2))).isNull();
    }

    @Test
    void encode_puis_decode_restitue_le_descripteur() {
        String json = FaceMatcher.encode(descripteur(0.25));
        double[] out = FaceMatcher.decode(json);
        assertThat(out).hasSize(FaceMatcher.DIMENSION);
        assertThat(out[0]).isEqualTo(0.25);
    }

    @Test
    void decode_tolere_les_entrees_invalides() {
        assertThat(FaceMatcher.decode(null)).isNull();
        assertThat(FaceMatcher.decode("")).isNull();
        assertThat(FaceMatcher.decode("pas-du-json")).isNull();
        assertThat(FaceMatcher.decode("[]")).isNull();
        assertThat(FaceMatcher.decode("[1,2,3]")).isNull();          // mauvaise dimension
        assertThat(FaceMatcher.decode("[" + "a,".repeat(127) + "a]")).isNull(); // non numerique
    }

    // ─── distance ───────────────────────────────────────────────────────────

    @Test
    void distance_nulle_entre_deux_descripteurs_identiques() {
        double[] a = FaceMatcher.decode(FaceMatcher.encode(descripteur(0.5)));
        assertThat(FaceMatcher.distanceEuclidienne(a, a)).isZero();
    }

    @Test
    void distance_croit_avec_l_ecart() {
        double[] a = FaceMatcher.decode(FaceMatcher.encode(descripteur(0.0)));
        double[] proche = FaceMatcher.decode(FaceMatcher.encode(descripteur(0.01)));
        double[] loin = FaceMatcher.decode(FaceMatcher.encode(descripteur(0.5)));
        assertThat(FaceMatcher.distanceEuclidienne(a, proche))
                .isLessThan(FaceMatcher.distanceEuclidienne(a, loin));
    }

    // ─── comparer ───────────────────────────────────────────────────────────

    @Test
    void meme_visage_correspond() {
        String reference = FaceMatcher.encode(descripteur(0.1));
        // Ecart minime : 128 * (0.001)^2 -> distance ~0.011, tres en deca du seuil.
        FaceMatcher.Resultat r = FaceMatcher.comparer(reference, descripteur(0.101));

        assertThat(r.matched()).isTrue();
        assertThat(r.status()).isEqualTo(FaceMatcher.CORRESPOND);
        assertThat(r.distance()).isLessThan(FaceMatcher.SEUIL);
    }

    @Test
    void visage_different_ne_correspond_pas() {
        String reference = FaceMatcher.encode(descripteur(0.0));
        // Ecart de 0.1 sur 128 dimensions -> distance ~1.13, au-dela du seuil 0.6.
        FaceMatcher.Resultat r = FaceMatcher.comparer(reference, descripteur(0.1));

        assertThat(r.matched()).isFalse();
        assertThat(r.status()).isEqualTo(FaceMatcher.DIFFERENT);
        assertThat(r.distance()).isGreaterThan(FaceMatcher.SEUIL);
    }

    @Test
    void sans_empreinte_de_reference_le_resultat_est_non_comparable() {
        FaceMatcher.Resultat r = FaceMatcher.comparer(null, descripteur(0.1));

        assertThat(r.status()).isEqualTo(FaceMatcher.NON_COMPARABLE);
        assertThat(r.matched()).isFalse();
        assertThat(r.distance()).isNull();
    }

    @Test
    void sans_empreinte_du_jour_le_resultat_est_non_comparable() {
        String reference = FaceMatcher.encode(descripteur(0.1));

        assertThat(FaceMatcher.comparer(reference, null).status())
                .isEqualTo(FaceMatcher.NON_COMPARABLE);
        // Descripteur de mauvaise dimension : incomparable, jamais « correspond ».
        assertThat(FaceMatcher.comparer(reference, List.of(0.1, 0.2)).status())
                .isEqualTo(FaceMatcher.NON_COMPARABLE);
    }

    @Test
    void le_seuil_de_reference_est_celui_du_modele() {
        assertThat(FaceMatcher.SEUIL).isEqualTo(0.6);
        assertThat(FaceMatcher.DIMENSION).isEqualTo(128);
    }
}
