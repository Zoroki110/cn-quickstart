#!/bin/bash

echo "🚀 CONFIGURATION RAPIDE POUR NETLIFY"
echo "===================================="
echo ""

# 1. Check backend
echo "1️⃣ Vérification du backend..."
if ! curl -s http://localhost:8080/api/pools > /dev/null 2>&1; then
    echo "   ⚠️  Backend non détecté, démarrage..."
    ./start-backend-production.sh &
    echo "   Attente du démarrage (30s)..."
    sleep 30
fi

echo "   ✅ Backend running sur localhost:8080"

# 2. Installer ngrok si nécessaire
if ! command -v ngrok &> /dev/null; then
    echo -e "\n2️⃣ Installation de ngrok..."
    wget -q https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-amd64.tgz
    tar xzf ngrok-v3-stable-linux-amd64.tgz
    sudo mv ngrok /usr/local/bin/
    rm ngrok-v3-stable-linux-amd64.tgz
    echo "   ✅ Ngrok installé"
else
    echo -e "\n2️⃣ Ngrok déjà installé ✅"
fi

# 3. Instructions
echo -e "\n📋 INSTRUCTIONS POUR CONNECTER NETLIFY:"
echo "======================================="
echo ""
echo "1️⃣ Démarrez ngrok dans un nouveau terminal:"
echo "   ngrok http 8080"
echo ""
echo "2️⃣ Copiez l'URL HTTPS fournie par ngrok"
echo "   Exemple: https://abc123.ngrok-free.app"
echo ""
echo "3️⃣ Dans Netlify Dashboard:"
echo "   → Site configuration"
echo "   → Environment variables"
echo "   → Add a variable:"
echo ""
echo "   REACT_APP_BACKEND_API_URL = https://YOUR-NGROK-URL.ngrok-free.app"
echo "   REACT_APP_USE_MOCK_DATA = false"
echo ""
echo "4️⃣ Redéployez votre site:"
echo "   → Deploys"
echo "   → Trigger deploy → Clear cache and deploy"
echo ""
echo "5️⃣ Testez sur app.clearportx.com:"
echo "   - Les pools doivent apparaître"
echo "   - F12 → Network doit montrer les requêtes vers ngrok"
echo ""
echo "📌 NGROK DASHBOARD:"
echo "   http://localhost:4040 (quand ngrok est running)"
echo ""
echo "✅ Votre backend Canton est prêt pour Netlify!"
echo ""
echo "Appuyez sur ENTER pour ouvrir ngrok..."
read

# Ouvrir ngrok
echo "Ouverture de ngrok..."
ngrok http 8080
