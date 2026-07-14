package cm.afriland.titres.web;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.integration.AifClient;
import cm.afriland.titres.integration.AifUnavailableException;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Consultation de solde Amplitude/AIF : autorisation (double barriere RBAC +
 * refus explicite des clients), validation des parametres, projection du JSON
 * AIF et degradation gracieuse quand le core banking est injoignable.
 */
@ExtendWith(MockitoExtension.class)
class AccountBalanceControllerTest {

    @Mock AifClient aif;
    @Mock AuditService audit;
    @InjectMocks AccountBalanceController controller;

    private static final ClientIp IP = new ClientIp("10.0.0.7");
    private static final AuthUser AGENT =
            new AuthUser(UUID.randomUUID(), "agent@afriland.cm", "AGENT", false);
    private static final AuthUser CLIENT =
            new AuthUser(UUID.randomUUID(), "client@example.cm", "CLIENT_PP", false);

    private static final String JSON_LISTE = """
            {"accounts":[
              {"accountId":"00001-XAF-1234567-01","branch":"00001","currency":"XAF",
               "account":"1234567","suffix":"01","status":"ACTIF","openingDate":"2020-01-15",
               "customer":{"customerNumber":"C-42","customerDisplayedName":"SARL EXEMPLE"},
               "instantBalance":1500000,"accountingBalance":1450000}
            ],"totalCount":1}""";

    // ─────────────────────────── Autorisation ───────────────────────────────

    /** Un client ne doit JAMAIS consulter un solde, meme s'il porte la permission. */
    @Test
    void search_refuse_les_clients() {
        assertThatThrownBy(() -> controller.search(CLIENT, IP, "1234567"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reservee au back-office");

        verifyNoInteractions(aif);
        verifyNoInteractions(audit);
    }

    @Test
    void detail_refuse_les_clients() {
        assertThatThrownBy(() -> controller.detail(CLIENT, IP, "00001", "XAF", "1234567", "01"))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(aif);
    }

    @Test
    void health_refuse_les_clients() {
        assertThatThrownBy(() -> controller.health(CLIENT))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reserve au back-office");
    }

    // ───────────────────────── Validation des entrees ───────────────────────

    @Test
    void search_rejette_un_numero_de_compte_trop_court() {
        assertThatThrownBy(() -> controller.search(AGENT, IP, "12"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("minimum 3 caracteres");
    }

    @Test
    void search_rejette_un_numero_de_compte_nul() {
        assertThatThrownBy(() -> controller.search(AGENT, IP, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("minimum 3 caracteres");
    }

    @Test
    void search_rejette_un_numero_de_compte_trop_long() {
        assertThatThrownBy(() -> controller.search(AGENT, IP, "1".repeat(41)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("trop long");
    }

    /** Une injection potentielle (caracteres hors alphabet) doit etre rejetee avant l'appel AIF. */
    @Test
    void search_rejette_les_caracteres_interdits() {
        assertThatThrownBy(() -> controller.search(AGENT, IP, "123'; DROP--"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("invalide");

        verifyNoInteractions(aif);
    }

    @Test
    void detail_rejette_un_segment_vide() {
        assertThatThrownBy(() -> controller.detail(AGENT, IP, "", "XAF", "1234567", "01"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("agence");
    }

    @Test
    void detail_rejette_un_segment_trop_long() {
        assertThatThrownBy(() -> controller.detail(AGENT, IP, "00001", "X".repeat(21), "1234567", "01"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("devise");
    }

    @Test
    void detail_rejette_un_segment_aux_caracteres_interdits() {
        assertThatThrownBy(() -> controller.detail(AGENT, IP, "00001", "XAF", "12/34", "01"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("compte");
    }

    // ──────────────────────────── Cas nominaux ──────────────────────────────

    @Test
    void search_projette_la_reponse_aif_et_trace_le_succes() {
        when(aif.getJson(eq("/"), any())).thenReturn(JSON_LISTE);

        var reponse = controller.search(AGENT, IP, "1234567");

        assertThat(reponse.status()).isEqualTo(AccountBalanceController.BalanceStatus.OK);
        assertThat(reponse.totalCount()).isEqualTo(1);
        var compte = reponse.accounts().get(0);
        assertThat(compte.accountId()).isEqualTo("00001-XAF-1234567-01");
        assertThat(compte.customerName()).isEqualTo("SARL EXEMPLE");
        assertThat(compte.instantBalance()).isEqualTo(1_500_000L);

        verify(audit).log(AGENT.id().toString(), "CONSULTATION_SOLDE_AIF",
                AuditService.SUCCES, "1234567", "10.0.0.7");
    }

    /** Le numero de compte doit voyager dans l'en-tete X-SBS-account, pas dans l'URL. */
    @Test
    void search_transmet_le_compte_dans_l_en_tete_x_sbs_account() {
        when(aif.getJson("/", Map.of("X-SBS-account", "1234567"))).thenReturn(JSON_LISTE);

        assertThat(controller.search(AGENT, IP, "1234567").totalCount()).isEqualTo(1);
    }

    @Test
    void detail_projette_un_compte_unique() {
        String unCompte = """
                {"accountId":"00001-XAF-1234567-01","branch":"00001","currency":"XAF",
                 "account":"1234567","suffix":"01","status":"ACTIF",
                 "customer":{"customerNumber":"C-42","customerDisplayedName":"SARL EXEMPLE"},
                 "instantBalance":"2500000"}""";
        when(aif.getJson(eq("/00001-XAF-1234567-01"), any())).thenReturn(unCompte);

        var compte = controller.detail(AGENT, IP, "00001", "XAF", "1234567", "01");

        assertThat(compte.status()).isEqualTo("ACTIF");
        // Solde transmis en chaine par AIF : il doit tout de meme etre converti.
        assertThat(compte.instantBalance()).isEqualTo(2_500_000L);
        // Champ absent du payload : null, pas 0.
        assertThat(compte.accountingBalance()).isNull();
        assertThat(compte.openingDate()).isNull();
    }

    /** Un solde non numerique ne doit pas faire echouer la consultation. */
    @Test
    void detail_tolere_un_solde_illisible() {
        when(aif.getJson(anyString(), any()))
                .thenReturn("{\"accountId\":\"x\",\"instantBalance\":\"abc\"}");

        assertThat(controller.detail(AGENT, IP, "00001", "XAF", "1234567", "01").instantBalance())
                .isNull();
    }

    /** « accounts » absent ou non tableau : liste vide, pas d'exception. */
    @Test
    void search_renvoie_une_liste_vide_si_le_payload_n_a_pas_de_tableau_accounts() {
        when(aif.getJson(anyString(), any())).thenReturn("{\"accounts\":{},\"totalCount\":0}");

        var reponse = controller.search(AGENT, IP, "1234567");

        assertThat(reponse.accounts()).isEmpty();
        assertThat(reponse.status()).isEqualTo(AccountBalanceController.BalanceStatus.OK);
    }

    // ─────────────────────── Degradation gracieuse ──────────────────────────

    /** AIF injoignable : reponse 200 avec statut UNAVAILABLE, et l'echec est trace. */
    @Test
    void search_degrade_gracieusement_quand_aif_est_injoignable() {
        when(aif.getJson(anyString(), any())).thenThrow(new AifUnavailableException("AIF injoignable"));

        var reponse = controller.search(AGENT, IP, "1234567");

        assertThat(reponse.status()).isEqualTo(AccountBalanceController.BalanceStatus.UNAVAILABLE);
        assertThat(reponse.accounts()).isEmpty();
        assertThat(reponse.totalCount()).isZero();
        verify(audit).log(AGENT.id().toString(), "CONSULTATION_SOLDE_AIF",
                AuditService.ECHEC, "1234567", "10.0.0.7");
    }

    /** Un payload AIF illisible est traite comme une indisponibilite, pas comme une erreur 500. */
    @Test
    void search_degrade_gracieusement_quand_le_payload_est_illisible() {
        when(aif.getJson(anyString(), any())).thenReturn("ceci n'est pas du JSON");

        var reponse = controller.search(AGENT, IP, "1234567");

        assertThat(reponse.status()).isEqualTo(AccountBalanceController.BalanceStatus.UNAVAILABLE);
        verify(audit).log(anyString(), anyString(), eq(AuditService.ECHEC), anyString(), anyString());
    }

    @Test
    void detail_renvoie_un_compte_vide_quand_aif_est_injoignable() {
        when(aif.getJson(anyString(), any())).thenThrow(new AifUnavailableException("AIF injoignable"));

        var compte = controller.detail(AGENT, IP, "00001", "XAF", "1234567", "01");

        assertThat(compte.accountId()).isEqualTo("00001-XAF-1234567-01");
        assertThat(compte.branch()).isEqualTo("00001");
        assertThat(compte.status()).isNull();
        assertThat(compte.instantBalance()).isNull();
        verify(audit).log(anyString(), anyString(), eq(AuditService.ECHEC),
                eq("00001-XAF-1234567-01"), anyString());
    }

    // ──────────────────────────── Ping / health ─────────────────────────────

    @Test
    void health_renvoie_le_ping_aif() {
        when(aif.ping()).thenReturn(new AifClient.PingResult(true, 12L, "https://aif.intra"));

        var sante = controller.health(AGENT);

        assertThat(sante.available()).isTrue();
        assertThat(sante.latencyMs()).isEqualTo(12L);
        assertThat(sante.endpoint()).isEqualTo("https://aif.intra");
    }

    /** Le ping est mis en cache : deux appels rapproches ne sollicitent AIF qu'une fois. */
    @Test
    void health_met_le_ping_en_cache() {
        when(aif.ping()).thenReturn(new AifClient.PingResult(true, 12L, "https://aif.intra"));

        controller.health(AGENT);
        controller.health(AGENT);

        verify(aif).ping();
    }

    /** Le refus d'un client ne doit jamais declencher de ping. */
    @Test
    void health_ne_ping_pas_pour_un_client() {
        assertThatThrownBy(() -> controller.health(CLIENT)).isInstanceOf(ApiException.class);

        verify(aif, never()).ping();
    }
}
