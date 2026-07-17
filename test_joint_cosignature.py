# -*- coding: utf-8 -*-
"""Test d'integration — compte-titres JOINT a double signature (M-JOINT).

Couvre :
  - Creation d'un compte JOINT a 2 signataires -> 2 logins, le co-signataire
    rattache au titulaire principal (account_holder_id).
  - Soumission d'un ordre sur le compte joint -> statut EN_ATTENTE_SIGNATURES +
    demande de signature pour l'AUTRE signataire.
  - Le co-signataire voit l'operation dans /orders/cosignatures/pending.
  - Validation par le co-signataire -> l'ordre passe SOUMIS (tous ont signe).
  - Refus par le co-signataire (2e ordre) -> l'ordre passe ANNULE.

Depuis V32 (cosignature = signature + visage) :
  - Valider SANS signature ou SANS photo est refuse (400).
  - Refuser n'exige ni signature ni photo.
  - La validation enregistre la signature et une photo dans order_face_checks.
  - Le back-office voit toutes les personnes du compte, qui a valide / qui est
    en attente, avec les deux photos de chacune (/admin/orders/{id}/signatures).
  - Le back-office peut transmettre l'ordre sans attendre tout le monde
    (/orders/{id}/force-validate) -> les PENDING passent en BYPASSED.

Astuce de test : les logins crees ont un mot de passe aleatoire (envoye par
e-mail). On copie le hash du compte de demo (mot de passe Demo1234) sur les deux
logins via psql, pour pouvoir se connecter normalement (login + OTP 123456).
"""
import json
import subprocess
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
    print("  [%s] %s%s" % ("PASS" if cond else "FAIL", name,
                           ("  -->  " + str(detail)[:300]) if (detail and not cond) else ""))


def login(email, pwd="Demo1234"):
    st, b = req("POST", "/api/v1/auth/login", {"email": email, "password": pwd})
    assert st == 200 and b.get("challengeId"), "login %s: %s %s" % (email, st, b)
    st, b = req("POST", "/api/v1/auth/mfa/verify", {"challengeId": b["challengeId"], "code": "123456"})
    assert st == 200 and b.get("accessToken"), "mfa %s: %s %s" % (email, st, b)
    return b["accessToken"]


def psql(sql):
    out = subprocess.run(
        ["docker", "exec", "afb_titres_db", "psql", "-U", "afb_app", "-d", "afb_titres", "-tAc", sql],
        capture_output=True, text=True)
    return out.stdout.strip()


def make_emission(tag):
    em = {
        "code": "BTA-JNT-" + tag + SUF, "isin": ("JN" + tag + SUF + "000")[:12],
        "libelle": "BTA Joint " + tag, "nature": "BTA", "paysCode": "CMR",
        "dateEmission": "2026-06-01", "ouvertureSouscription": "2026-06-02T08:00:00Z",
        "fermetureSouscription": "2026-06-09T09:00:00Z", "dateEcheance": "2026-09-01",
        "dateReglement": "2026-06-10", "valeurNominaleUnitaire": 1000000,
        "montantGlobal": 5000000000, "tauxNominal": 0, "montantMinimum": 1000000,
        "modeAdjudication": "ADJ",
    }
    st, b = req("POST", "/api/v1/emissions", em, token=tok_agent)
    assert st == 201, ("emission", st, b)
    eid = b["id"]
    st, b = req("POST", "/api/v1/emissions/%s/publish" % eid, token=tok_sup)
    assert st == 200, ("publish", st, b)
    return eid


# Preuves exigees pour VALIDER : signature manuscrite + photo du visage.
# Le contenu importe peu ici (le serveur stocke les octets et compare les
# empreintes) : seule leur PRESENCE est une regle metier.
SIGNATURE = "data:image/png;base64," + ("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
                                        "AAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
PHOTO = "data:image/jpeg;base64," + ("/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsL"
                                     "DBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/"
                                     "2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIy"
                                     "MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QA"
                                     "HwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF"
                                     "BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkK"
                                     "FhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1"
                                     "dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG"
                                     "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/9oACAEBAAA/APn+"
                                     "iiigD//Z")


def preuve(**extra):
    """Corps de validation d'un co-signataire (signature + visage)."""
    body = {"approve": True, "signatureData": SIGNATURE, "imageBase64": PHOTO,
            "livenessScore": 0.97, "challengeType": "BLINK"}
    body.update(extra)
    return body


print("=" * 70)
print(" COMPTE JOINT — DOUBLE SIGNATURE DES ORDRES")
print("=" * 70)

st, b = req("GET", "/health")
check("backend en ligne", st == 200 and b.get("database") == "up", (st, b))

tok_agent = login("agent@afriland.cm")
tok_sup = login("superviseur@afriland.cm")

emailA = "joint.a." + SUF + "@example.cm"
emailB = "joint.b." + SUF + "@example.cm"
client = {
    "type": "PP", "raisonSociale": "Compte Joint " + SUF, "categorie": "NON_QUALIFIE",
    "typeCompte": "JOINT",
    "adresse": {"type": "DOMICILE", "rue": "Rue Jointe", "ville": "Yaounde", "pays": "Cameroun"},
    "signataires": [
        {"type": "TITULAIRE", "nom": "Signataire A", "prenom": "Un", "email": emailA, "telephonePortable": "690000021"},
        {"type": "TITULAIRE", "nom": "Signataire B", "prenom": "Deux", "email": emailB, "telephonePortable": "690000022"},
    ],
    "sousComptes": [{"numero": "SCJ-" + SUF, "libelle": "Conservation"}],
}
st, b = req("POST", "/api/v1/clients", client, token=tok_agent)
check("creation compte JOINT (2 signataires) -> 201", st == 201, (st, b))

# Le co-signataire B doit etre un login rattache au titulaire principal A.
holder = psql("SELECT account_holder_id FROM users WHERE email='%s'" % emailB)
primary = psql("SELECT id FROM users WHERE email='%s'" % emailA)
check("co-signataire B rattache au titulaire principal A (account_holder_id)",
      holder != "" and holder == primary, (holder, primary))
check("le titulaire principal A n'a pas de account_holder_id",
      psql("SELECT account_holder_id FROM users WHERE email='%s'" % emailA) == "", None)

# Rend les deux logins utilisables (mot de passe Demo1234, pas de changement force).
psql("UPDATE users SET password_hash=(SELECT password_hash FROM users WHERE email='jean.mballa@example.cm'), "
     "must_change_password=false WHERE email IN ('%s','%s')" % (emailA, emailB))

tokA = login(emailA)
tokB = login(emailB)

# ---- Parcours nominal : A soumet, B valide -> SOUMIS ----
em1 = make_emission("X")
st, b = req("POST", "/api/v1/orders", {"emissionId": em1, "volume": 2, "tauxSoumis": 5.5}, token=tokA)
check("A soumet un ordre sur le compte joint -> EN_ATTENTE_SIGNATURES",
      st == 201 and b.get("status") == "EN_ATTENTE_SIGNATURES", (st, b))
ord1 = b.get("id") if isinstance(b, dict) else None

st, b = req("GET", "/api/v1/orders/cosignatures/pending", token=tokB)
check("B voit l'operation en attente de SA signature",
      st == 200 and isinstance(b, list) and any(x.get("orderId") == ord1 for x in b), (st, b))

st, b = req("GET", "/api/v1/orders/cosignatures/pending", token=tokA)
check("A (emetteur) n'a PAS de signature en attente", st == 200 and isinstance(b, list)
      and not any(x.get("orderId") == ord1 for x in b), (st, b))

# Valider engage : la signature ET le visage sont exiges (comme a l'inscription).
st, b = req("POST", "/api/v1/orders/%s/cosign" % ord1, {"approve": True}, token=tokB)
check("B ne peut pas valider sans signature ni photo -> 400", st == 400, (st, b))

st, b = req("POST", "/api/v1/orders/%s/cosign" % ord1,
            preuve(imageBase64=None), token=tokB)
check("B ne peut pas valider sans photo du visage -> 400", st == 400, (st, b))

st, b = req("POST", "/api/v1/orders/%s/cosign" % ord1,
            preuve(signatureData=None), token=tokB)
check("B ne peut pas valider sans signature -> 400", st == 400, (st, b))

st, b = req("POST", "/api/v1/orders/%s/cosign" % ord1, preuve(), token=tokB)
check("B valide avec signature + visage -> ordre transmis (SOUMIS)",
      st == 200 and b.get("status") == "SOUMIS", (st, b))

check("la signature de B est enregistree",
      psql("SELECT length(signature_data) > 0 FROM order_signatures WHERE order_id='%s' "
           "AND status='SIGNED'" % ord1) == "t", None)
check("la photo de B est enregistree pour cet ordre",
      psql("SELECT count(*) FROM order_face_checks c JOIN users u ON u.id=c.user_id "
           "WHERE c.order_id='%s' AND u.email='%s'" % (ord1, emailB)) == "1", None)

st, b = req("GET", "/api/v1/orders/%s" % ord1, token=tokA)
check("A voit l'ordre desormais SOUMIS (compte partage)",
      st == 200 and b.get("status") == "SOUMIS", (st, b))

# ---- Back-office : qui a valide, qui est en attente, et les photos ----
st, b = req("GET", "/api/v1/admin/orders/%s/signatures" % ord1, token=tok_agent)
check("BO voit la vue compte joint de l'ordre", st == 200 and b.get("joint") is True, (st, b))
personnes = b.get("personnes") if isinstance(b, dict) else []
check("BO voit les 2 personnes liees au compte", len(personnes) == 2, personnes)
check("BO voit l'emetteur A et le co-signataire B",
      {p.get("role") for p in personnes} == {"EMETTEUR", "COSIGNATAIRE"}, personnes)
sigB = next((p for p in personnes if p.get("role") == "COSIGNATAIRE"), {})
check("BO voit que B a valide", sigB.get("statut") == "SIGNED", sigB)
check("BO voit la photo de soumission de B", bool(sigB.get("photoSoumission")), None)
check("BO voit la signature de B", bool(sigB.get("signature")), None)

# ---- Parcours refus : A soumet, B refuse -> ANNULE ----
em2 = make_emission("Y")
st, b = req("POST", "/api/v1/orders", {"emissionId": em2, "volume": 2, "tauxSoumis": 5.5}, token=tokA)
ord2 = b.get("id") if isinstance(b, dict) else None
check("A soumet un 2e ordre -> EN_ATTENTE_SIGNATURES",
      st == 201 and b.get("status") == "EN_ATTENTE_SIGNATURES", (st, b))

# Refuser n'exige ni signature ni photo : on ne fait pas prouver son identite
# pour dire non (et l'exiger retiendrait un refus faute de camera).
st, b = req("POST", "/api/v1/orders/%s/cosign" % ord2, {"approve": False}, token=tokB)
check("B refuse SANS signature ni photo -> ordre ANNULE",
      st == 200 and b.get("status") == "ANNULE", (st, b))

st, b = req("POST", "/api/v1/orders/%s/cosign" % ord2, preuve(), token=tokB)
check("B ne peut plus re-signer un ordre clos -> 400", st == 400, (st, b))

# ---- Validation forcee : le BO transmet sans attendre B ----
em3 = make_emission("Z")
st, b = req("POST", "/api/v1/orders", {"emissionId": em3, "volume": 2, "tauxSoumis": 5.5}, token=tokA)
ord3 = b.get("id") if isinstance(b, dict) else None
check("A soumet un 3e ordre -> EN_ATTENTE_SIGNATURES",
      st == 201 and b.get("status") == "EN_ATTENTE_SIGNATURES", (st, b))

st, b = req("POST", "/api/v1/orders/%s/force-validate" % ord3, {"motif": "ab"}, token=tok_agent)
check("validation forcee sans motif valable -> 400", st == 400, (st, b))

st, b = req("POST", "/api/v1/orders/%s/force-validate" % ord3,
            {"motif": "Accord de B recueilli en agence"}, token=tokA)
check("un client ne peut pas forcer la validation -> 403", st == 403, (st, b))

st, b = req("POST", "/api/v1/orders/%s/force-validate" % ord3,
            {"motif": "Accord de B recueilli en agence"}, token=tok_agent)
check("le BO transmet l'ordre sans attendre B -> SOUMIS",
      st == 200 and b.get("status") == "SOUMIS", (st, b))

check("la demande de signature de B passe en BYPASSED",
      psql("SELECT status FROM order_signatures WHERE order_id='%s'" % ord3) == "BYPASSED", None)
check("la validation forcee est tracee (qui + motif)",
      psql("SELECT signatures_bypassed_by IS NOT NULL AND signatures_bypass_motif IS NOT NULL "
           "FROM orders WHERE id='%s'" % ord3) == "t", None)

st, b = req("GET", "/api/v1/admin/orders/%s/signatures" % ord3, token=tok_agent)
sigB3 = next((p for p in (b.get("personnes") or []) if p.get("role") == "COSIGNATAIRE"), {})
check("BO voit B en BYPASSED sur l'ordre force", sigB3.get("statut") == "BYPASSED", sigB3)

st, b = req("POST", "/api/v1/orders/%s/cosign" % ord3, preuve(), token=tokB)
check("B ne peut plus valider un ordre deja transmis -> 400", st == 400, (st, b))

print("=" * 70)
ok = sum(1 for r in results if r)
print(" RESULTAT : %d / %d tests reussis" % (ok, len(results)))
print("=" * 70)
