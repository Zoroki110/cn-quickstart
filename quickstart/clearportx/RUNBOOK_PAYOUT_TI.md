0) TransferFactory probes (documented endpoint + payload)
Use a real operator-owned holding in inputHoldingCids.

BASE=http://localhost:8080
OP=``"ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37"``
RX="3f5cab62227096155dd237686093dc95::12205e4067e63c53ef877725e63da505cc27169a000db739fd82fe0065d2bc76eac8"
AMULET_ADMIN="DSO::1220be58c29e65de40bf273be1dc2b266d43a9a002ea5b18955aeef7aac881bb471a"
CBTC_ADMIN="cbtc-network::12202a83c6f4082217c175e29bc53da5f2703ba2675778ab99217a5a881a949203ff"

OP_ENC=$(python3 - <<'PY'
import urllib.parse
print(urllib.parse.quote("ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37", safe=""))
PY
)

REQ_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EXEC_BEFORE=$(date -u -d '+2 hours' +%Y-%m-%dT%H:%M:%SZ)

AMU_HOLDING=$(curl -s "$BASE/api/holdings/$OP_ENC/utxos" | jq -r --arg admin "$AMULET_ADMIN" --arg id "Amulet" --arg owner "$OP" '.[] | select(.instrumentAdmin==$admin and .instrumentId==$id and .owner==$owner) | .contractId' | head -n1)
CBTC_HOLDING=$(curl -s "$BASE/api/holdings/$OP_ENC/utxos" | jq -r --arg admin "$CBTC_ADMIN" --arg id "CBTC" --arg owner "$OP" '.[] | select(.instrumentAdmin==$admin and .instrumentId==$id and .owner==$owner) | .contractId' | head -n1)

# Amulet (Scan, sync.global)
curl -s -X POST "https://scan.sv-1.dev.global.canton.network.sync.global/registry/transfer-instruction/v1/transfer-factory" \
  -H "Content-Type: application/json" \
  -d '{
    "choiceArguments":{
      "expectedAdmin":"'"$AMULET_ADMIN"'",
      "transfer":{
        "sender":"'"$OP"'","receiver":"'"$RX"'","amount":"10.0000000000",
        "instrumentId":{"admin":"'"$AMULET_ADMIN"'","id":"Amulet"},
        "requestedAt":"'"$REQ_AT"'","executeBefore":"'"$EXEC_BEFORE"'",
        "inputHoldingCids":["'"$AMU_HOLDING"'"],
        "meta":{"values":{}}
      },
      "extraArgs":{"context":{"values":{}}, "meta":{"values":{}}}
    }
  }' | jq

# CBTC (Utilities registrar)
ADMIN_CBTC_ENC=$(python3 - <<'PY'
import urllib.parse
print(urllib.parse.quote("cbtc-network::12202a83c6f4082217c175e29bc53da5f2703ba2675778ab99217a5a881a949203ff", safe=""))
PY
)

curl -s -X POST "https://api.utilities.digitalasset-dev.com/api/token-standard/v0/registrars/${ADMIN_CBTC_ENC}/registry/transfer-instruction/v1/transfer-factory" \
  -H "Content-Type: application/json" \
  -d '{
    "choiceArguments":{
      "expectedAdmin":"'"$CBTC_ADMIN"'",
      "transfer":{
        "sender":"'"$OP"'","receiver":"'"$RX"'","amount":"0.0010000000",
        "instrumentId":{"admin":"'"$CBTC_ADMIN"'","id":"CBTC"},
        "requestedAt":"'"$REQ_AT"'","executeBefore":"'"$EXEC_BEFORE"'",
        "inputHoldingCids":["'"$CBTC_HOLDING"'"],
        "meta":{"values":{}}
      },
      "extraArgs":{"context":{"values":{}}, "meta":{"values":{}}}
    }
  }' | jq

Expected: JSON with factoryId, choiceContext, disclosedContracts.

1) Create payouts (backend)

BASE=http://localhost:8080
RX="3f5cab62227096155dd237686093dc95::12205e4067e63c53ef877725e63da505cc27169a000db739fd82fe0065d2bc76eac8"

curl -s -X POST "$BASE/api/devnet/payout/amulet" -H "Content-Type: application/json" \
  -d '{"receiverParty":"'"$RX"'","amount":"10.0000000000","executeBeforeSeconds":7200,"memo":"gate0.5-amulet-10"}' | jq

curl -s -X POST "$BASE/api/devnet/payout/cbtc" -H "Content-Type: application/json" \
  -d '{"receiverParty":"'"$RX"'","amount":"0.0010000000","executeBeforeSeconds":7200,"memo":"gate0.5-cbtc-0.0010"}' | jq

Expected: JSON with factoryId, choiceContext, disclosedContracts.

2) Verify outgoing → accept in Loop → outgoing disappears

OP="ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37"
OP_ENC=$(python3 - <<'PY'
import urllib.parse
print(urllib.parse.quote("ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37", safe=""))
PY
)

# Outgoing visible
curl -s "$BASE/api/devnet/transfer-instructions/outgoing?senderParty=${OP_ENC}&instrumentAdmin=${AMULET_ADMIN}&instrumentId=Amulet" | jq
curl -s "$BASE/api/devnet/transfer-instructions/outgoing?senderParty=${OP_ENC}&instrumentAdmin=${CBTC_ADMIN}&instrumentId=CBTC" | jq

# Accept in Loop (manual), then re-run outgoing to confirm disappearance.s