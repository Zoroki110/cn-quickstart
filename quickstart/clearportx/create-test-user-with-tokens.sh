#!/bin/bash

echo "🧪 CRÉATION D'UN UTILISATEUR TEST AVEC TOKENS"
echo "============================================"
echo ""

# 1. Créer un utilisateur alice
echo "1️⃣ Création de l'utilisateur alice@clearportx..."

ALICE_RESPONSE=$(grpcurl -plaintext -d '{
    "party_details": {
        "display_name": "alice@clearportx",
        "is_local": true
    }
}' localhost:5001 com.daml.ledger.api.v2.admin.PartyManagementService/AllocateParty 2>&1)

if echo "$ALICE_RESPONSE" | grep -q "party_details"; then
    ALICE_PARTY=$(echo "$ALICE_RESPONSE" | jq -r '.party_details.party')
    echo "✅ Alice créée: $ALICE_PARTY"
else
    # Si alice existe déjà, la trouver
    echo "⚠️  Alice existe peut-être déjà, recherche..."
    ALICE_PARTY=$(grpcurl -plaintext localhost:5001 com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties | jq -r '.party_details[] | select(.party | contains("alice")) | .party' | head -1)
    echo "✅ Alice trouvée: $ALICE_PARTY"
fi

# 2. Créer des issuers pour ETH et USDC
echo ""
echo "2️⃣ Création des issuers de tokens..."

# ETH Issuer
ETH_ISSUER_RESPONSE=$(grpcurl -plaintext -d '{
    "party_details": {
        "display_name": "ETH-Issuer",
        "is_local": true
    }
}' localhost:5001 com.daml.ledger.api.v2.admin.PartyManagementService/AllocateParty 2>&1)

if echo "$ETH_ISSUER_RESPONSE" | grep -q "party_details"; then
    ETH_ISSUER=$(echo "$ETH_ISSUER_RESPONSE" | jq -r '.party_details.party')
    echo "✅ ETH Issuer créé: $ETH_ISSUER"
else
    ETH_ISSUER=$(grpcurl -plaintext localhost:5001 com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties | jq -r '.party_details[] | select(.display_name == "ETH-Issuer" or .party | contains("ETHIssuer") or .party | contains("ethIssuer")) | .party' | head -1)
    echo "✅ ETH Issuer trouvé: $ETH_ISSUER"
fi

# USDC Issuer
USDC_ISSUER_RESPONSE=$(grpcurl -plaintext -d '{
    "party_details": {
        "display_name": "USDC-Issuer",
        "is_local": true
    }
}' localhost:5001 com.daml.ledger.api.v2.admin.PartyManagementService/AllocateParty 2>&1)

if echo "$USDC_ISSUER_RESPONSE" | grep -q "party_details"; then
    USDC_ISSUER=$(echo "$USDC_ISSUER_RESPONSE" | jq -r '.party_details.party')
    echo "✅ USDC Issuer créé: $USDC_ISSUER"
else
    USDC_ISSUER=$(grpcurl -plaintext localhost:5001 com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties | jq -r '.party_details[] | select(.display_name == "USDC-Issuer" or .party | contains("USDCIssuer") or .party | contains("usdcIssuer")) | .party' | head -1)
    echo "✅ USDC Issuer trouvé: $USDC_ISSUER"
fi

# 3. Créer un script DAML pour donner des tokens
echo ""
echo "3️⃣ Création des tokens pour Alice..."

cat > /tmp/give_alice_tokens.daml << EOF
module GiveAliceTokens where

import Daml.Script
import qualified Token.Token as T

giveAliceTokens : Script ()
giveAliceTokens = script do
  -- Parties hardcodées
  let alice = toPartyId "$ALICE_PARTY"
      ethIssuer = toPartyId "$ETH_ISSUER"
      usdcIssuer = toPartyId "$USDC_ISSUER"
  
  -- Créer 100 ETH pour Alice
  submit ethIssuer do
    createCmd T.Token with
      issuer = ethIssuer
      owner = alice
      symbol = "ETH"
      amount = 100.0
      
  -- Créer 50,000 USDC pour Alice
  submit usdcIssuer do
    createCmd T.Token with
      issuer = usdcIssuer
      owner = alice
      symbol = "USDC"
      amount = 50000.0
      
  debug "✅ Tokens créés pour Alice: 100 ETH + 50,000 USDC"
  
  where
    toPartyId : Text -> Party
    toPartyId = error "Replaced at runtime"
EOF

# Remplacer toPartyId par les vraies parties
sed -i "s/toPartyId \"$ALICE_PARTY\"/error \"$ALICE_PARTY\"/g" /tmp/give_alice_tokens.daml
sed -i "s/toPartyId \"$ETH_ISSUER\"/error \"$ETH_ISSUER\"/g" /tmp/give_alice_tokens.daml
sed -i "s/toPartyId \"$USDC_ISSUER\"/error \"$USDC_ISSUER\"/g" /tmp/give_alice_tokens.daml

# 4. Copier le script dans le projet DAML
cp /tmp/give_alice_tokens.daml /root/cn-quickstart/quickstart/clearportx/daml/

# 5. Exécuter le script
echo ""
echo "4️⃣ Exécution du script pour créer les tokens..."
cd /root/cn-quickstart/quickstart/clearportx
daml script --dar .daml/dist/clearportx-fees-1.0.0.dar --script-name GiveAliceTokens:giveAliceTokens --ledger-host localhost --ledger-port 5001

# 6. Sauvegarder les informations
echo ""
echo "5️⃣ Sauvegarde des informations..."
cat > frontend_test_user.json << EOF
{
  "user": "alice@clearportx",
  "party": "$ALICE_PARTY",
  "password": "alice123",
  "tokens": {
    "ETH": {
      "amount": 100.0,
      "issuer": "$ETH_ISSUER"
    },
    "USDC": {
      "amount": 50000.0,
      "issuer": "$USDC_ISSUER"
    }
  }
}
EOF

echo ""
echo "✅ UTILISATEUR TEST CRÉÉ!"
echo "========================"
echo ""
echo "📋 INFORMATIONS DE CONNEXION:"
echo "-----------------------------"
echo "Username: alice@clearportx"
echo "Password: alice123"
echo "Party ID: $ALICE_PARTY"
echo ""
echo "💰 TOKENS DISPONIBLES:"
echo "---------------------"
echo "- 100 ETH"
echo "- 50,000 USDC"
echo ""
echo "🔑 MAPPING POUR LE FRONTEND:"
echo "----------------------------"
echo "Ajoutez dans backendApi.ts:"
echo ""
echo "const PARTY_MAPPING: Record<string, string> = {"
echo "  'alice@clearportx': '$ALICE_PARTY',"
echo "  'alice': '$ALICE_PARTY',"
echo "  // ..."
echo "};"
echo ""
echo "📄 Données sauvegardées dans: frontend_test_user.json"