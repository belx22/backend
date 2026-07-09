package cm.afriland.titres.web;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.audit.AuditService;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.integration.AifClient;
import cm.afriland.titres.integration.AifUnavailableException;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.security.ClientIp;
import cm.afriland.titres.security.Permission;

/**
 * Module 9 — Consultation de solde via Amplitude/AIF (CSFT §INT-CB01 et §M3-S03).
 *
 * <p>Endpoint reserve au back-office (permission {@code ACCOUNT_BALANCE_READ}).
 * Les clients ne peuvent JAMAIS consulter leur solde via la plateforme : une
 * double protection est en place (matrice RBAC + verification explicite ici).
 * Chaque appel est trace dans le journal d'audit (utilisateur, compte, IP).</p>
 */
@RestController
@RequestMapping("/api/v1/account-balance")
public class AccountBalanceController {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Duree de cache du ping AIF — evite de marteler le serveur a chaque rendu. */
    private static final long PING_CACHE_MS = 15_000L;

    private final AifClient aif;
    private final AuditService audit;

    /** Dernier ping AIF — cache leger partage entre tous les acteurs BO. */
    private final java.util.concurrent.atomic.AtomicReference<AifClient.PingResult> cachedPing =
            new java.util.concurrent.atomic.AtomicReference<>();
    private volatile long cachedPingAt;

    public AccountBalanceController(AifClient aif, AuditService audit) {
        this.aif = aif;
        this.audit = audit;
    }

    /** Reponse sanitizee — le payload AIF brut n'est pas re-renvoye tel quel. */
    public record AccountBalance(
            String accountId,
            String branch,
            String currency,
            String account,
            String suffix,
            String status,
            String openingDate,
            String customerNumber,
            String customerName,
            Long instantBalance,
            Long accountingBalance) {
    }

    /** Statut de la consultation. UNAVAILABLE = AIF injoignable (degradation). */
    public enum BalanceStatus { OK, UNAVAILABLE }

    public record AccountBalanceListResponse(
            List<AccountBalance> accounts, int totalCount, BalanceStatus status) {
    }

    /** Statut de connexion AIF pour la barre superieure du back-office. */
    public record HealthResponse(boolean available, long latencyMs, String endpoint) {
    }

    /**
     * {@code GET /account-balance/health} — ping discret du serveur AIF.
     * Utilise par la barre superieure du back-office pour afficher l'etat de
     * connexion. Reservee aux acteurs back-office (les clients n'ont aucune
     * raison de connaitre l'etat du core banking).
     */
    @GetMapping("/health")
    public HealthResponse health(AuthUser user) {
        ApiException.ensure(!user.isClient(),
                "Statut Amplitude reserve au back-office.");
        long now = System.currentTimeMillis();
        AifClient.PingResult ping = cachedPing.get();
        if (ping == null || now - cachedPingAt > PING_CACHE_MS) {
            ping = aif.ping();
            cachedPing.set(ping);
            cachedPingAt = now;
        }
        return new HealthResponse(ping.available(), ping.latencyMs(), ping.endpoint());
    }

    /**
     * {@code GET /account-balance?accountNumber=...} — recherche par numero de compte.
     * Defense en profondeur : RBAC + refus explicite des roles client.
     */
    @GetMapping
    public AccountBalanceListResponse search(AuthUser user, ClientIp ip,
                                             @RequestParam String accountNumber) {
        ensureAuthorized(user);
        validateAccountNumber(accountNumber);

        try {
            String json = aif.getJson("/", Map.of("X-SBS-account", accountNumber));
            List<AccountBalance> accounts = parseList(json);
            audit.log(user.id().toString(), "CONSULTATION_SOLDE_AIF",
                    AuditService.SUCCES, accountNumber, ip.value());
            return new AccountBalanceListResponse(accounts, accounts.size(), BalanceStatus.OK);
        } catch (AifUnavailableException e) {
            // Degradation gracieuse : la plateforme reste fonctionnelle si AIF
            // est indisponible. L'echec est trace, mais la reponse reste 200
            // avec un statut UNAVAILABLE plutot qu'une erreur HTTP.
            audit.log(user.id().toString(), "CONSULTATION_SOLDE_AIF",
                    AuditService.ECHEC, accountNumber, ip.value());
            return new AccountBalanceListResponse(List.of(), 0, BalanceStatus.UNAVAILABLE);
        }
    }

    /**
     * {@code GET /account-balance/{branch}-{currency}-{account}-{suffix}} —
     * detail complet d'un compte donne.
     */
    @GetMapping("/{branch}-{currency}-{account}-{suffix}")
    public AccountBalance detail(AuthUser user, ClientIp ip,
                                 @org.springframework.web.bind.annotation.PathVariable String branch,
                                 @org.springframework.web.bind.annotation.PathVariable String currency,
                                 @org.springframework.web.bind.annotation.PathVariable String account,
                                 @org.springframework.web.bind.annotation.PathVariable String suffix) {
        ensureAuthorized(user);
        validateSegment("agence", branch);
        validateSegment("devise", currency);
        validateSegment("compte", account);
        validateSegment("suffixe", suffix);

        String accountId = branch + "-" + currency + "-" + account + "-" + suffix;
        try {
            String json = aif.getJson("/" + accountId, Map.of());
            AccountBalance balance = parseSingle(json);
            audit.log(user.id().toString(), "CONSULTATION_SOLDE_AIF",
                    AuditService.SUCCES, accountId, ip.value());
            return balance;
        } catch (AifUnavailableException e) {
            // Degradation gracieuse : on renvoie un detail vide plutot qu'une erreur HTTP.
            audit.log(user.id().toString(), "CONSULTATION_SOLDE_AIF",
                    AuditService.ECHEC, accountId, ip.value());
            return new AccountBalance(accountId, branch, currency, account, suffix,
                    null, null, null, null, null, null);
        }
    }

    // ───────────────────────────── Helpers ──────────────────────────────────

    /** Defense en profondeur : verifie RBAC + role explicite. */
    private static void ensureAuthorized(AuthUser user) {
        if (user.isClient()) {
            throw ApiException.forbidden(
                    "La consultation de solde est strictement reservee au back-office.");
        }
        user.require(Permission.ACCOUNT_BALANCE_READ);
    }

    /** Le numero de compte doit etre alphanumerique (eventuellement espaces). */
    private static void validateAccountNumber(String accountNumber) {
        // Garde null explicite (throw) avant tout dereferencement : evite tout
        // risque de NullPointerException si accountNumber est absent.
        if (accountNumber == null || accountNumber.length() < 3) {
            throw ApiException.badRequest("Numero de compte invalide (minimum 3 caracteres).");
        }
        ApiException.ensure(accountNumber.length() <= 40,
                "Numero de compte trop long.");
        ApiException.ensure(accountNumber.matches("[A-Za-z0-9 ._-]+"),
                "Numero de compte invalide.");
    }

    private static void validateSegment(String label, String value) {
        if (value == null || value.length() < 1 || value.length() > 20) {
            throw ApiException.badRequest("Segment " + label + " invalide.");
        }
        ApiException.ensure(value.matches("[A-Za-z0-9._-]+"),
                "Segment " + label + " invalide.");
    }

    private static List<AccountBalance> parseList(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode accounts = root.path("accounts");
            if (!accounts.isArray()) return List.of();
            List<AccountBalance> result = new java.util.ArrayList<>(accounts.size());
            for (JsonNode n : accounts) {
                result.add(toBalance(n));
            }
            return result;
        } catch (Exception e) {
            // Reponse AIF illisible : on degrade comme une indisponibilite.
            throw new AifUnavailableException(
                    "Reponse Amplitude (AIF) illisible : " + e.getMessage(), e);
        }
    }

    private static AccountBalance parseSingle(String json) {
        try {
            return toBalance(JSON.readTree(json));
        } catch (Exception e) {
            throw new AifUnavailableException(
                    "Reponse Amplitude (AIF) illisible : " + e.getMessage(), e);
        }
    }

    private static AccountBalance toBalance(JsonNode n) {
        JsonNode customer = n.path("customer");
        return new AccountBalance(
                text(n, "accountId"),
                text(n, "branch"),
                text(n, "currency"),
                text(n, "account"),
                text(n, "suffix"),
                text(n, "status"),
                text(n, "openingDate"),
                text(customer, "customerNumber"),
                text(customer, "customerDisplayedName"),
                longValue(n, "instantBalance"),
                longValue(n, "accountingBalance"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText(null);
    }

    private static Long longValue(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isNumber()) return v.asLong();
        try {
            return Long.parseLong(v.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
