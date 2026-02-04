#!/bin/bash
# Script to mint test tokens for alice on Canton ledger
# This uses the Canton console to create tokens directly

set -e

echo "🪙 Minting tokens for alice..."

# Get alice's full party ID
ALICE_PARTY=$(docker exec canton canton-console --config /app/app.conf -c "app_user_participant.parties.list().find(_.displayName == \"alice\").map(_.party)" 2>/dev/null | grep -o "alice::[a-zA-Z0-9]*" || echo "alice")

echo "Alice party ID: $ALICE_PARTY"

# Note: This is a placeholder script
# The actual token minting depends on your DAML token template
# You need to either:
# 1. Use the Splice token standard (if available)
# 2. Or create custom tokens using your Token template

echo "⚠️  Token minting requires the specific Token template from your DAML model"
echo "Please check the DAML code for the correct template and choice names"

echo ""
echo "Alternative: Use the backend test endpoint to mint tokens"
echo "If your backend has a test/admin endpoint for minting, use:"
echo ""
echo "curl -X POST http://localhost:8080/api/admin/mint-token \\"
echo "  -H 'Content-Type: application/json' \\"
echo "  -d '{
  \"party\": \"alice\",
  \"symbol\": \"ETH\",
  \"amount\": \"10.0\"
}'"

# For now, let's just verify alice exists
docker exec canton canton-console --config /app/app.conf -c "app_user_participant.parties.list()" 2>/dev/null | grep -i alice || echo "❌ Alice not found"
