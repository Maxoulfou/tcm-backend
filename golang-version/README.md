# TCM Backend — édition Go

Port Go modulaire du backend TCM/RuoYi du dépôt parent. Cette implémentation constitue une base de travail séparée : elle ne remplace pas encore toutes les fonctionnalités Spring Boot et ne doit pas être utilisée avec des données de santé réelles avant revue métier, sécurité et confidentialité.

## Architecture

| Dossier | Responsabilité |
| --- | --- |
| `cmd/tcm-server` | Point d’entrée minimal : délègue au runtime. |
| `runtime` | Configuration, ouverture MySQL, construction du serveur, timeouts et arrêt gracieux. |
| `ruoyi-admin` | Assemblage du routeur et des modules. |
| `ruoyi-common/database` | Store MySQL, validation d’identifiants SQL, introspection, opérations et transactions. |
| `ruoyi-common/httpx` | Réponses JSON et erreurs localisées. |
| `ruoyi-common/i18n` | Catalogue chinois simplifié / anglais sélectionné avec `Accept-Language`. |
| `ruoyi-framework/security` | JWT HS256, middleware d’authentification, CORS allowlist, limites de taille, headers défensifs et rate limit de login. |
| `ruoyi-generator` | Lecture des tables du schéma pour l’administration du générateur. |
| `ruoyi-hospital` | Ressources métier TCM, CRUD générique et réservation publique. |
| `ruoyi-quartz` | Consultation des jobs RuoYi et primitives de planification en mémoire. |
| `System` | Authentification, profil, mots de passe et lecture des utilisateurs système. |
| `tools/checkfunc` | Vérificateur AST : aucune fonction ou méthode ne dépasse 20 lignes, tests compris. |

Les noms `ruoyi-*` suivent les noms canoniques des modules Java, en minuscules pour rester conformes aux conventions Go. Le dossier `System` conserve le nom demandé; son identifiant de package Go est `system`.

## Démarrage

Prérequis : Go 1.22+ et une base MySQL initialisée avec le schéma du projet.

```sh
cd golang-version
cp .env.example .env   # exporter les variables manuellement ou utiliser votre gestionnaire de secrets
export DB_HOST=127.0.0.1 DB_PORT=3306 DB_USER=tcm DB_PASSWORD='…' DB_NAME=tcm
export JWT_SECRET="$(openssl rand -hex 32)"
export CORS_ORIGINS="http://localhost:5173"
make quality
make build
make run
```

Le serveur écoute sur `:8080` par défaut. `DB_DSN` peut remplacer `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD` et `DB_NAME`. Le démarrage vérifie la connexion MySQL avant d’écouter; `/health` est donc disponible une fois l’initialisation réussie. En production, `JWT_SECRET` doit être défini explicitement et faire au moins 32 octets.

Le backend n’exécute pas de migration automatique. Le dump parent `../sql/tcm_all_in_one.sql` contient des `DROP TABLE` : ne le lancez pas sur une base avec des données à conserver. Utilisez une sauvegarde et la procédure de migration du projet source.

## Sécurité et conventions

- **JWT** : HS256 seulement, signature vérifiée en temps constant, issuer, `exp` et `nbf` contrôlés. Durée actuelle : 12 heures; jeton transmis via `Authorization: Bearer …`, sans cookie.
- **CORS** : origines exactes via `CORS_ORIGINS`; aucun wildcard ni credentials-cookie. Toute origine non déclarée est refusée.
- **XSS** : réponses via `encoding/json` (échappement HTML activé), API sans rendu HTML, CSP restrictive, `nosniff`, `frame-ancestors 'none'`. Les notes et champs cliniques ne sont pas altérés par une « désinfection » globale; l’encodage doit rester contextuel à l’affichage.
- **Entrées** : requête limitée à 8 MiB; identifiants de table/colonne limités à `[A-Za-z0-9_]`; valeurs SQL passées en paramètres. Les routes de login ont une limite locale en mémoire de 8 tentatives par minute et par adresse IP.
- **Serveur** : timeouts HTTP explicites, récupération de panic sans détail interne dans la réponse, arrêt gracieux sur SIGINT/SIGTERM.
- **Rôles** : les handlers appliquent des contrôles simples selon les rôles reçus de `sys_user_role`. Ils ne remplacent pas encore les permissions complètes du système RuoYi, ni les restrictions de portée par patient/branche.
- **Secrets** : ne jamais committer `.env`, mots de passe ou secrets; utiliser un compte MySQL à privilèges minimaux et TLS en production.

Le middleware XSS ne modifie pas les données sauvegardées : le rendu sûr dépend aussi du frontend. Une validation CSP ou CORS ne remplace pas une revue OWASP complète.

## i18n

Le catalogue `ruoyi-common/i18n` fournit des clés stables plutôt que des textes d’erreur dispersés. `Accept-Language: zh-CN` (ou `zh`) sélectionne le chinois simplifié; les autres langues retombent sur l’anglais. Le catalogue contient aussi **124 messages chinois distincts** extraits des appels Java d’exception/réponse (`ServiceException`, `AjaxResult.error`, `R.fail`, `MessageUtils.message`, `setMessage`) avec leur traduction anglaise. `TranslateSource` permet de mapper un littéral source exact; les commentaires techniques ne sont pas traduits.

Le générateur `python3 tools/i18n_catalog.py` ré-extrait ces messages, demande les traductions par petits lots, vérifie les placeholders et régénère `source_messages.go`. Les autres libellés UI, annotations, fragments contextuels ou textes non présents dans ces appels de réponse n’ont pas tous été migrés; ajouter une clé et ses deux textes avant d’introduire une nouvelle erreur visible.

## Routes présentes dans cette tranche

- `GET /health`
- `POST /api/auth/login`, `GET /api/auth/me`, `POST /api/auth/change-password`, `POST /api/auth/reset-password`
- `GET /api/users` (admin) et `GET /api/generator/tables` (admin)
- `GET /api/monitor/job` (admin; lecture de `sys_job`)
- CRUD de base pour patients, rendez-vous, consultations, branches, formules, dictionnaire d’herbes, inventaire, méridiens, points d’acupuncture, fournisseurs, modèles, conversions, salles, tarifs, types de service, journaux e-mail et fichiers.
- Réservation publique : `GET /api/public-booking/options`, `GET /api/public-booking/availability?date=YYYY-MM-DD&serviceType=…`, `GET /api/public-booking/schedule?...` et `POST /api/public-booking`.
- `POST /api/appointments/check-slot` pour vérifier un créneau; l’écriture publique est transactionnelle et sérialise les créations par type de service.

Le CRUD générique ne constitue pas une implémentation complète des règles cliniques. Les API métier spécialisées, les schémas exacts et la compatibilité frontend doivent être validés avant usage.

## À implémenter pour la parité

Les liens et signatures de consentement/anamnèse, fichiers téléversés et URL signées, fusion de patients, paiements/inventaire spécialisés, règles complètes d’agenda par praticien/salle, audit et versionnage des consultations, emails, Stripe, exports, statistiques, CRUD complet des rôles/menus/configuration et exécution persistante des jobs Quartz ne sont pas encore portés dans cette structure. Quartz expose pour l’instant la lecture des jobs et un scheduler en mémoire, pas l’exécution du moteur cron RuoYi.

Les autres textes interactifs Java chinois, les contrôles d’accès précis par patient et branche, les validations de domaine et les tests d’intégration MySQL restent des étapes requises. La version Go doit passer une revue de sécurité et de confidentialité avant toute exposition publique.

## Qualité

```sh
make quality       # go test -race, go vet, contrôle AST des 20 lignes
make build         # binaire autonome dans bin/tcm-server
```
