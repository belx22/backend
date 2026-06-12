# -*- coding: utf-8 -*-
"""Test end-to-end du backend Spring Boot — Plateforme Valeurs du Tresor CEMAC."""
import json
import time
import urllib.request
import urllib.error

BASE = "http://localhost:8080"
results = []

# Suffixe unique par execution -> le test est re-executable (pas de doublon ISIN).
SUF = str(int(time.time()))[-6:]


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=15) as resp:
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


print("=" * 70)
print(" TESTS END-TO-END — BACKEND SPRING BOOT")
print("=" * 70)

# --- 1. Sante ---
st, b = req("GET", "/health")
check("GET /health", st == 200 and b and b.get("database") == "up", (st, b))

# --- 2. Authentification (rate-limit : 10 appels max / min) ---
st, b = req("POST", "/api/v1/auth/login",
            {"email": "jean.mballa@example.cm", "password": "MAUVAIS_MDP"})
check("login mot de passe errone -> 401", st == 401, st)


def login(email, pwd="Demo1234"):
    st, b = req("POST", "/api/v1/auth/login", {"email": email, "password": pwd})
    assert st == 200 and b.get("mfaRequired"), "login %s: %s %s" % (email, st, b)
    st, b = req("POST", "/api/v1/auth/mfa/verify",
                {"challengeId": b["challengeId"], "code": "123456"})
    assert st == 200 and b.get("accessToken"), "mfa %s: %s %s" % (email, st, b)
    return b["accessToken"], b["refreshToken"], b["user"]


tok_client, rt_client, u_client = login("jean.mballa@example.cm")
check("login + MFA client", u_client["role"] == "CLIENT_PP", u_client)

tok_agent, _, u_agent = login("agent@afriland.cm")
check("login + MFA agent", u_agent["role"] == "AGENT", u_agent)

tok_sup, _, u_sup = login("superviseur@afriland.cm")
check("login + MFA superviseur", u_sup["role"] == "SUPERVISEUR", u_sup)

tok_admin, _, u_admin = login("admin@afriland.cm")
check("login + MFA admin", u_admin["role"] == "ADMIN", u_admin)

st, b = req("POST", "/api/v1/auth/refresh", {"refreshToken": rt_client})
check("refresh token (rotation)", st == 200 and b.get("accessToken"), st)

# --- 3. Profil ---
st, b = req("GET", "/api/v1/auth/me", token=tok_client)
check("GET /auth/me", st == 200 and b.get("email") == "jean.mballa@example.cm", (st, b))

st, b = req("GET", "/api/v1/auth/me")
check("GET /auth/me sans jeton -> 401", st == 401, st)

# --- 4. Emissions ---
st, b = req("GET", "/api/v1/emissions", token=tok_client)
ems = b.get("data", []) if b else []
check("liste emissions (client) repond", st == 200 and "total" in b, (st, b))
check("client ne voit que les emissions PUBLIE",
      all(e["status"] == "PUBLIE" for e in ems), [e["status"] for e in ems])

st, b = req("GET", "/api/v1/emissions", token=tok_agent)
check("agent peut lister toutes les emissions", st == 200 and "total" in b,
      (st, b.get("total") if b else None))

# --- 5. Cycle de vie emission + ordre ---
new_em = {
    "code": "BTA-CMR-T" + SUF, "isin": ("CT" + SUF + "0000")[:12],
    "libelle": "BTA Test automatise 13 semaines", "nature": "BTA", "paysCode": "CMR",
    "dateEmission": "2026-06-01", "ouvertureSouscription": "2026-06-02T08:00:00Z",
    "fermetureSouscription": "2026-06-09T09:00:00Z", "dateEcheance": "2026-09-01",
    "dateReglement": "2026-06-10", "valeurNominaleUnitaire": 1000000,
    "montantGlobal": 5000000000, "tauxNominal": 0, "montantMinimum": 1000000,
    "modeAdjudication": "TAUX"
}
st, b = req("POST", "/api/v1/emissions", new_em, token=tok_agent)
check("agent cree une emission (BROUILLON)", st == 201 and b.get("status") == "BROUILLON", (st, b))
em_id = b.get("id") if b else None

st, b = req("POST", "/api/v1/emissions", new_em, token=tok_client)
check("client ne peut PAS creer d'emission -> 403", st == 403, st)

st, b = req("POST", "/api/v1/emissions/%s/publish" % em_id, token=tok_sup)
check("superviseur publie l'emission (PUBLIE)", st == 200 and b.get("status") == "PUBLIE", (st, b))

st, b = req("POST", "/api/v1/orders",
            {"emissionId": em_id, "volume": 3, "tauxSoumis": 5.5}, token=tok_client)
check("client soumet un ordre (SOUMIS)", st == 201 and b.get("status") == "SOUMIS", (st, b))
ord_id = b.get("id") if b else None

# Tentative de double souscription a la meme emission -> doit etre refusee.
st, b = req("POST", "/api/v1/orders",
            {"emissionId": em_id, "volume": 1, "tauxSoumis": 5.5}, token=tok_client)
check("client ne peut pas souscrire 2 fois a la meme emission -> 400", st == 400, (st, b))

st, b = req("GET", "/api/v1/orders", token=tok_client)
check("liste ordres (client)", st == 200 and b.get("total", 0) >= 1, st)

st, b = req("POST", "/api/v1/orders/%s/validate" % ord_id, token=tok_agent)
check("agent valide l'ordre (EN_ATTENTE_ADJUDICATION)",
      st == 200 and b.get("status") == "EN_ATTENTE_ADJUDICATION", (st, b))

st, b = req("POST", "/api/v1/orders/%s/status" % ord_id,
            {"status": "TOTALEMENT_RETENU", "montantAdjuge": 3000000,
             "volumeAlloue": 3, "tauxAdjuge": 5.5}, token=tok_agent)
check("agent saisit le resultat d'adjudication", st == 200 and b.get("status") == "TOTALEMENT_RETENU",
      (st, b))

st, b = req("POST", "/api/v1/orders/%s/status" % ord_id,
            {"status": "SOUMIS"}, token=tok_agent)
check("ordre cloture : changement de statut refuse -> 400", st == 400, (st, b))

# --- 6. Portefeuille (l'ordre adjuge doit y apparaitre) ---
st, b = req("GET", "/api/v1/portfolio", token=tok_client)
check("portefeuille client", st == 200 and "positions" in b, (st, b))
check("portefeuille reflete l'ordre adjuge",
      b and b.get("valeurTotale", 0) >= 3000000, b.get("valeurTotale") if b else None)

# --- 7. Notifications ---
st, b = req("GET", "/api/v1/notifications", token=tok_client)
check("liste notifications client", st == 200 and b.get("total", 0) >= 1, st)
st, b = req("GET", "/api/v1/notifications/unread-count", token=tok_client)
check("compteur non-lues", st == 200 and "count" in b, (st, b))

# --- 8. Utilisateurs & clients (RBAC) ---
st, b = req("GET", "/api/v1/users", token=tok_agent)
check("agent liste les utilisateurs (USER_MANAGE)", st == 200 and b.get("total", 0) >= 5, st)
st, b = req("GET", "/api/v1/users", token=tok_client)
check("client ne peut PAS lister les utilisateurs -> 403", st == 403, st)

st, b = req("GET", "/api/v1/clients/dossiers", token=tok_agent)
check("liste dossiers clients accessible (CLIENT_MANAGE)",
      st == 200 and isinstance(b, list), (st, type(b).__name__))

# Seed minimal : aucun dossier n'est preinsere -> /clients/me renvoie 404.
st, b = req("GET", "/api/v1/clients/me", token=tok_client)
check("/clients/me sans dossier renvoie 404 (seed minimal)", st == 404, st)

# --- 9. Audit ---
st, b = req("GET", "/api/v1/audit", token=tok_sup)
check("journal d'audit (superviseur AUDIT_READ)", st == 200 and b.get("total", 0) >= 1, st)
st, b = req("GET", "/api/v1/audit", token=tok_agent)
check("agent ne peut PAS lire l'audit -> 403", st == 403, st)
st, b = req("GET", "/api/v1/audit", token=tok_sup)
if b and b.get("data"):
    has_name = any(e.get("utilisateurNom") for e in b["data"])
    check("audit resout le nom des acteurs", has_name, "aucun nom resolu")

# --- 10. Permissions RBAC ---
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
check("admin restaure la matrice RBAC",
      st == 200 and "CONFIG_MARCHE" not in b.get("AGENT", []), (st, b))

# --- 11. Suppression emission (admin, brouillon) ---
em2 = dict(new_em)
em2["code"] = "BTA-CMR-D" + SUF
em2["isin"] = ("CD" + SUF + "0000")[:12]
st, b = req("POST", "/api/v1/emissions", em2, token=tok_agent)
em2_id = b.get("id") if b else None
st, b = req("DELETE", "/api/v1/emissions/%s" % em2_id, token=tok_admin)
check("admin supprime une emission brouillon -> 204", st == 204, st)

# --- Bilan ---
print("=" * 70)
total = len(results)
ok = sum(1 for r in results if r)
print(" RESULTAT : %d / %d tests reussis" % (ok, total))
print("=" * 70)
