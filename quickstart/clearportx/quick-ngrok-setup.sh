#!/bin/bash

echo "🌐 CONFIGURATION RAPIDE NGROK POUR APP.CLEARPORTX.COM"
echo "====================================================="

# Vérifier si ngrok est installé
if ! command -v ngrok &> /dev/null; then
    echo "📦 Installation de ngrok..."
    wget -q https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-amd64.tgz
    tar xzf ngrok-v3-stable-linux-amd64.tgz
    sudo mv ngrok /usr/local/bin/
    rm ngrok-v3-stable-linux-amd64.tgz
    echo "✅ Ngrok installé"
fi

# Vérifier si le backend est running
echo -e "\n🔍 Vérification du backend..."
if ! curl -s http://localhost:8080/api/pools > /dev/null 2>&1; then
    echo "❌ Backend non détecté sur port 8080"
    echo "Veuillez d'abord démarrer le backend avec:"
    echo "  ./start-backend-production.sh"
    exit 1
fi

echo "✅ Backend détecté sur localhost:8080"

# Créer le tunnel
echo -e "\n🚇 Création du tunnel ngrok..."
echo "================================"
echo ""
echo "IMPORTANT: Ngrok va ouvrir une nouvelle fenêtre."
echo ""
echo "1️⃣ Copiez l'URL HTTPS fournie par ngrok (ex: https://abc123.ngrok-free.app)"
echo ""
echo "2️⃣ Dans app.clearportx.com, configurez cette URL comme backend"
echo "   - Soit dans les paramètres de l'app"
echo "   - Soit via la console du navigateur (F12):"
echo "     localStorage.setItem('BACKEND_URL', 'https://YOUR-NGROK-URL.ngrok-free.app')"
echo ""
echo "3️⃣ Utilisateurs de test disponibles:"
echo "   - app-provider (a des pools)"
echo "   - Créez de nouveaux utilisateurs via l'UI"
echo ""
echo "Appuyez sur ENTER pour démarrer ngrok..."
read

# Start ngrok
ngrok http 8080
