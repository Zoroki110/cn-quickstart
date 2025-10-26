#!/bin/bash

echo "👥 CRÉATION D'UTILISATEURS CANTON POUR APP.CLEARPORTX.COM"
echo "========================================================"

# Fonction pour créer un utilisateur
create_user() {
    local username=$1
    local display_name=$2
    
    echo -e "\n📝 Création de $display_name..."
    
    RESPONSE=$(grpcurl -plaintext -d "{
        \"party_details\": {
            \"display_name\": \"$display_name\",
            \"is_local\": true
        }
    }" localhost:5001 com.daml.ledger.api.v2.admin.PartyManagementService/AllocateParty 2>&1)
    
    if echo "$RESPONSE" | grep -q "party_details"; then
        PARTY_ID=$(echo "$RESPONSE" | jq -r '.party_details.party')
        echo "✅ Créé: $display_name"
        echo "   Party ID: $PARTY_ID"
        echo "$username=$PARTY_ID" >> canton_users.txt
    else
        echo "⚠️  $display_name existe peut-être déjà"
    fi
}

# Créer les utilisateurs
echo "Création des utilisateurs de test..."

create_user "alice" "alice@clearportx"
create_user "bob" "bob@clearportx"
create_user "charlie" "charlie@clearportx"

echo -e "\n📄 Utilisateurs sauvegardés dans: canton_users.txt"

# Afficher les mappings
echo -e "\n🔑 MAPPING POUR LE FRONTEND:"
echo "============================"
if [ -f canton_users.txt ]; then
    cat canton_users.txt
fi

echo -e "\n💡 UTILISATION:"
echo "==============="
echo "1. Copiez ces party IDs dans votre frontend"
echo "2. Ou utilisez directement les display names dans l'app"
echo "3. Le backend fera la résolution automatiquement"
