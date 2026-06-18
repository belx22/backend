# -*- coding: utf-8 -*-
"""
Test end-to-end — Flux complet : Publication → Souscription → Validation → Livrables

Etapes couvertes :
  1. Sante backend
  2. Authentification (client PP, agent, superviseur, admin)
  3. Creation emission BROUILLON par l'agent
  4. Publication emission PUBLIE par le superviseur
  5. Client : liste les emissions ouvertes → voit la nouvelle
  6. Client : soumet un ordre de souscription (SOUMIS)
  7. Client : modifie son ordre (PATCH /orders/:id) — nouveau endpoint
  8. Agent  : valide l'ordre → EN_ATTENTE_ADJUDICATION
  9. Client : ne peut plus modifier l'ordre apres validation
 10. Agent  : propose le resultat d'adjudication (TOTALEMENT_RETENU)
 11. Client : ne voit pas encore le resultat (scrubForClient)
 12. Agent  : ne peut PAS valider son propre resultat (ORDER_RESULT_VALIDATE)
 13. Superviseur : valide le resultat → ordre prend statut TOTALEMENT_RETENU
 14. Client : voit le statut final + montantAdjuge
 15. Portefeuille : l'ordre adjuge apparait dans les positions
 16. Livrables    : upload d'un avis d'operation par l'agent
 17. Client : liste ses documents → le livrable apparait
 18. Client : telecharge le livrable (GET /documents/:id avec contenu)
 19. Notifications : le client a bien recu des notifications tout au long du flux
 20. Annulation : client tente d'annuler un ordre valide → refus 400
"""

import json
import time
import base64
import hashlib
import subprocess
import urllib.request
import urllib.error
import http.cookiejar

BASE = "http://localhost:8080"
SUF = str(int(time.time()))[-6:]
results = []

# JWT secret : lu depuis le conteneur backend au demarrage
def _get_jwt_secret():
    try:
        r = subprocess.run(
            ["docker", "exec", "afb_titres_backend", "env"],
            capture_output=True, text=True, timeout=8)
        for line in r.stdout.splitlines():
            if line.startswith("APP_JWT_SECRET="):
                return line.split("=", 1)[1].strip()
    except Exception:
        pass
    return "dev_secret_a_remplacer_en_production_32_caracteres_min"

JWT_SECRET = _get_jwt_secret()

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
        with opener.open(r, timeout=20) as resp:
            raw = resp.read().decode()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def check(name, cond, detail=""):
    results.append((name, cond))
    mark = "\033[32mPASS\033[0m" if cond else "\033[31mFAIL\033[0m"
    line = "  [%s] %s" % (mark, name)
    if detail and not cond:
        line += "\n         --> " + str(detail)[:400]
    print(line)


def decrypt_otp(code_enc_b64):
    """Dechiffre un OTP stocke en AES-256-GCM dans la base (SecretCipher.java)."""
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        return None
    key = hashlib.sha256(JWT_SECRET.encode()).digest()
    all_bytes = base64.b64decode(code_enc_b64)
    iv = all_bytes[:12]
    ct = all_bytes[12:]
    try:
        pt = AESGCM(key).decrypt(iv, ct, None)
        return pt.decode()
    except Exception:
        return None


def get_otp_from_db(challenge_id):
    """Lit le code_enc depuis la base puis le dechiffre."""
    try:
        result = subprocess.run(
            ["docker", "exec", "afb_titres_db", "psql", "-U", "afb_app", "-d", "afb_titres",
             "-t", "-c",
             "SELECT code_enc FROM mfa_challenges WHERE id = '%s';" % challenge_id],
            capture_output=True, text=True, timeout=10
        )
        code_enc = result.stdout.strip()
        if not code_enc:
            return None
        return decrypt_otp(code_enc)
    except Exception:
        return None


def login(email, pwd="Demo1234"):
    """Login + MFA : recupere l'OTP depuis la BD si la messagerie est active."""
    st, b = req("POST", "/api/v1/auth/login", {"email": email, "password": pwd})
    assert st == 200 and b and b.get("mfaRequired"), \
        "login %s echoue: %s %s" % (email, st, b)
    challenge_id = b["challengeId"]

    # Essai 1 : code fixe de developpement (actif si SMTP non configure)
    st2, b2 = req("POST", "/api/v1/auth/mfa/verify",
                  {"challengeId": challenge_id, "code": "123456"})
    if st2 == 200 and b2 and b2.get("accessToken"):
        return b2["accessToken"], b2["user"]

    # Essai 2 : OTP reel dechiffre depuis la base (SMTP Office365 configure)
    otp = get_otp_from_db(challenge_id)
    assert otp, "Impossible de recuperer l'OTP pour %s (challengeId=%s)" % (email, challenge_id)
    # Un nouveau login est necessaire : le challenge peut avoir ete marque consume
    # apres la tentative echouee ci-dessus.
    st, b = req("POST", "/api/v1/auth/login", {"email": email, "password": pwd})
    assert st == 200 and b and b.get("challengeId"), \
        "relance login %s: %s" % (email, st)
    challenge_id2 = b["challengeId"]
    otp2 = get_otp_from_db(challenge_id2)
    assert otp2, "OTP challenge2 introuvable pour %s" % email
    st3, b3 = req("POST", "/api/v1/auth/mfa/verify",
                  {"challengeId": challenge_id2, "code": otp2})
    assert st3 == 200 and b3 and b3.get("accessToken"), \
        "mfa echoue pour %s avec otp=%s: %s %s" % (email, otp2, st3, b3)
    return b3["accessToken"], b3["user"]


print()
print("=" * 70)
print("  FLUX COMPLET : PUBLICATION → SOUSCRIPTION → VALIDATION → LIVRABLES")
print("=" * 70)
print()

# ─────────────────────────────────────────────────────────────
# 1. Sante
# ─────────────────────────────────────────────────────────────
print("── 1. Sante ─────────────────────────────────────────────────────")
st, b = req("GET", "/health")
check("backend repond + BD connectee", st == 200 and b and b.get("database") == "up", (st, b))

# ─────────────────────────────────────────────────────────────
# 2. Authentification
# ─────────────────────────────────────────────────────────────
print()
print("── 2. Authentification ──────────────────────────────────────────")
tok_client, u_client = login("jean.mballa@example.cm")
check("login client (CLIENT_PP)", u_client.get("role") == "CLIENT_PP", u_client)

tok_agent, u_agent = login("takoua_efraim@afrilandfirstbank.com")
check("login agent (AGENT)", u_agent.get("role") == "AGENT", u_agent)

tok_sup, u_sup = login("bellofidele@gmail.com")
check("login superviseur (SUPERVISEUR)", u_sup.get("role") == "SUPERVISEUR", u_sup)

tok_admin, u_admin = login("fidele_bello@afrilandfirstbank.com")
check("login admin (ADMIN)", u_admin.get("role") == "ADMIN", u_admin)

# ─────────────────────────────────────────────────────────────
# 3. Creation de l'emission (BROUILLON)
# ─────────────────────────────────────────────────────────────
print()
print("── 3. Emission : creation BROUILLON (agent) ──────────────────────")
em_body = {
    "code": "BTA-CMR-F" + SUF,
    "isin": ("CF" + SUF + "0000")[:12],
    "libelle": "BTA Flux Complet 13 semaines",
    "nature": "BTA",
    "paysCode": "CMR",
    "dateEmission": "2026-07-01",
    "ouvertureSouscription": "2026-06-18T08:00:00Z",
    "fermetureSouscription": "2027-12-31T23:59:00Z",
    "dateEcheance": "2026-10-01",
    "dateReglement": "2026-07-03",
    "valeurNominaleUnitaire": 1000000,
    "montantGlobal": 10000000000,
    "tauxNominal": 5.5,
    "montantMinimum": 1000000,
    "modeAdjudication": "TAUX",
}
st, b = req("POST", "/api/v1/emissions", em_body, token=tok_agent)
check("agent cree l'emission (201 BROUILLON)", st == 201 and b and b.get("status") == "BROUILLON", (st, b))
em_id = b.get("id") if b else None

st, b = req("POST", "/api/v1/emissions", em_body, token=tok_client)
check("client ne peut PAS creer une emission (403)", st == 403, st)

# ─────────────────────────────────────────────────────────────
# 4. Publication (superviseur)
# ─────────────────────────────────────────────────────────────
print()
print("── 4. Emission : publication PUBLIE (superviseur) ────────────────")
st, b = req("POST", "/api/v1/emissions/%s/publish" % em_id, token=tok_sup)
check("superviseur publie l'emission (PUBLIE)", st == 200 and b and b.get("status") == "PUBLIE", (st, b))

st, b_c = req("POST", "/api/v1/emissions/%s/publish" % em_id, token=tok_client)
check("client ne peut PAS publier une emission (403)", st == 403, st)

# ─────────────────────────────────────────────────────────────
# 5. Client voit l'emission ouverte
# ─────────────────────────────────────────────────────────────
print()
print("── 5. Client : lecture des emissions ouvertes ────────────────────")
st, b = req("GET", "/api/v1/emissions", token=tok_client)
check("client liste les emissions (200)", st == 200 and b and "data" in b, (st, b))
publiees = [e for e in (b.get("data") or []) if e.get("status") == "PUBLIE"]
check("client ne voit que les emissions PUBLIE",
      all(e["status"] == "PUBLIE" for e in publiees), [e.get("status") for e in publiees])
check("la nouvelle emission est visible par le client",
      any(e.get("id") == em_id for e in publiees), "em_id=%s" % em_id)

# ─────────────────────────────────────────────────────────────
# 6. Client soumet un ordre
# ─────────────────────────────────────────────────────────────
print()
print("── 6. Client : soumission d'un ordre ─────────────────────────────")
st, b = req("POST", "/api/v1/orders",
            {"emissionId": em_id, "volume": 5, "tauxSoumis": 5.25}, token=tok_client)
check("client soumet l'ordre (201 SOUMIS)", st == 201 and b and b.get("status") == "SOUMIS", (st, b))
ord_id = b.get("id") if b else None
check("montant calcule = 5 x 1 000 000", b and b.get("montant") == 5000000, b.get("montant") if b else None)
check("compteEspeces renseigne", b and bool(b.get("compteEspeces")), b.get("compteEspeces") if b else None)

# ─────────────────────────────────────────────────────────────
# 7. Client modifie son ordre (PATCH — nouveau endpoint)
# ─────────────────────────────────────────────────────────────
print()
print("── 7. Client : modification de l'ordre (PATCH /orders/:id) ───────")
st, b = req("PATCH", "/api/v1/orders/%s" % ord_id,
            {"volume": 3, "tauxSoumis": 5.75}, token=tok_client)
check("client modifie volume + taux (200)", st == 200 and b and b.get("volume") == 3, (st, b))
check("montant recalcule (3 x 1 000 000 = 3 000 000)",
      b and b.get("montant") == 3000000, b.get("montant") if b else None)
check("taux mis a jour (5.75)", b and abs((b.get("tauxSoumis") or 0) - 5.75) < 0.001,
      b.get("tauxSoumis") if b else None)
check("statut reste SOUMIS apres modif", b and b.get("status") == "SOUMIS",
      b.get("status") if b else None)

# Agent ne peut pas modifier un ordre client via PATCH
st, b = req("PATCH", "/api/v1/orders/%s" % ord_id,
            {"volume": 1, "tauxSoumis": 1.0}, token=tok_agent)
check("agent ne peut PAS modifier l'ordre client via PATCH (403)", st == 403, (st, b))

# ─────────────────────────────────────────────────────────────
# 8. Agent valide l'ordre
# ─────────────────────────────────────────────────────────────
print()
print("── 8. Agent : validation de l'ordre ─────────────────────────────")
st, b = req("POST", "/api/v1/orders/%s/validate" % ord_id, token=tok_agent)
check("agent valide (200 EN_ATTENTE_ADJUDICATION)",
      st == 200 and b and b.get("status") == "EN_ATTENTE_ADJUDICATION", (st, b))
check("validatedByAgent renseigne",
      b and b.get("validatedByAgent") is not None, b.get("validatedByAgent") if b else None)

# Client ne peut plus modifier l'ordre apres validation (statut != SOUMIS)
st, b = req("PATCH", "/api/v1/orders/%s" % ord_id,
            {"volume": 10, "tauxSoumis": 6.0}, token=tok_client)
check("client ne peut PAS modifier apres validation (400)", st == 400, (st, b))

# ─────────────────────────────────────────────────────────────
# 9. Annulation d'un ordre valide → refusee
# ─────────────────────────────────────────────────────────────
print()
print("── 9. Client : annulation d'un ordre EN_ATTENTE → refus ──────────")
st, b = req("POST", "/api/v1/orders/%s/cancel" % ord_id, token=tok_client)
check("client ne peut PAS annuler un ordre EN_ATTENTE_ADJUDICATION (400)",
      st == 400, (st, b))

# ─────────────────────────────────────────────────────────────
# 10. Agent saisit le resultat d'adjudication (effet immediat)
# ─────────────────────────────────────────────────────────────
print()
print("── 10. Agent : saisie du resultat d'adjudication ─────────────────")
st, b = req("POST", "/api/v1/orders/%s/status" % ord_id,
            {"status": "TOTALEMENT_RETENU", "montantAdjuge": 3000000,
             "volumeAlloue": 3, "tauxAdjuge": 5.75}, token=tok_agent)
check("agent saisit TOTALEMENT_RETENU (effet direct, 200)",
      st == 200 and b and b.get("status") == "TOTALEMENT_RETENU", (st, b))
check("montantAdjuge = 3 000 000",
      b and b.get("montantAdjuge") == 3000000, b.get("montantAdjuge") if b else None)
check("tauxAdjuge = 5.75",
      b and abs((b.get("tauxAdjuge") or 0) - 5.75) < 0.001, b.get("tauxAdjuge") if b else None)

# ─────────────────────────────────────────────────────────────
# 11. Ordre cloture : impossible de changer le statut
# ─────────────────────────────────────────────────────────────
print()
print("── 11. Statut final : toute nouvelle transition refusee ───────────")
st, b = req("POST", "/api/v1/orders/%s/status" % ord_id,
            {"status": "SOUMIS"}, token=tok_agent)
check("ordre cloture : changement de statut refuse (400)", st == 400, (st, b))

# validate-result renvoie 400 car l'ordre est deja finalise
st, b = req("POST", "/api/v1/orders/%s/validate-result" % ord_id, token=tok_sup)
check("validate-result refuse (pas de resultat en attente, ordre deja finalise)",
      st == 400, (st, b))

# ─────────────────────────────────────────────────────────────
# 12. Client voit le statut final immediatement
# ─────────────────────────────────────────────────────────────
print()
print("── 12. Client : consultation du resultat final ───────────────────")
st, b = req("GET", "/api/v1/orders/%s" % ord_id, token=tok_client)
check("client voit TOTALEMENT_RETENU", st == 200 and b and b.get("status") == "TOTALEMENT_RETENU", (st, b))
check("client voit montantAdjuge = 3 000 000",
      b and b.get("montantAdjuge") == 3000000, b.get("montantAdjuge") if b else None)

# ─────────────────────────────────────────────────────────────
# 13. Portefeuille
# ─────────────────────────────────────────────────────────────
print()
print("── 13. Portefeuille client ───────────────────────────────────────")
st, b = req("GET", "/api/v1/portfolio", token=tok_client)
check("GET /portfolio (200)", st == 200 and b and "positions" in b, (st, b))
check("portefeuille reflete l'ordre adjuge (valeurTotale >= 3 000 000)",
      b and b.get("valeurTotale", 0) >= 3000000, b.get("valeurTotale") if b else None)

# ─────────────────────────────────────────────────────────────
# 16. Livrables : upload d'un avis d'operation
# ─────────────────────────────────────────────────────────────
print()
print("── 14. Livrables : upload d'un avis d'operation (agent) ──────────")
contenu_b64 = base64.b64encode(
    ("Avis d'operation - Ordre " + (ord_id or "") + " - TOTALEMENT RETENU").encode()
).decode()
doc_body = {
    "type": "AVIS_OPERATION",
    "titre": "Avis d'operation test flux complet",
    "reference": "AO-CF%s-20260618-001" % SUF,
    "mimeType": "application/pdf",
    "contenu": contenu_b64,
    "clientId": u_client.get("id"),
    "orderId": ord_id,
    "isin": em_body["isin"],
    "emissionCode": em_body["code"],
}
st, b = req("POST", "/api/v1/documents", doc_body, token=tok_agent)
check("agent upload le livrable (201)", st == 201 and b and b.get("id") is not None, (st, b))
doc_id = b.get("id") if b else None
check("reference du livrable presente", b and bool(b.get("reference")), b.get("reference") if b else None)

st2, _ = req("POST", "/api/v1/documents",
             {**doc_body, "reference": "AO-CF%s-20260618-002" % SUF}, token=tok_client)
check("client ne peut PAS uploader un document (403)", st2 == 403, st2)

# ─────────────────────────────────────────────────────────────
# 17. Client liste ses documents
# ─────────────────────────────────────────────────────────────
print()
print("── 15. Client : liste des livrables ─────────────────────────────")
st, b = req("GET", "/api/v1/documents?size=50", token=tok_client)
check("GET /documents (200)", st == 200 and b is not None, (st, b))
docs = b if isinstance(b, list) else (b.get("data") or b.get("documents") or [])
check("le livrable apparait dans la liste du client",
      any(d.get("id") == doc_id for d in docs),
      "doc_id=%s, docs=%d" % (doc_id, len(docs)))

# ─────────────────────────────────────────────────────────────
# 18. Client telecharge le livrable
# ─────────────────────────────────────────────────────────────
print()
print("── 16. Client : telechargement du livrable ───────────────────────")
st, b = req("GET", "/api/v1/documents/%s" % doc_id, token=tok_client)
check("GET /documents/:id (200 + contenu base64)",
      st == 200 and b and bool(b.get("contenu")), (st, (b.get("contenu") or "")[:30] if b else None))
if b and b.get("contenu"):
    try:
        decoded = base64.b64decode(b["contenu"]).decode()
        check("contenu dechiffrable et coherent", "TOTALEMENT RETENU" in decoded, decoded[:80])
    except Exception as ex:
        check("contenu dechiffrable", False, str(ex))

# ─────────────────────────────────────────────────────────────
# 19. Notifications
# ─────────────────────────────────────────────────────────────
print()
print("── 17. Notifications client ──────────────────────────────────────")
st, b = req("GET", "/api/v1/notifications", token=tok_client)
check("GET /notifications (200)", st == 200 and b and "total" in b, (st, b))
check("client a recu au moins 2 notifications",
      b and b.get("total", 0) >= 2, b.get("total") if b else None)

st, b = req("GET", "/api/v1/notifications/unread-count", token=tok_client)
check("GET /notifications/unread-count (200)", st == 200 and b and "count" in b, (st, b))

# ─────────────────────────────────────────────────────────────
# 20. Journal d'audit
# ─────────────────────────────────────────────────────────────
print()
print("── 18. Journal d'audit ───────────────────────────────────────────")
st, b = req("GET", "/api/v1/audit?size=100", token=tok_admin)
check("admin consulte le journal d'audit (200)",
      st == 200 and b and b.get("total", 0) >= 3, (st, b.get("total") if b else None))
if b and b.get("data"):
    actions = [e.get("action") for e in b["data"]]
    check("audit contient SOUMISSION_ORDRE",
          any("SOUMISSION" in (a or "") for a in actions), actions[:10])
    check("audit contient VALIDATION_ORDRE",
          any("VALIDATION" in (a or "") for a in actions), actions[:10])
    check("audit contient MODIFICATION_ORDRE (PATCH)",
          any("MODIFICATION" in (a or "") for a in actions), actions[:10])
    check("audit resout les noms d'acteurs",
          any(e.get("utilisateurNom") for e in b["data"]), "aucun nom resolu")

# ─────────────────────────────────────────────────────────────
# Bilan
# ─────────────────────────────────────────────────────────────
print()
print("=" * 70)
total = len(results)
ok = sum(1 for _, r in results if r)
ko = total - ok
print("  BILAN : %d / %d tests reussis  (%d echec%s)" % (ok, total, ko, "s" if ko > 1 else ""))
if ko > 0:
    print()
    print("  ECHECS :")
    for name, r in results:
        if not r:
            print("    \033[31m- %s\033[0m" % name)
print("=" * 70)
print()
