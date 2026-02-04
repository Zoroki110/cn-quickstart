#!/bin/bash

echo "🚀 DÉMARRAGE POUR APP.CLEARPORTX.COM"
echo "===================================="

# 1. Rebuild backend avec CORS
echo "1️⃣ Rebuild du backend avec CORS..."
cd /root/cn-quickstart/quickstart/backend
../gradlew clean build -x test

# 2. Démarrer le backend
echo -e "\n2️⃣ Démarrage du backend..."
cd /root/cn-quickstart/quickstart/clearportx
./start-backend-production.sh &
BACKEND_PID=$!
echo "Backend PID: $BACKEND_PID"

# Attendre que le backend soit prêt
echo "Attente du backend..."
for i in {1..30}; do
    if curl -s http://localhost:8080/api/pools > /dev/null; then
        echo "✅ Backend prêt!"
        break
    fi
    sleep 2
    echo -n "."
done

# 3. Créer les utilisateurs
echo -e "\n3️⃣ Création des utilisateurs..."
cd /root/cn-quickstart/quickstart/clearportx
daml build
daml script \
  --ledger-host localhost \
  --ledger-port 5001 \
  --dar .daml/dist/*.dar \
  --script-name CreateAppUsers:createAppUsers || echo "Users might already exist"

# 4. Afficher l'état
echo -e "\n4️⃣ État du système:"
curl -s http://localhost:8080/api/pools | jq '.[0]'

echo -e "\n✅ SYSTÈME PRÊT!"
echo ""
echo "POUR CONNECTER APP.CLEARPORTX.COM:"
echo "=================================="
echo ""
echo "Option 1 - TUNNEL NGROK (Recommandé pour tests):"
echo "  ./setup-ngrok-tunnel.sh"
echo "  → Vous obtiendrez une URL comme: https://abc123.ngrok.io"
echo "  → Configurez cette URL dans votre frontend"
echo ""
echo "Option 2 - SERVEUR PUBLIC:"
echo "  Déployez ce backend sur un serveur avec IP publique"
echo "  et configurez l'URL dans votre frontend"
echo ""
echo "📱 Dans app.clearportx.com, configurez:"
echo "  - Backend URL: https://YOUR-NGROK-URL.ngrok.io"
echo "  - Ou utilisez les variables d'environnement du frontend"
