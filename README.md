# Backend — Plateforme Valeurs du Trésor CEMAC

API REST en **Java / Spring Boot** avec **PostgreSQL**, pour la plateforme
Afriland First Bank de souscription aux titres publics CEMAC (BTA / OTA).

## Pile technique

| Composant        | Choix                                                  |
|------------------|--------------------------------------------------------|
| Langage / runtime| Java 21                                                |
| Framework HTTP   | Spring Boot 3.3 (Spring MVC / Tomcat)                  |
| Base de données  | PostgreSQL 16 via Spring `JdbcTemplate` (requêtes paramétrées) |
| Migrations       | Flyway                                                 |
| Authentification | JWT HS + MFA (OTP) + refresh tokens                    |
| Hachage          | Argon2id (Spring Security Crypto + BouncyCastle)       |
| Build            | Maven                                                  |

## Démarrage rapide (Docker)

```bash
cd Backend_
docker compose up --build
```

Cela lance PostgreSQL **et** le backend. Au premier démarrage, la base est
migrée (Flyway) puis peuplée de données de démonstration. L'API écoute sur
`http://localhost:8080`.

Vérification : `curl http://localhost:8080/health`

### Démarrage hors Docker

Requiert un JDK 21, Maven et un PostgreSQL accessible.

```bash
# Configurer la connexion (variables d'environnement Spring) :
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/afb_titres
export SPRING_DATASOURCE_USERNAME=afb_app
export SPRING_DATASOURCE_PASSWORD=change_me_db
export APP_JWT_SECRET=un_secret_aleatoire_de_32_caracteres_minimum

mvn spring-boot:run
```

## Comptes de démonstration

Mot de passe commun : **`Demo1234`**

| E-mail                      | Rôle        |
|-----------------------------|-------------|
| `jean.mballa@example.cm`    | CLIENT_PP   |
| `alucam@example.cm`         | CLIENT_PM   |
| `agent@afriland.cm`         | AGENT       |
| `superviseur@afriland.cm`   | SUPERVISEUR |
| `admin@afriland.cm`         | ADMIN       |
| `conformite@afriland.cm`    | CONFORMITE  |
| `direction@afriland.cm`     | DIRECTION   |

> La MFA est obligatoire. En développement, le code OTP est fixé à **`123456`**
> (variable `APP_MFA_DEV_CODE`). En production, retirer cette variable : la MFA
> utilise alors un code aléatoire à usage unique, journalisé côté serveur
> (`docker compose logs backend`, ligne `Code OTP`).

## Parcours d'authentification

1. `POST /api/v1/auth/login` → `{ challengeId }`
2. `POST /api/v1/auth/mfa/verify` `{ challengeId, code }` → `{ accessToken, refreshToken }`
3. Requêtes authentifiées : en-tête `Authorization: Bearer <accessToken>`
4. `POST /api/v1/auth/refresh` `{ refreshToken }` pour renouveler les jetons

## Principales routes (`/api/v1`)

- `auth/*` — connexion, MFA, refresh, logout, `me`
- `emissions` — liste / détail (public : statut PUBLIE), CRUD (back-office)
- `orders` — souscription (client), validation / rejet / résultat (back-office)
- `portfolio` — positions titres dérivées des ordres adjugés
- `users`, `clients` — administration (RBAC)
- `documents`, `notifications` — livrables et notifications in-app
- `permissions` — matrice RBAC (modification réservée à l'administrateur)
- `audit` — journal d'audit (lecture seule)

## Architecture du code (`src/main/java/cm/afriland/titres`)

- `config` — propriétés applicatives, CORS, en-têtes de sécurité, résolveurs MVC
- `security` — JWT, Argon2id, RBAC, limitation de débit, `AuthUser`/`ClientIp`
- `web` — un contrôleur REST par module métier
- `audit`, `notif` — services transverses
- `error` — exceptions applicatives et gestionnaire global
- `seed` — insertion du jeu de données de démonstration au démarrage
- `resources/db/migration` — migrations Flyway (`V1`…`V6`)

## Tests

Un script de bout en bout couvre tous les modules :

```bash
docker compose up -d
python test_api.py
```

## Bonnes pratiques de sécurité appliquées

- **Mots de passe** : hachage Argon2id, sel aléatoire par compte, jamais stockés ni renvoyés en clair.
- **Injection SQL** : requêtes 100 % paramétrées (`JdbcTemplate`) — aucune concaténation de valeurs.
- **JWT** : jeton d'accès court (15 min) ; refresh tokens opaques stockés hachés (SHA-256), avec rotation et révocation.
- **MFA** : OTP obligatoire, à usage unique, expirant en 5 min, stocké haché.
- **Anti-bruteforce** : limitation de débit par IP + verrouillage de compte après 5 échecs.
- **RBAC** : matrice rôle → permissions, principe du moindre privilège ; cloisonnement client (accès limité à ses propres données).
- **Anti-énumération** : messages d'erreur génériques + comparaison à temps constant même pour un e-mail inconnu.
- **Validation** des entrées (Jakarta Bean Validation) + contraintes `CHECK` en base.
- **En-têtes HTTP de sécurité** (CSP, X-Frame-Options, HSTS, nosniff…) et **CORS** restreint à l'origine du frontend.
- **Fuite d'informations** : les erreurs internes sont journalisées mais jamais détaillées au client.
- **Conteneur** : exécution sous utilisateur non-root, image d'exécution minimale (JRE).
- **Audit** : journal append-only des actions sensibles.

> ⚠️ En production : servir derrière HTTPS/TLS, définir un `APP_JWT_SECRET` fort
> et unique, et changer le mot de passe PostgreSQL par défaut.
