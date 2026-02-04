#!/bin/bash

echo "🧪 CRÉATION DE DONNÉES DE TEST POUR APP.CLEARPORTX.COM"
echo "====================================================="
echo ""

# Vérifier le backend
if ! curl -s http://localhost:8080/api/pools > /dev/null 2>&1; then
    echo "❌ Backend non accessible sur localhost:8080"
    echo "   Démarrez d'abord: ./start-backend-production.sh"
    exit 1
fi

echo "✅ Backend détecté"
echo ""

# Options de test
echo "Que voulez-vous créer?"
echo "1. Créer des utilisateurs de test (alice, bob, charlie)"
echo "2. Donner des tokens aux utilisateurs existants"
echo "3. Créer un nouveau pool"
echo "4. Tout faire (1+2+3)"
echo ""
echo -n "Votre choix (1-4): "
read choice

case $choice in
    1)
        echo -e "\n👥 Création des utilisateurs..."
        ./create-canton-users.sh
        ;;
    2)
        echo -e "\n💰 Distribution de tokens..."
        daml script \
            --ledger-host localhost \
            --ledger-port 5001 \
            --dar .daml/dist/*.dar \
            --script-name GiveTokensToUsers:giveTokensToUsers
        ;;
    3)
        echo -e "\n🏊 Création d'un nouveau pool..."
        echo "Fonctionnalité à venir..."
        ;;
    4)
        echo -e "\n🚀 Création complète de l'environnement de test..."
        ./create-canton-users.sh
        sleep 2
        daml script \
            --ledger-host localhost \
            --ledger-port 5001 \
            --dar .daml/dist/*.dar \
            --script-name GiveTokensToUsers:giveTokensToUsers
        ;;
    *)
        echo "Choix invalide"
        exit 1
        ;;
esac

echo -e "\n✅ DONNÉES DE TEST CRÉÉES!"
echo ""
echo "📱 Dans app.clearportx.com, vous pouvez maintenant:"
echo "- Se connecter avec: alice@clearportx, bob@clearportx, charlie@clearportx"
echo "- alice a: 100 ETH + 50,000 USDC"
echo "- bob a: 50 ETH + 100,000 USDC"
echo "- charlie a: 200 ETH + 25,000 USDC"
echo ""
echo "💡 N'oubliez pas de configurer l'URL ngrok dans Netlify!"
