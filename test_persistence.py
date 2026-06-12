# -*- coding: utf-8 -*-
"""Verifie que les donnees creees survivent a un redemarrage / rebuild."""
import json
import time
import urllib.request
import urllib.error

BASE = "http://localhost:8080"


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


def login(email):
    st, b = req("POST", "/api/v1/auth/login", {"email": email, "password": "Demo1234"})
    assert st == 200, (st, b)
    st, b = req("POST", "/api/v1/auth/mfa/verify",
                {"challengeId": b["challengeId"], "code": "123456"})
    assert st == 200, (st, b)
    return b["accessToken"]


# 1) On cree une emission de demonstration en tant qu'agent.
tok_agent = login("agent@afriland.cm")
suf = str(int(time.time()))[-6:]
em = {
    "code": "PERSIST-" + suf, "isin": ("PE" + suf + "0000")[:12],
    "libelle": "Test persistance redemarrage", "nature": "BTA", "paysCode": "CMR",
    "dateEmission": "2026-06-01", "ouvertureSouscription": "2026-06-02T08:00:00Z",
    "fermetureSouscription": "2026-06-09T09:00:00Z", "dateEcheance": "2026-09-01",
    "dateReglement": "2026-06-10", "valeurNominaleUnitaire": 1000000,
    "montantGlobal": 5000000000, "tauxNominal": 0, "montantMinimum": 1000000,
    "modeAdjudication": "TAUX",
}
st, b = req("POST", "/api/v1/emissions", em, token=tok_agent)
assert st == 201, ("creation emission", st, b)
em_id = b["id"]
em_code = b["code"]
print("AVANT redemarrage : emission creee   id=%s code=%s status=%s"
      % (em_id, em_code, b["status"]))


# 2) On la publie, puis un client soumet un ordre dessus.
tok_sup = login("superviseur@afriland.cm")
req("POST", "/api/v1/emissions/%s/publish" % em_id, token=tok_sup)

tok_client = login("jean.mballa@example.cm")
st, b = req("POST", "/api/v1/orders",
            {"emissionId": em_id, "volume": 2, "tauxSoumis": 5.5}, token=tok_client)
assert st == 201, ("creation ordre", st, b)
ord_ref = b["reference"]
print("                  : ordre creé        ref=%s status=%s" % (ord_ref, b["status"]))
