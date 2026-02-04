#!/bin/bash

echo "🔌 CONNEXION FRONTEND-BACKEND POUR TESTS RÉELS"
echo "=============================================="

# 1. Create environment file for frontend
echo -e "\n1️⃣ Création du fichier .env pour le frontend..."
cat > /root/canton-website/app/.env.local << EOF
# Backend API (votre backend Canton)
REACT_APP_BACKEND_API_URL=http://localhost:8080

# Disable mock data - use real backend
REACT_APP_USE_MOCK_DATA=false

# Canton Config
REACT_APP_CANTON_API_URL=http://localhost:8080
REACT_APP_PACKAGE_ID=5ce4bf9f9cd097fa7d4d63b821bd902869354ddf2726d70ed766ba507c3af1b4

# Environment
REACT_APP_ENV=devnet
EOF

echo "✅ Fichier .env.local créé"

# 2. Update party mappings
echo -e "\n2️⃣ Mise à jour des party mappings..."
cat > /root/canton-website/app/src/config/party-mapping.ts << 'EOF'
// Real Canton DevNet Party Mappings
export const DEVNET_PARTY_MAPPING: Record<string, string> = {
  // Existing parties on DevNet
  'app-provider': 'app-provider::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388',
  'alice': 'Alice_RealTrader_2025::1220<hash>',  // To be created
  'bob': 'Bob_RealTrader_2025::1220<hash>',      // To be created
  'charlie': 'Charlie_RealTrader_2025::1220<hash>', // To be created
  
  // Legacy mappings (keep for compatibility)
  'AppProvider': 'app-provider::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388',
};

export function getPartyId(name: string): string {
  return DEVNET_PARTY_MAPPING[name] || name;
}
EOF

echo "✅ Party mappings configurés"

# 3. Create CORS configuration for backend
echo -e "\n3️⃣ Configuration CORS pour le backend..."
cat > /root/cn-quickstart/quickstart/backend/src/main/java/com/digitalasset/quickstart/config/CorsConfig.java << 'EOF'
package com.digitalasset.quickstart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        
        // Allow Netlify and localhost origins
        config.setAllowedOrigins(Arrays.asList(
            "http://localhost:3000",
            "http://localhost:3001", 
            "https://clearportx.netlify.app",
            "https://*.netlify.app"
        ));
        
        // Allow all headers
        config.setAllowedHeaders(Arrays.asList("*"));
        
        // Allow all methods
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        
        // Allow credentials
        config.setAllowCredentials(true);
        
        // Expose headers
        config.setExposedHeaders(Arrays.asList("Authorization", "X-Total-Count"));
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        
        return new CorsFilter(source);
    }
}
EOF

echo "✅ CORS configuré pour accepter le frontend"

# 4. Create test user creation script
echo -e "\n4️⃣ Script pour créer les utilisateurs de test..."
cat > /root/cn-quickstart/quickstart/clearportx/create-test-users.sh << 'EOF'
#!/bin/bash

echo "Creating test users on Canton DevNet..."

# Create Alice
grpcurl -plaintext -d '{
  "party_details": {
    "display_name": "Alice_RealTrader_2025",
    "is_local": true
  }
}' localhost:5001 com.daml.ledger.api.v2.admin.PartyManagementService/AllocateParty | jq -r '.party_details.party' > alice_party_id.txt

# Create Bob
grpcurl -plaintext -d '{
  "party_details": {
    "display_name": "Bob_RealTrader_2025",
    "is_local": true
  }
}' localhost:5001 com.daml.ledger.api.v2.admin.PartyManagementService/AllocateParty | jq -r '.party_details.party' > bob_party_id.txt

# Create Charlie
grpcurl -plaintext -d '{
  "party_details": {
    "display_name": "Charlie_RealTrader_2025",
    "is_local": true
  }
}' localhost:5001 com.daml.ledger.api.v2.admin.PartyManagementService/AllocateParty | jq -r '.party_details.party' > charlie_party_id.txt

echo "Alice Party ID: $(cat alice_party_id.txt)"
echo "Bob Party ID: $(cat bob_party_id.txt)"
echo "Charlie Party ID: $(cat charlie_party_id.txt)"

echo ""
echo "Update party-mapping.ts with these IDs!"
EOF

chmod +x /root/cn-quickstart/quickstart/clearportx/create-test-users.sh

# 5. Start backend with CORS enabled
echo -e "\n5️⃣ Démarrage du backend avec CORS..."
cd /root/cn-quickstart/quickstart/clearportx
./start-backend-production.sh &
BACKEND_PID=$!

echo "Backend PID: $BACKEND_PID"
echo "Attente du démarrage..."
sleep 30

# 6. Test backend connectivity
echo -e "\n6️⃣ Test de connectivité..."
if curl -s http://localhost:8080/api/pools > /dev/null; then
    echo "✅ Backend accessible"
    curl -s http://localhost:8080/api/pools | jq '.[0]'
else
    echo "❌ Backend non accessible"
fi

echo -e "\n✅ CONFIGURATION TERMINÉE!"
echo ""
echo "PROCHAINES ÉTAPES:"
echo "1. Créer les utilisateurs de test: ./create-test-users.sh"
echo "2. Mettre à jour party-mapping.ts avec les IDs générés"
echo "3. Démarrer le frontend:"
echo "   cd /root/canton-website/app"
echo "   npm start"
echo "4. Accéder à http://localhost:3000"
echo "5. Se connecter avec 'alice', 'bob' ou 'charlie'"
echo ""
echo "📱 Pour tester depuis Netlify:"
echo "   - Utiliser un tunnel (ngrok) pour exposer localhost:8080"
echo "   - Ou déployer le backend sur un serveur public"
EOF
