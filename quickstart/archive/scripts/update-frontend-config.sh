#!/bin/bash

# Script to update frontend configuration with ngrok URL

echo "🔧 UPDATE FRONTEND CONFIGURATION"
echo "================================"
echo ""

# Get the ngrok URL from the user
echo "📌 Votre URL ngrok est: https://nonexplicable-lacily-leesa.ngrok-free.dev"
echo ""
echo "Pour configurer le frontend Netlify:"
echo ""
echo "1. Allez dans Netlify Dashboard"
echo "2. Site settings → Environment variables"
echo "3. Ajoutez ces variables:"
echo ""
echo "   REACT_APP_BACKEND_API_URL = https://nonexplicable-lacily-leesa.ngrok-free.dev"
echo "   REACT_APP_CANTON_API_URL = https://nonexplicable-lacily-leesa.ngrok-free.dev"
echo ""
echo "4. Redéployez le site (trigger deploy)"
echo ""
echo "OU pour tester localement:"
echo ""
echo "   cd /root/canton-website"
echo "   export REACT_APP_BACKEND_API_URL=https://nonexplicable-lacily-leesa.ngrok-free.dev"
echo "   export REACT_APP_CANTON_API_URL=https://nonexplicable-lacily-leesa.ngrok-free.dev"
echo "   npm start"
echo ""
echo "⚠️  IMPORTANT: L'URL ngrok change à chaque redémarrage!"
echo "    Mettez à jour les variables d'environnement à chaque fois."
