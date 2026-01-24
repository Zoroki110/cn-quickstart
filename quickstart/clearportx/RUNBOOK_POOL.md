BASE=http://localhost:8080
OP="ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37"
OP_ENC=$(python3 - <<'PY'
import urllib.parse
print(urllib.parse.quote("ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37", safe=""))
PY
)

# 0) Vérifier les TIs encore présentes (si besoin)
curl -s "$BASE/api/devnet/bootstrap-tis?receiverParty=${OP_ENC}&amuletAmount=85000&cbtcAmount=0.1&maxAgeSeconds=7200" | jq
curl -s "$BASE/api/devnet/transfer-instructions/debug?receiverParty=${OP_ENC}" | jq

# 1) Archiver l’ancienne pool Active
OLD_POOL="00635648bcc0ace44430803334fca76ebc6e30dff9a61c8d515c361f5e3370a1cdca121220d4c2e987687f7af4bf21bf04ccbf7b70858a9c11cb9d2235a1abc90ddbe18144"
curl -s -X POST "$BASE/api/holding-pools/${OLD_POOL}/archive" | jq

# 2) Créer une nouvelle pool Uninitialized
ADMIN_AMULET="DSO::1220be58c29e65de40bf273be1dc2b266d43a9a002ea5b18955aeef7aac881bb471a"
ADMIN_CBTC="cbtc-network::12202a83c6f4082217c175e29bc53da5f2703ba2675778ab99217a5a881a949203ff"
curl -s -X POST "$BASE/api/holding-pools" -H "Content-Type: application/json" -d '{
  "poolId": "pool-cbtc-amulet-prod",
  "instrumentA": { "admin": "'"$ADMIN_AMULET"'", "id": "Amulet" },
  "instrumentB": { "admin": "'"$ADMIN_CBTC"'", "id": "CBTC" },
  "feeBps": 30
}' | jq
# -> récupérer NEW_POOL_CID

# 3) Bootstrap auto-consommateur
NEW_POOL_CID="<PASTE_NEW_CID>"
curl -s -X POST "$BASE/api/holding-pools/${NEW_POOL_CID}/bootstrap" -H "Content-Type: application/json" -d '{
  "tiCidA": "0025b856c3bd6e3844f0e6d429ce9d96a4ff633860d6736ec3d7ac5eed1cd7c0f3ca1212202fac45a695b5981610b1413c52866d32896234fb8a01cbb41e67f48ada07a473",
  "tiCidB": "000c74331138c10e08f3fc78c55f3acfff24fb4fb617755438b84fd84d786fdf72ca1212202f4a3b1b2cb24894aede9c6d6ea7f62c7a601ad4e24cbcc17b744950b2acca46",
  "amountA": "85000.0",
  "amountB": "0.10",
  "lpProvider": "'"$OP"'"
}' | jq

# 4) Post-check TIs absentes
curl -s "$BASE/api/devnet/bootstrap-tis?receiverParty=${OP_ENC}&amuletAmount=85000&cbtcAmount=0.1&maxAgeSeconds=7200" | jq
curl -s "$BASE/api/devnet/transfer-instructions/debug?receiverParty=${OP_ENC}" | jq

# 5) Vérifier pool Active + réserves
curl -s "$BASE/api/holding-pools/${NEW_POOL_CID}" | jq
curl -s "$BASE/api/holding-pools" | jq