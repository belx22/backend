# -*- coding: utf-8 -*-
"""Test end-to-end ALIGNE sur le code actuel (workflow V10 + cookie HttpOnly).

Differences avec test_api.py (obsolete) :
  - /auth/refresh lit le refresh token dans le cookie HttpOnly `afb_rt` (pas le body).
  - Adjudication a 2 niveaux : l'agent PROPOSE via /status (l'ordre reste
    EN_ATTENTE_ADJUDICATION), le superviseur VALIDE via /validate-result.
  - RBAC V10 : USER_MANAGE et AUDIT_READ sont reserves a l'ADMIN.
  - /clients/dossiers renvoie une page (objet), pas une liste nue.
"""
import json
import time
import urllib.request
import urllib.error
import http.cookiejar

BASE = "http://localhost:8080"
results = []
SUF = str(int(time.time()))[-6:]

# CookieJar partage : capte le cookie HttpOnly afb_rt pose par /mfa/verify.
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with opener.open(r, timeout=15) as resp:
            raw = resp.read().decode()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def check(name, cond, detail=""):
    results.append(cond)
    mark = "PASS" if cond else "FAIL"
    line = "  [%s] %s" % (mark, name)
    if detail and not cond:
        line += "  -->  " + str(detail)[:300]
    print(line)


def login(email, pwd="Demo1234"):
    st, b = req("POST", "/api/v1/auth/login", {"email": email, "password": pwd})
    assert st == 200 and b.get("mfaRequired"), "login %s: %s %s" % (email, st, b)
    st, b = req("POST", "/api/v1/auth/mfa/verify",
                {"challengeId": b["challengeId"], "code": "123456"})
    assert st == 200 and b.get("accessToken"), "mfa %s: %s %s" % (email, st, b)
    return b["accessToken"], b["user"]


print("=" * 70)
print(" TESTS END-TO-END (V10) — BACKEND SPRING BOOT")
print("=" * 70)

# --- Sante & auth ---
st, b = req("GET", "/health")
check("GET /health", st == 200 and b.get("database") == "up", (st, b))

tok_client, u_client = login("jean.mballa@example.cm")
check("login + MFA client (CLIENT_PP)", u_client["role"] == "CLIENT_PP", u_client)
tok_agent, u_agent = login("agent@afriland.cm")
check("login + MFA agent (AGENT)", u_agent["role"] == "AGENT", u_agent)
tok_sup, u_sup = login("superviseur@afriland.cm")
check("login + MFA superviseur (SUPERVISEUR)", u_sup["role"] == "SUPERVISEUR", u_sup)
tok_admin, u_admin = login("admin@afriland.cm")
check("login + MFA admin (ADMIN)", u_admin["role"] == "ADMIN", u_admin)

# --- Refresh par cookie HttpOnly (la session client a pose afb_rt) ---
# On rejoue le login client pour reposer un cookie frais juste avant le refresh.
login("jean.mballa@example.cm")
st, b = req("POST", "/api/v1/auth/refresh")
check("refresh via cookie HttpOnly (rotation)", st == 200 and b.get("accessToken"), (st, b))

# --- Cycle emission ---
new_em = {
    "code": "BTA-CMR-V" + SUF, "isin": ("CV" + SUF + "0000")[:12],
    "libelle": "BTA Test V10 13 semaines", "nature": "BTA", "paysCode": "CMR",
    "dateEmission": "2026-06-01", "ouvertureSouscription": "2026-06-02T08:00:00Z",
    "fermetureSouscription": "2026-06-09T09:00:00Z", "dateEcheance": "2026-09-01",
    "dateReglement": "2026-06-10", "valeurNominaleUnitaire": 1000000,
    "montantGlobal": 5000000000, "tauxNominal": 0, "montantMinimum": 1000000,
    "modeAdjudication": "TAUX",
}
st, b = req("POST", "/api/v1/emissions", new_em, token=tok_agent)
check("agent cree une emission (BROUILLON)", st == 201 and b.get("status") == "BROUILLON", (st, b))
em_id = b.get("id") if b else None
st, b = req("POST", "/api/v1/emissions/%s/publish" % em_id, token=tok_sup)
check("superviseur publie l'emission (PUBLIE)", st == 200 and b.get("status") == "PUBLIE", (st, b))

# --- Ordre : soumission + validation agent ---
st, b = req("POST", "/api/v1/orders",
            {"emissionId": em_id, "volume": 3, "tauxSoumis": 5.5}, token=tok_client)
check("client soumet un ordre (SOUMIS)", st == 201 and b.get("status") == "SOUMIS", (st, b))
ord_id = b.get("id") if b else None
st, b = req("POST", "/api/v1/orders/%s/validate" % ord_id, token=tok_agent)
check("agent valide l'ordre (EN_ATTENTE_ADJUDICATION)",
      st == 200 and b.get("status") == "EN_ATTENTE_ADJUDICATION", (st, b))

# --- Adjudication a 2 niveaux (V10) ---
# 1) L'agent PROPOSE le resultat : l'ordre reste EN_ATTENTE_ADJUDICATION.
st, b = req("POST", "/api/v1/orders/%s/status" % ord_id,
            {"status": "TOTALEMENT_RETENU", "montantAdjuge": 3000000,
             "volumeAlloue": 3, "tauxAdjuge": 5.5}, token=tok_agent)
check("agent PROPOSE l'adjudication (statut reste EN_ATTENTE)",
      st == 200 and b.get("status") == "EN_ATTENTE_ADJUDICATION"
      and b.get("resultatPropose") == "TOTALEMENT_RETENU", (st, b))

# 2) Le client ne voit PAS encore le resultat propose (scrubForClient).
st, b = req("GET", "/api/v1/orders/%s" % ord_id, token=tok_client)
check("client ne voit pas le resultat tant qu'il n'est pas valide",
      st == 200 and b.get("resultatPropose") is None and b.get("montantAdjuge") is None, (st, b))

# 3) L'agent ne peut PAS valider sa propre proposition (ORDER_RESULT_VALIDATE).
st, b = req("POST", "/api/v1/orders/%s/validate-result" % ord_id, token=tok_agent)
check("agent ne peut PAS valider l'adjudication -> 403", st == 403, (st, b))

# 4) Le superviseur VALIDE : l'ordre prend son statut final.
st, b = req("POST", "/api/v1/orders/%s/validate-result" % ord_id, token=tok_sup)
check("superviseur VALIDE l'adjudication (TOTALEMENT_RETENU)",
      st == 200 and b.get("status") == "TOTALEMENT_RETENU", (st, b))

# 5) Ordre cloture : toute nouvelle transition est refusee.
st, b = req("POST", "/api/v1/orders/%s/status" % ord_id,
            {"status": "SOUMIS"}, token=tok_agent)
check("ordre cloture : changement de statut refuse -> 400", st == 400, (st, b))

# 6) Le client voit desormais le resultat valide.
st, b = req("GET", "/api/v1/orders/%s" % ord_id, token=tok_client)
check("client voit le resultat une fois valide",
      st == 200 and b.get("status") == "TOTALEMENT_RETENU"
      and b.get("montantAdjuge") == 3000000, (st, b))

# --- Portefeuille ---
st, b = req("GET", "/api/v1/portfolio", token=tok_client)
check("portefeuille reflete l'ordre adjuge",
      st == 200 and "positions" in b and b.get("valeurTotale", 0) >= 3000000,
      b.get("valeurTotale") if b else None)

# --- RBAC V10 : USER_MANAGE = ADMIN uniquement ---
st, b = req("GET", "/api/v1/users", token=tok_admin)
check("admin liste les utilisateurs (USER_MANAGE)", st == 200 and b.get("total", 0) >= 5, (st, b))
st, b = req("GET", "/api/v1/users", token=tok_agent)
check("agent ne peut PAS lister les utilisateurs -> 403 (V10)", st == 403, st)

# --- RBAC V10 : AUDIT_READ = ADMIN uniquement ---
st, b = req("GET", "/api/v1/audit", token=tok_admin)
check("admin lit le journal d'audit (AUDIT_READ)", st == 200 and b.get("total", 0) >= 1, st)
st, b = req("GET", "/api/v1/audit", token=tok_sup)
check("superviseur ne peut PAS lire l'audit -> 403 (V10)", st == 403, st)
st, b = req("GET", "/api/v1/audit", token=tok_admin)
if b and b.get("data"):
    check("audit resout le nom des acteurs",
          any(e.get("utilisateurNom") for e in b["data"]), "aucun nom resolu")

# --- Clients (CLIENT_MANAGE = agent/superviseur/admin) ---
st, b = req("GET", "/api/v1/clients/dossiers", token=tok_agent)
check("agent liste les dossiers clients (page)",
      st == 200 and isinstance(b, dict) and "data" in b, (st, type(b).__name__))

# --- Permissions ---
st, b = req("GET", "/api/v1/permissions", token=tok_client)
check("GET /permissions (tout authentifie)", st == 200 and "ADMIN" in b, st)
st, b = req("PUT", "/api/v1/permissions",
            {"role": "AGENT", "permission": "CONFIG_MARCHE", "granted": True}, token=tok_sup)
check("superviseur ne peut PAS modifier la matrice RBAC -> 403", st == 403, st)
st, b = req("PUT", "/api/v1/permissions",
            {"role": "AGENT", "permission": "CONFIG_MARCHE", "granted": True}, token=tok_admin)
check("admin modifie la matrice RBAC", st == 200 and "CONFIG_MARCHE" in b.get("AGENT", []), (st, b))
st, b = req("PUT", "/api/v1/permissions",
            {"role": "AGENT", "permission": "CONFIG_MARCHE", "granted": False}, token=tok_admin)
check("admin restaure la matrice RBAC", st == 200 and "CONFIG_MARCHE" not in b.get("AGENT", []), (st, b))

# --- Suppression emission brouillon (ADMIN) ---
em2 = dict(new_em)
em2["code"] = "BTA-CMR-W" + SUF
em2["isin"] = ("CW" + SUF + "0000")[:12]
st, b = req("POST", "/api/v1/emissions", em2, token=tok_agent)
em2_id = b.get("id") if b else None
st, b = req("DELETE", "/api/v1/emissions/%s" % em2_id, token=tok_admin)
check("admin supprime une emission brouillon -> 204", st == 204, st)

print("=" * 70)
total = len(results)
ok = sum(1 for r in results if r)
print(" RESULTAT : %d / %d tests reussis" % (ok, total))
print("=" * 70)
