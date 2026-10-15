# TCM Backend — édition Go

Réimplémentation Go autonome du backend TCM basé sur le projet Java/Spring Boot situé dans le dépôt parent. Cette édition est isolée dans `golang-version/` et ne modifie pas l’application Java existante.

> **État du port :** il s’agit d’une première version fonctionnelle du cœur API, pas encore d’une parité 1:1 avec les 442 classes Java. Les flux ou intégrations explicitement indiqués « à compléter » ne sont pas présentés comme terminés.

## Prérequis et démarrage

- Go 1.22+
- MySQL 8+ avec le schéma TCM/RuoYi existant

Le serveur n’exécute pas de migrations automatiquement. Le script historique `../sql/tcm_all_in_one.sql` contient des `DROP TABLE`; ne le lancez pas sur une base contenant des données à conserver. Faites une sauvegarde et suivez la procédure de migration du projet source.

```sh
cd golang-version
export DB_HOST=127.0.0.1 DB_PORT=3306 DB_USER=tcm DB_PASSWORD='…' DB_NAME=tcm
export JWT_SECRET="$(openssl rand -hex 32)"
go mod download
go test ./...
go run ./cmd/tcm-server
```

Par défaut, le serveur écoute sur `:8080`; `GET /health` fonctionne même sans base. Les routes métier exigent une connexion MySQL. On peut fournir un DSN complet à la place des paramètres DB :

```sh
DB_DSN='user:password@tcp(127.0.0.1:3306)/tcm?parseTime=true&charset=utf8mb4&loc=America%2FToronto' go run ./cmd/tcm-server
```

En `APP_ENV=production`, `JWT_SECRET` est obligatoire. Une clé de développement de secours n’est utilisée qu’en local, avec avertissement. Configurez `CORS_ORIGIN`, `PUBLIC_APP_BASE_URL`, `PUBLIC_API_BASE_URL` et `STORAGE_DIR` selon le déploiement. Les téléversements sont limités à 8 MiB et stockés localement avec permissions restreintes.

## Fonctionnalités portées

- `GET /health`
- Auth : `POST /api/auth/login`, `GET /api/auth/me`, modification et réinitialisation du mot de passe ; jetons signés HS256, expiration, contrôle des rôles et comparaison BCrypt des mots de passe MySQL.
- CRUD métier par table du schéma pour patients, rendez-vous, consultations, branches, formules, dictionnaire d’herbes, inventaire, méridiens, points d’acupuncture, fournisseurs, modèles, conversions d’unités, salles, tarifs, types de service, journaux d’e-mail et fichiers.
- Actions principales : suppression logique lorsque la table a `deleted_at`, transitions de rendez-vous/consultation, fusion transactionnelle de patients, paiements stockés dans `payload`, ajustement de stock et historique.
- Réservation publique : options, une disponibilité journalière simplifiée, validation de conflit pour un créneau, création de réservation.
- Consentement public et formulaires d’anamnèse par jeton, avec signature, expiration et stockage dans `payload`. Le bouton d’envoi ne transmet pas de courriel sans SMTP : la réponse signale clairement `sent: false`.
- Pièces jointes : upload, liste par patient/consultation, téléchargement ZIP, suppression et URL d’accès signées à expiration.
- Paramètres de clinique et aperçu statistique.

Les colonnes SQL sont découvertes dans `information_schema`; les identifiants SQL dynamiques sont validés. Les champs additionnels des ressources qui ont une colonne `payload` y sont encodés en JSON. Les appels protégés attendent `Authorization: Bearer <token>`.

## Parité restant à réaliser

- Règles exactes de confidentialité par rôle, affectation des patients, audit complet, validations de domaine, verrouillage/versioning des consultations et synchronisation complète des prescriptions/stock.
- Réservation publique de parité complète (horaires par praticien, règles par salle et service, fuseau et fenêtres de réservation, chevauchements complexes, libération progressive des créneaux, gestion/cancellation du jeton).
- Envoi d’e-mails et notifications, modèles de courriel, PDFs de consultation/facture et stockage S3/R2 signé; le port actuel ne remplace que le stockage local des pièces jointes.
- Stripe (checkout, terminal, webhooks), reconnaissance/import de factures et assistant IA : nécessitent des services externes/configuration.
- Import/export bootstrap, CRUD complet d’administration RuoYi (utilisateurs/rôles/menus/configuration/jobs), diagnostics et outils opérationnels.
- Quelques routes secondaires TCM (suppression/restauration de prescription, consultation par versions, liens manage, exports) restent à implémenter.

Avant d’utiliser cette édition avec des données réelles de santé, achevez ces écarts, ajoutez des tests d’intégration MySQL et validez la compatibilité avec le frontend `tcm-app`, puis réalisez une revue sécurité/confidentialité. Les réponses `501` ne doivent pas être contournées par le frontend.

## Tests et build

```sh
gofmt -w cmd/tcm-server
go test ./...
go vet ./...
go build -o bin/tcm-server ./cmd/tcm-server
```

Les tests actuels couvrent JWT, liens d’accès signés, mapping des clés, compatibilité de rôles et `/health`. Aucun serveur MySQL de test n’est fourni.
