#!/bin/bash

echo "🌐 CONNEXION APP.CLEARPORTX.COM → BACKEND CANTON DEVNET"
echo "======================================================="

# 1. Option 1: Tunnel local (pour tests rapides)
echo -e "\n📡 OPTION 1: TUNNEL NGROK (Recommandé pour tests)"
echo "================================================"
cat > setup-ngrok-tunnel.sh << 'EOF'
#!/bin/bash

# Installer ngrok si nécessaire
if ! command -v ngrok &> /dev/null; then
    echo "Installation de ngrok..."
    curl -s https://ngrok-agent.s3.amazonaws.com/ngrok.asc | sudo tee /etc/apt/trusted.gpg.d/ngrok.asc >/dev/null
    echo "deb https://ngrok-agent.s3.amazonaws.com buster main" | sudo tee /etc/apt/sources.list.d/ngrok.list
    sudo apt update && sudo apt install ngrok
fi

# Démarrer le backend
echo "Démarrage du backend..."
cd /root/cn-quickstart/quickstart/clearportx
./start-backend-production.sh &
sleep 30

# Créer tunnel ngrok
echo "Création du tunnel ngrok..."
ngrok http 8080 --log-level=info
EOF

chmod +x setup-ngrok-tunnel.sh

# 2. Configuration CORS pour accepter app.clearportx.com
echo -e "\n🔐 Configuration CORS pour app.clearportx.com"
echo "============================================="
cat > /root/cn-quickstart/quickstart/backend/src/main/java/com/digitalasset/quickstart/config/CorsConfig.java << 'EOF'
package com.digitalasset.quickstart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import java.util.Arrays;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        
        // Autoriser app.clearportx.com et localhost
        config.setAllowedOrigins(Arrays.asList(
            "https://app.clearportx.com",
            "https://clearportx.com",
            "http://localhost:3000",
            "http://localhost:3001"
        ));
        
        // Autoriser tous les headers nécessaires
        config.setAllowedHeaders(Arrays.asList(
            "Origin",
            "Content-Type",
            "Accept",
            "Authorization",
            "X-Requested-With",
            "X-Idempotency-Key"
        ));
        
        // Autoriser toutes les méthodes HTTP
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));
        
        // Autoriser les credentials (cookies, auth headers)
        config.setAllowCredentials(true);
        
        // Headers exposés au frontend
        config.setExposedHeaders(Arrays.asList(
            "Authorization",
            "X-Total-Count",
            "X-Rate-Limit-Remaining"
        ));
        
        // Durée du cache preflight
        config.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        
        return new CorsFilter(source);
    }
}
EOF

# 3. Créer les utilisateurs Canton pour l'app
echo -e "\n👥 Création des utilisateurs Canton"
echo "===================================="
cat > /root/cn-quickstart/quickstart/clearportx/create-app-users.daml << 'EOF'
module CreateAppUsers where

import Daml.Script
import qualified Token.Token as T

createAppUsers : Script ()
createAppUsers = script do
  debug "=== CRÉATION DES UTILISATEURS POUR APP.CLEARPORTX.COM ==="
  
  -- Créer Alice
  alice <- allocateParty "alice@clearportx"
  debug $ "Alice créée: " <> show alice
  
  -- Créer Bob  
  bob <- allocateParty "bob@clearportx"
  debug $ "Bob créé: " <> show bob
  
  -- Créer Charlie
  charlie <- allocateParty "charlie@clearportx"
  debug $ "Charlie créé: " <> show charlie
  
  -- Get existing issuers
  parties <- listKnownParties
  let findIssuer symbol = find (\p -> symbol `isInfixOf` show p.party) parties
  
  -- Donner des tokens aux utilisateurs
  case (findIssuer "ETH", findIssuer "USDC") of
    (Some ethIssuerDetails, Some usdcIssuerDetails) -> do
      let ethIssuer = ethIssuerDetails.party
          usdcIssuer = usdcIssuerDetails.party
      
      -- Alice: 100 ETH + 50,000 USDC
      submit ethIssuer do
        createCmd T.Token with
          issuer = ethIssuer
          owner = alice
          symbol = "ETH"
          amount = 100.0
          
      submit usdcIssuer do
        createCmd T.Token with
          issuer = usdcIssuer
          owner = alice
          symbol = "USDC"
          amount = 50000.0
      
      -- Bob: 50 ETH + 100,000 USDC
      submit ethIssuer do
        createCmd T.Token with
          issuer = ethIssuer
          owner = bob
          symbol = "ETH"
          amount = 50.0
          
      submit usdcIssuer do
        createCmd T.Token with
          issuer = usdcIssuer
          owner = bob
          symbol = "USDC"
          amount = 100000.0
      
      -- Charlie: 200 ETH + 25,000 USDC
      submit ethIssuer do
        createCmd T.Token with
          issuer = ethIssuer
          owner = charlie
          symbol = "ETH"
          amount = 200.0
          
      submit usdcIssuer do
        createCmd T.Token with
          issuer = usdcIssuer
          owner = charlie
          symbol = "USDC"
          amount = 25000.0
      
      debug "✅ Tokens distribués à tous les utilisateurs!"
      
    _ -> debug "❌ Issuers not found"
    
  debug "\n📋 RÉSUMÉ DES UTILISATEURS CRÉÉS:"
  debug "- alice@clearportx: 100 ETH + 50,000 USDC"
  debug "- bob@clearportx: 50 ETH + 100,000 USDC"  
  debug "- charlie@clearportx: 200 ETH + 25,000 USDC"
  
  where
    isInfixOf needle haystack = 
      needle == take (length needle) haystack || 
      (length haystack > length needle && isInfixOf needle (drop 1 haystack))
    length t = if t == "" then 0 else 1 + length (drop 1 t)
    take n t = if n <= 0 || t == "" then "" else implode [head (explode t)] <> take (n-1) (drop 1 t)
    head (x::_) = x
    head [] = error "empty"
EOF

# 4. Script pour démarrer tout
echo -e "\n🚀 Script de démarrage complet"
echo "=============================="
cat > /root/cn-quickstart/quickstart/clearportx/start-for-clearportx-app.sh << 'EOF'
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
EOF

chmod +x /root/cn-quickstart/quickstart/clearportx/start-for-clearportx-app.sh

# 5. Instructions finales
echo -e "\n✅ SCRIPTS CRÉÉS!"
echo ""
echo "📋 INSTRUCTIONS:"
echo "==============="
echo ""
echo "1️⃣ Démarrer le système complet:"
echo "   ./start-for-clearportx-app.sh"
echo ""
echo "2️⃣ Créer un tunnel public (pour que app.clearportx.com puisse accéder):"
echo "   ./setup-ngrok-tunnel.sh"
echo ""
echo "3️⃣ Dans votre frontend (app.clearportx.com), configurez:"
echo "   - L'URL du backend: https://YOUR-NGROK-URL.ngrok.io"
echo "   - Ou modifiez les variables d'environnement"
echo ""
echo "4️⃣ Connectez-vous avec:"
echo "   - Username: alice@clearportx (100 ETH + 50k USDC)"
echo "   - Username: bob@clearportx (50 ETH + 100k USDC)"
echo "   - Username: charlie@clearportx (200 ETH + 25k USDC)"
echo ""
echo "🔍 Debug avec F12:"
echo "   - Vérifiez les appels API dans Network"
echo "   - Les requêtes doivent aller vers votre backend ngrok"
echo "   - Vérifiez les CORS headers dans les réponses"
