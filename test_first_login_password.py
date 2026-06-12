# -*- coding: utf-8 -*-
"""Test de la fonction « changement de mot de passe a la premiere connexion ».

Scenario :
  1. L'admin se connecte (login + MFA).
  2. L'admin cree un compte AGENT avec un mot de passe provisoire
     -> must_change_password = TRUE (POST /users).
  3. Le nouveau compte se connecte avec le mot de passe provisoire.
     -> le jeton porte le claim mcp=true ; le profil expose mustChangePassword.
  4. Tant que le mot de passe n'est pas change, toute route protegee renvoie
     403 PASSWORD_CHANGE_REQUIRED (MustChangePasswordInterceptor).
  5. Mot de passe actuel errone -> 401.
  6. Nouveau == actuel -> 400 (refus).
  7. Changement valide -> 200, le drapeau retombe a FALSE.
  8. Apres changement : route protegee accessible (200).
  9. L'ancien mot de passe provisoire ne fonctionne plus ; le nouveau oui.
"""
import json
import time
import urllib.request
import urllib.error
import http.cookiejar

BASE = "http://localhost:8080"
OTP = "123456"            # APP_MFA_DEV_CODE (mode demo)
DEMO = "Demo1234"
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


def login(email, pwd):
    """Retourne (accessToken, user) ou leve une AssertionError detaillee."""
    st, b = req("POST", "/api/v1/auth/login", {"email": email, "password": pwd})
    assert st == 200 and b.get("mfaRequired"), "login %s: %s %s" % (email, st, b)
    st, b = req("POST", "/api/v1/auth/mfa/verify",
                {"challengeId": b["challengeId"], "code": OTP})
    assert st == 200 and b.get("accessToken"), "mfa %s: %s %s" % (email, st, b)
    return b["accessToken"], b["user"]


print("=" * 70)
print(" TEST — CHANGEMENT DE MOT DE PASSE A LA PREMIERE CONNEXION")
print("=" * 70)

# 0) Sante
st, b = req("GET", "/health")
check("backend en ligne", st == 200, (st, b))

# 1) Connexion admin
tok_admin, u_admin = login("admin@afriland.cm", DEMO)
check("connexion admin", u_admin["role"] == "ADMIN", u_admin)

# 2) L'admin cree un compte avec mot de passe provisoire (-> must_change=TRUE)
email_new = "agent.test.%s@afriland.cm" % SUF
PROV = "Provisoire#2026"
st, b = req("POST", "/api/v1/users", {
    "email": email_new, "password": PROV, "role": "AGENT",
    "nom": "TESTEUR", "prenom": "Premier",
}, token=tok_admin)
check("admin cree un compte (201) avec mustChangePassword=true",
      st == 201 and b.get("mustChangePassword") is True, (st, b))

# 3) Le nouveau compte se connecte avec le mot de passe provisoire
tok_new, u_new = login(email_new, PROV)
check("connexion avec mot de passe provisoire (login+MFA OK)",
      bool(tok_new), u_new)
check("le profil expose mustChangePassword=true",
      u_new.get("mustChangePassword") is True, u_new)

# 4) Route protegee bloquee tant que le mot de passe n'est pas change
st, b = req("GET", "/api/v1/users", token=tok_new)
code = b.get("error", {}).get("code") if isinstance(b, dict) else None
check("route protegee -> 403 PASSWORD_CHANGE_REQUIRED",
      st == 403 and code == "PASSWORD_CHANGE_REQUIRED", (st, b))

# 4bis) /auth/me reste accessible malgre le drapeau (whitelist interceptor)
st, b = req("GET", "/api/v1/auth/me", token=tok_new)
check("/auth/me reste accessible malgre le drapeau", st == 200, (st, b))

# 5) Mot de passe actuel errone -> 401
st, b = req("POST", "/api/v1/auth/change-password",
            {"currentPassword": "MauvaisMdp1", "newPassword": "NouveauMdp#2026"},
            token=tok_new)
check("mot de passe actuel errone -> 401", st == 401, (st, b))

# 6) Nouveau identique a l'actuel -> 400
st, b = req("POST", "/api/v1/auth/change-password",
            {"currentPassword": PROV, "newPassword": PROV}, token=tok_new)
check("nouveau == actuel -> 400 (refus)", st == 400, (st, b))

# 7) Changement valide -> 200
NOUVEAU = "NouveauMdp#2026"
st, b = req("POST", "/api/v1/auth/change-password",
            {"currentPassword": PROV, "newPassword": NOUVEAU}, token=tok_new)
check("changement valide -> 200", st == 200, (st, b))

# 8) Reconnexion avec le NOUVEAU mot de passe : drapeau retombe + acces OK
tok_after, u_after = login(email_new, NOUVEAU)
check("reconnexion avec le nouveau mot de passe : mustChangePassword=false",
      u_after.get("mustChangePassword") is False, u_after)
# Route reellement autorisee a un AGENT (CLIENT_MANAGE) : prouve que
# l'intercepteur ne bloque plus (plus de 403 PASSWORD_CHANGE_REQUIRED).
st, b = req("GET", "/api/v1/clients/dossiers", token=tok_after)
check("route metier de l'agent desormais accessible -> 200", st == 200, (st, b))

# 9) L'ancien mot de passe provisoire ne fonctionne plus
st, b = req("POST", "/api/v1/auth/login", {"email": email_new, "password": PROV})
check("l'ancien mot de passe provisoire est refuse -> 401",
      st == 401, (st, b))

print("=" * 70)
total = len(results)
ok = sum(1 for r in results if r)
print(" RESULTAT : %d / %d tests reussis" % (ok, total))
print(" Compte de test cree : %s" % email_new)
print("=" * 70)
