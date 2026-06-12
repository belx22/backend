# -*- coding: utf-8 -*-
"""Non-regression des corrections recentes (cf. cahier de test, module M17).

Couvre les correctifs BACKEND testables via l'API HTTP :
  CT-FIX-001 : la longueur d'OTP est configurable 4..8 (admin) ; /auth/mfa/verify
               doit accepter toute la plage. Avant correction, @Size(6,6) rejetait
               tout code != 6 chiffres en 400 -> MFA cassee des que l'admin change
               la longueur. On verifie les bornes de validation.
  CT-FIX-002 : /orders/:id/reject doit refuser un ordre NON_RETENU (statut final,
               resultat d'adjudication valide). Avant correction, l'ensemble exclu
               oubliait NON_RETENU et l'ordre basculait a tort en ANNULE.
  CT-FIX-003 : POST /clients avec un champ obligatoire nul (nom signataire,
               numero/libelle sous-compte) doit renvoyer 400 explicite et non un
               500 (NPE), les records de requete n'ayant pas de @Valid.

CT-FIX-004 (pas de FOUC au rechargement en production) et CT-FIX-005 (decote de
l'avis d'opere neutralisee en mode TAUX) sont des correctifs FRONTEND : ils sont
valides cote build/manuel (modules M16/M17) et sortent du perimetre de ce script.

Pre-requis : backend en ligne (docker compose up), comptes de demo seedes,
code OTP de demo = 123456 (APP_MFA_DEV_CODE). Budget d'appels auth volontairement
maintenu < 10/min pour ne pas declencher le limiteur de debit (10/min/IP).
"""
import json
import time
import urllib.request
import urllib.error
import http.cookiejar

BASE = "http://localhost:8080"
results = []
SUF = str(int(time.time()))[-6:]

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


def raw_login(email, pwd="Demo1234"):
    """Premiere etape seule : renvoie le challengeId (sans verifier la MFA)."""
    st, b = req("POST", "/api/v1/auth/login", {"email": email, "password": pwd})
    assert st == 200 and b.get("challengeId"), "login %s: %s %s" % (email, st, b)
    return b["challengeId"]


def verify(challenge, code):
    return req("POST", "/api/v1/auth/mfa/verify", {"challengeId": challenge, "code": code})


def login(email, pwd="Demo1234"):
    """Login complet -> (accessToken, user)."""
    ch = raw_login(email, pwd)
    st, b = verify(ch, "123456")
    assert st == 200 and b.get("accessToken"), "mfa %s: %s %s" % (email, st, b)
    return b["accessToken"], b["user"]


print("=" * 70)
print(" NON-REGRESSION DES CORRECTIONS RECENTES (M17) — BACKEND")
print("=" * 70)

st, b = req("GET", "/health")
check("GET /health (backend en ligne)", st == 200 and b.get("database") == "up", (st, b))

# ───────────────────────────────────────────────────────────────────────────
# CT-FIX-001 — Longueur d'OTP : la validation /mfa/verify accepte 4..8 chiffres.
# Astuce : un code errone increment "attempts" mais ne consomme PAS le defi
# (consume seulement au succes), et un 400 de validation court-circuite le
# limiteur de debit. Un meme defi sert donc a tester les bornes puis a obtenir
# le jeton client avec le code correct.
# ───────────────────────────────────────────────────────────────────────────
ch = raw_login("jean.mballa@example.cm")

# Sous la borne min (4) et au-dela de la borne max (8) -> 400 (validation).
st, b = verify(ch, "000")
check("CT-FIX-001 code 3 chiffres -> 400 (sous la borne min=4)", st == 400, (st, b))
st, b = verify(ch, "000000000")
check("CT-FIX-001 code 9 chiffres -> 400 (au-dela de la borne max=8)", st == 400, (st, b))

# Dans la plage 4..8 : la validation PASSE, la verification s'execute -> 401
# (code errone), JAMAIS 400. C'est la preuve directe du correctif @Size(4,8).
st, b = verify(ch, "00000000")
check("CT-FIX-001 code 8 chiffres errone -> 401 (accepte par la validation)", st == 401, (st, b))
st, b = verify(ch, "0000")
check("CT-FIX-001 code 4 chiffres errone -> 401 (accepte par la validation)", st == 401, (st, b))

# Le defi survit aux tentatives erronees : le bon code delivre encore les jetons
# (MFA non cassee) et fournit le token client pour la suite.
st, b = verify(ch, "123456")
check("CT-FIX-001 code correct -> 200 (MFA fonctionnelle apres essais)",
      st == 200 and b.get("accessToken"), (st, b))
tok_client = b["accessToken"] if (st == 200 and isinstance(b, dict)) else None
if not tok_client:
    tok_client, _ = login("jean.mballa@example.cm")

tok_agent, _ = login("agent@afriland.cm")
tok_sup, _ = login("superviseur@afriland.cm")

# ───────────────────────────────────────────────────────────────────────────
# CT-FIX-002 — /orders/:id/reject refuse un ordre NON_RETENU (statut final).
# On amene un ordre jusqu'a NON_RETENU (adjudication validee), puis on le rejette.
# ───────────────────────────────────────────────────────────────────────────
em = {
    "code": "BTA-CMR-F" + SUF, "isin": ("CF" + SUF + "0000")[:12],
    "libelle": "BTA Non-regression FIX 13 semaines", "nature": "BTA", "paysCode": "CMR",
    "dateEmission": "2026-06-01", "ouvertureSouscription": "2026-06-02T08:00:00Z",
    "fermetureSouscription": "2026-06-09T09:00:00Z", "dateEcheance": "2026-09-01",
    "dateReglement": "2026-06-10", "valeurNominaleUnitaire": 1000000,
    "montantGlobal": 5000000000, "tauxNominal": 0, "montantMinimum": 1000000,
    "modeAdjudication": "TAUX",
}
st, b = req("POST", "/api/v1/emissions", em, token=tok_agent)
check("CT-FIX-002 prep : agent cree une emission", st == 201 and b.get("status") == "BROUILLON", (st, b))
em_id = b.get("id") if isinstance(b, dict) else None
st, b = req("POST", "/api/v1/emissions/%s/publish" % em_id, token=tok_sup)
check("CT-FIX-002 prep : superviseur publie", st == 200 and b.get("status") == "PUBLIE", (st, b))

st, b = req("POST", "/api/v1/orders",
            {"emissionId": em_id, "volume": 2, "tauxSoumis": 6.0}, token=tok_client)
check("CT-FIX-002 prep : client soumet un ordre", st == 201 and b.get("status") == "SOUMIS", (st, b))
ord_id = b.get("id") if isinstance(b, dict) else None
st, b = req("POST", "/api/v1/orders/%s/validate" % ord_id, token=tok_agent)
check("CT-FIX-002 prep : agent transmet a l'adjudication",
      st == 200 and b.get("status") == "EN_ATTENTE_ADJUDICATION", (st, b))

st, b = req("POST", "/api/v1/orders/%s/status" % ord_id,
            {"status": "NON_RETENU", "montantAdjuge": 0, "volumeAlloue": 0, "tauxAdjuge": 0,
             "commentaire": "offre non retenue (non-regression)"}, token=tok_agent)
check("CT-FIX-002 prep : agent PROPOSE NON_RETENU (statut reste EN_ATTENTE)",
      st == 200 and b.get("status") == "EN_ATTENTE_ADJUDICATION"
      and b.get("resultatPropose") == "NON_RETENU", (st, b))
st, b = req("POST", "/api/v1/orders/%s/validate-result" % ord_id, token=tok_sup)
check("CT-FIX-002 prep : superviseur VALIDE -> NON_RETENU (statut final)",
      st == 200 and b.get("status") == "NON_RETENU", (st, b))

# Coeur du correctif : un ordre NON_RETENU ne peut plus etre rejete.
st, b = req("POST", "/api/v1/orders/%s/reject" % ord_id,
            {"motif": "tentative de rejet d'un ordre final (non-regression)"}, token=tok_agent)
check("CT-FIX-002 reject d'un ordre NON_RETENU -> 400 (statut final)", st == 400, (st, b))

# ───────────────────────────────────────────────────────────────────────────
# CT-FIX-003 — Creation client : champ obligatoire nul -> 400 (et non 500 NPE).
# ───────────────────────────────────────────────────────────────────────────
adresse = {"type": "DOMICILE", "rue": "Rue de la Non-Regression",
           "ville": "Yaounde", "pays": "Cameroun"}
sc_ok = {"numero": "SC-" + SUF, "libelle": "Compte de conservation"}

# (a) Nom du premier signataire nul -> 400 (et non NPE au .trim()).
bad_nom = {
    "type": "PP", "raisonSociale": "FIX003 Nom Nul", "categorie": "NON_QUALIFIE",
    "typeCompte": "INDIVIDUEL", "adresse": adresse,
    "signataires": [{"type": "TITULAIRE", "nom": None, "prenom": "Jean",
                     "email": "fix003a." + SUF + "@example.cm",
                     "telephonePortable": "690000001"}],
    "sousComptes": [sc_ok],
}
st, b = req("POST", "/api/v1/clients", bad_nom, token=tok_agent)
check("CT-FIX-003 signataire sans nom -> 400 (pas 500 NPE)", st == 400, (st, b))

# (b) Sous-compte sans numero -> 400 (et non NPE au .trim()).
bad_sc = {
    "type": "PP", "raisonSociale": "FIX003 Sous-compte Nul", "categorie": "NON_QUALIFIE",
    "typeCompte": "INDIVIDUEL", "adresse": adresse,
    "signataires": [{"type": "TITULAIRE", "nom": "Mballa", "prenom": "Paul",
                     "email": "fix003b." + SUF + "@example.cm",
                     "telephonePortable": "690000002"}],
    "sousComptes": [{"numero": None, "libelle": "Compte de conservation"}],
}
st, b = req("POST", "/api/v1/clients", bad_sc, token=tok_agent)
check("CT-FIX-003 sous-compte sans numero -> 400 (pas 500 NPE)", st == 400, (st, b))


# ───────────────────────────────────────────────────────────────────────────
# CT-FIX-006 — Numeros de compte uniques (generation aleatoire verifiee + V13).
# Deux clients crees a la suite doivent obtenir des comptes-titres DISTINCTS et
# non vides (l'ancienne generation par horodatage pouvait collisionner).
# ───────────────────────────────────────────────────────────────────────────
def make_client(tag, tel):
    return {
        "type": "PP", "raisonSociale": "Client Unicite " + tag, "categorie": "NON_QUALIFIE",
        "typeCompte": "INDIVIDUEL",
        "adresse": {"type": "DOMICILE", "rue": "Rue de l'Unicite", "ville": "Yaounde", "pays": "Cameroun"},
        "signataires": [{"type": "TITULAIRE", "nom": "Unicite" + tag, "prenom": "Test",
                         "email": ("unicite" + tag + "." + SUF + "@example.cm").lower(),
                         "telephonePortable": tel}],
        "sousComptes": [{"numero": "SC-" + tag + "-" + SUF, "libelle": "Compte de conservation"}],
    }


def compte_titres(dossier):
    return (dossier or {}).get("compte", {}).get("numero")


st, ba = req("POST", "/api/v1/clients", make_client("A", "690000010"), token=tok_agent)
check("CT-FIX-006 creation client A -> 201", st == 201, (st, ba))
st, bb = req("POST", "/api/v1/clients", make_client("B", "690000011"), token=tok_agent)
check("CT-FIX-006 creation client B -> 201", st == 201, (st, bb))
ct_a, ct_b = compte_titres(ba), compte_titres(bb)
check("CT-FIX-006 comptes-titres non vides et DISTINCTS",
      bool(ct_a) and bool(ct_b) and ct_a != ct_b, (ct_a, ct_b))

# ───────────────────────────────────────────────────────────────────────────
print("-" * 70)
print(" NOTE : CT-FIX-004 (pas de FOUC en production) et CT-FIX-005 (decote avis")
print("        en mode TAUX) sont des correctifs FRONTEND, valides cote build et")
print("        manuellement (modules M16/M17) — hors perimetre de ce script API.")
print("=" * 70)
total = len(results)
ok = sum(1 for r in results if r)
print(" RESULTAT : %d / %d tests reussis" % (ok, total))
print("=" * 70)
