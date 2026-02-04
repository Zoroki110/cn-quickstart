# Plan de Mise à Jour Splice 0.5.1 → 0.5.4

## Résumé Exécutif

**Objectif**: Mettre à jour le validateur ClearportX-DEX-1 de Splice 0.5.1 vers 0.5.4 sans perte de données ni recréation du validateur.

**Réponse à ta question**: **OUI, c'est possible de juste mettre à jour sans supprimer le validateur**. Une mise à jour "rolling" est supportée car il n'y a pas de breaking changes majeurs entre 0.5.1 et 0.5.4.

---

## Configuration Actuelle

| Élément | Valeur |
|---------|--------|
| Version Splice | 0.5.1 |
| Version Canton Local | 0.4.22 |
| Participant ID | ClearportX-DEX-1 |
| Party | `ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37` |
| Image Repository | `ghcr.io/digital-asset/decentralized-canton-sync/docker/` |
| Migration ID | 1 |
| Base de données | PostgreSQL 14 |

---

## Changements entre 0.5.1 et 0.5.4

### ⚠️ Version 0.5.2 - SKIP
**Ne pas installer** - Cette version a un bug critique de pruning.

### Version 0.5.3
- **Nouveautés**: Meilleure gestion retry sequencer, timeout 15s
- **Fix**: Bug featured app proxies
- **⚠️ HELM OBLIGATOIRE**: `scan.publicUrl` et `scan.internalUrl` maintenant requis (N/A pour docker-compose)

### Version 0.5.4
- **⚠️ Breaking Change**: Docker-compose expose uniquement `127.0.0.1` par défaut
  - Solution: Utiliser flag `-E` ou `HOST_BIND_IP=0.0.0.0`
- **Nouveauté**: Support HTTP proxy (`https.proxyHost`, `https.proxyPort`)
- **Fix Critique**: Bug de pruning participant de 0.5.0/0.5.1 corrigé
- **Fix Performance**: Regression multi-minutes processing corrigée

---

## Pré-requis

### 1. Vérification de l'état actuel
```bash
# Vérifier que le validateur est sain
cd /root/splice-node/docker-compose/validator
docker compose ps

# Vérifier la connectivité ledger
grpcurl -plaintext localhost:5001 \
  com.daml.ledger.api.v2.StateService/GetLedgerEnd

# Vérifier les pools ClearportX
curl -s http://localhost:8080/api/pools | jq length
```

### 2. Backup de la configuration
```bash
# Créer un répertoire de backup
BACKUP_DIR=/root/splice-backup-$(date +%Y%m%d-%H%M%S)
mkdir -p $BACKUP_DIR

# Copier les fichiers de configuration
cp /root/splice-node/docker-compose/validator/.env $BACKUP_DIR/
cp /root/splice-node/docker-compose/validator/compose.yaml $BACKUP_DIR/
cp /root/splice-node/docker-compose/validator/nginx.conf $BACKUP_DIR/

# Optionnel: Backup de la base de données
docker exec splice-validator-postgres-splice-1 \
  pg_dump -U cnadmin -d participant-1 > $BACKUP_DIR/participant-db.sql

echo "Backup créé dans: $BACKUP_DIR"
```

---

## Étapes de Mise à Jour

### Étape 1: Arrêter proprement les services (sans supprimer)
```bash
cd /root/splice-node/docker-compose/validator

# Arrêt gracieux (garde les volumes)
docker compose stop

# Vérifier que tout est arrêté
docker compose ps
```

### Étape 2: Mettre à jour la version dans .env
```bash
# Backup du .env actuel
cp .env .env.backup-0.5.1

# Modifier IMAGE_TAG de 0.5.1 à 0.5.4
sed -i 's/IMAGE_TAG=0.5.1/IMAGE_TAG=0.5.4/' .env

# Vérifier le changement
grep IMAGE_TAG .env
# Attendu: IMAGE_TAG=0.5.4
```

### Étape 3: Pull des nouvelles images
```bash
# Télécharger les images 0.5.4
docker compose pull

# Vérifier que les images sont téléchargées
docker images | grep "0.5.4"
```

### Étape 4: Ajustement pour le breaking change 0.5.4 (Binding IP)
```bash
# Si tu veux exposer les ports à l'extérieur (pas seulement localhost),
# ajouter cette variable dans .env:
echo "HOST_BIND_IP=0.0.0.0" >> .env

# OU modifier compose.yaml pour forcer le binding (optionnel si déjà configuré)
# Les ports sont déjà exposés comme:
#   - "5001:5001"
#   - "5002:5002"
#   - "8888:80"
# Ce qui devrait fonctionner, mais vérifie après redémarrage
```

### Étape 5: Redémarrer les services
```bash
# Redémarrer avec les nouvelles images
docker compose up -d

# Suivre les logs pour vérifier le démarrage
docker compose logs -f --tail=100
```

### Étape 6: Vérification post-upgrade
```bash
# 1. Vérifier que tous les services sont UP
docker compose ps
# Attendu: Tous les services en "running"

# 2. Vérifier la version des images
docker compose images
# Attendu: Toutes les images montrent "0.5.4"

# 3. Vérifier la connectivité participant
grpcurl -plaintext localhost:5001 \
  com.daml.ledger.api.v2.StateService/GetLedgerEnd
# Attendu: Offset retourné

# 4. Vérifier que le validateur est healthy
docker logs splice-validator-validator-1 2>&1 | tail -20

# 5. Vérifier le wallet UI
curl -s http://localhost:8888/health 2>/dev/null || echo "Nginx proxy check"

# 6. Vérifier ClearportX backend (si démarré)
curl -s http://localhost:8080/api/pools | jq
```

---

## Script de Mise à Jour Automatisé

Créer un script pour automatiser le processus:

```bash
#!/bin/bash
# upgrade-splice-0.5.4.sh

set -e

VALIDATOR_DIR="/root/splice-node/docker-compose/validator"
BACKUP_DIR="/root/splice-backup-$(date +%Y%m%d-%H%M%S)"
NEW_VERSION="0.5.4"

echo "=== Mise à jour Splice vers $NEW_VERSION ==="

# 1. Backup
echo "[1/6] Création du backup..."
mkdir -p $BACKUP_DIR
cp $VALIDATOR_DIR/.env $BACKUP_DIR/
cp $VALIDATOR_DIR/compose.yaml $BACKUP_DIR/
cp $VALIDATOR_DIR/nginx.conf $BACKUP_DIR/ 2>/dev/null || true
echo "Backup créé: $BACKUP_DIR"

# 2. Arrêt des services
echo "[2/6] Arrêt des services..."
cd $VALIDATOR_DIR
docker compose stop

# 3. Mise à jour de la version
echo "[3/6] Mise à jour de IMAGE_TAG vers $NEW_VERSION..."
sed -i "s/IMAGE_TAG=.*/IMAGE_TAG=$NEW_VERSION/" .env
grep IMAGE_TAG .env

# 4. Pull des nouvelles images
echo "[4/6] Téléchargement des images $NEW_VERSION..."
docker compose pull

# 5. Redémarrage
echo "[5/6] Redémarrage des services..."
docker compose up -d

# 6. Vérification
echo "[6/6] Vérification..."
sleep 30  # Attendre le démarrage

echo "=== État des services ==="
docker compose ps

echo ""
echo "=== Vérification Ledger ==="
grpcurl -plaintext localhost:5001 \
  com.daml.ledger.api.v2.StateService/GetLedgerEnd 2>/dev/null \
  && echo "✅ Ledger accessible" \
  || echo "❌ Ledger non accessible (attendre quelques secondes)"

echo ""
echo "=== Mise à jour terminée ==="
echo "Backup disponible: $BACKUP_DIR"
echo "Pour rollback: cp $BACKUP_DIR/.env $VALIDATOR_DIR/.env && docker compose up -d"
```

---

## Rollback en cas de problème

Si quelque chose ne fonctionne pas:

```bash
cd /root/splice-node/docker-compose/validator

# 1. Arrêter les services
docker compose down

# 2. Restaurer l'ancienne configuration
cp /root/splice-backup-XXXXXXXX/.env .env

# 3. Redémarrer avec l'ancienne version
docker compose up -d

# 4. Vérifier
docker compose ps
```

---

## Impact sur ClearportX

### Ce qui ne change PAS:
- Party ID reste le même
- Contracts existants restent sur le ledger
- Pools et tokens ClearportX intacts
- Backend Spring Boot continue de fonctionner

### Ce qui pourrait nécessiter attention:
- **Port binding**: Si le backend ne peut plus accéder au participant après upgrade, vérifier `HOST_BIND_IP=0.0.0.0`
- **Performance**: 0.5.4 corrige une regression de performance, donc les swaps devraient être plus rapides

### Actions recommandées après upgrade:
```bash
# 1. Redémarrer le backend ClearportX pour s'assurer de la reconnexion
cd /root/cn-quickstart/quickstart/clearportx
./start-backend-production.sh

# 2. Vérifier les pools
curl -s http://localhost:8080/api/pools | jq

# 3. Tester un swap
curl -s -X POST http://localhost:8080/api/clearportx/debug/swap-by-cid \
  -H "Content-Type: application/json" \
  -H "X-Party: ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37" \
  -d '{"poolId": "ETH-USDC", "inputSymbol": "USDC", "amountIn": "10", "outputSymbol": "ETH", "minOutput": "0.001"}' | jq
```

---

## Timeline Recommandée

| Étape | Durée | Description |
|-------|-------|-------------|
| Backup | 2 min | Sauvegarde configuration |
| Arrêt services | 1 min | `docker compose stop` |
| Pull images | 5-10 min | Téléchargement selon réseau |
| Redémarrage | 2-3 min | `docker compose up -d` |
| Vérification | 5 min | Tests de santé |
| **Total** | **~15-20 min** | Downtime minimal |

---

## Checklist Finale

- [ ] Backup créé
- [ ] IMAGE_TAG=0.5.4 dans .env
- [ ] Images 0.5.4 téléchargées
- [ ] Tous les services running
- [ ] Ledger accessible (grpcurl)
- [ ] Backend ClearportX reconnecté
- [ ] Pools visibles via API
- [ ] Test swap réussi

---

## Références

- [Splice Release Notes](https://docs.dev.sync.global/release_notes.html)
- [Validator Upgrade Guide](https://docs.dev.sync.global/validator_operator/validator_upgrade.html)
- [Canton Network DevNet](https://docs.dev.sync.global/)