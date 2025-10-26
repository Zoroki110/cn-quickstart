#!/usr/bin/env python3
"""
Direct pool creation using Canton Ledger API
This will create a real pool on the Canton ledger
"""
import json
import subprocess
import uuid
from datetime import datetime

# Configuration
LEDGER_HOST = "localhost"
LEDGER_PORT = "5001"

def run_daml_script():
    """Create pool using DAML script with explicit options"""
    
    print("=== CREATING AMM POOL ON CANTON ===")
    print(f"Time: {datetime.now()}")
    
    # Create a specific script that will definitely commit
    script_content = """
module CreateRealPool where

import Daml.Script
import AMM.Pool
import Token.Token
import DA.Time (hours)

createRealPool : Script ()
createRealPool = do
  -- Create unique parties for this run
  let suffix = "REAL_" <> show (hours 1)
  
  operator <- allocateParty $ "OP_REAL"
  poolParty <- allocateParty $ "POOL_REAL"
  ethIssuer <- allocateParty $ "ETH_REAL"
  usdcIssuer <- allocateParty $ "USDC_REAL"
  lpIssuer <- allocateParty $ "LP_REAL"
  feeReceiver <- allocateParty $ "FEE_REAL"
  
  debug $ "Operator: " <> show operator
  debug $ "Pool Party: " <> show poolParty
  
  -- Create tokens
  ethTokenCid <- submit ethIssuer do
    createCmd Token with
      issuer = ethIssuer
      owner = operator
      symbol = "ETH"
      amount = 1000.0
      
  usdcTokenCid <- submit usdcIssuer do
    createCmd Token with
      issuer = usdcIssuer
      owner = operator  
      symbol = "USDC"
      amount = 2000000.0
      
  -- Create the AMM pool
  poolCid <- submitMulti [operator, poolParty] [] do
    createCmd Pool with
      poolOperator = operator
      poolParty = poolParty
      lpIssuer = lpIssuer
      issuerA = ethIssuer
      issuerB = usdcIssuer
      symbolA = "ETH"
      symbolB = "USDC"
      feeBps = 30  -- 0.3%
      poolId = "real-eth-usdc-pool"
      maxTTL = hours 24
      totalLPSupply = 0.0
      reserveA = 0.0
      reserveB = 0.0
      tokenACid = Some ethTokenCid
      tokenBCid = Some usdcTokenCid
      protocolFeeReceiver = feeReceiver
      maxInBps = 10000  -- 100%
      maxOutBps = 5000   -- 50%
      
  debug $ "POOL CREATED: " <> show poolCid
  
  -- Verify by querying
  pools <- query @Pool operator
  debug $ "Pools visible to operator: " <> show (length pools)
  
  pure ()
"""
    
    # Write the script
    with open("/root/cn-quickstart/quickstart/clearportx/daml/CreateRealPool.daml", "w") as f:
        f.write(script_content)
    
    print("\n1. Building DAR with pool creation script...")
    build_cmd = ["daml", "build"]
    subprocess.run(build_cmd, cwd="/root/cn-quickstart/quickstart/clearportx", check=True)
    
    print("\n2. Running pool creation script on Canton ledger...")
    script_cmd = [
        "daml", "script",
        "--ledger-host", LEDGER_HOST,
        "--ledger-port", LEDGER_PORT,
        "--dar", ".daml/dist/clearportx-amm-1.0.4.dar",
        "--script-name", "CreateRealPool:createRealPool",
        "--output-file", "pool-result.json"
    ]
    
    result = subprocess.run(
        script_cmd, 
        cwd="/root/cn-quickstart/quickstart/clearportx",
        capture_output=True,
        text=True
    )
    
    print(f"\nScript output:\n{result.stdout}")
    if result.stderr:
        print(f"Errors:\n{result.stderr}")
    
    if result.returncode == 0:
        print("\n✅ POOL CREATION SCRIPT COMPLETED!")
        
        # Read the result file
        try:
            with open("/root/cn-quickstart/quickstart/clearportx/pool-result.json", "r") as f:
                pool_data = json.load(f)
                print(f"\nPool creation result: {json.dumps(pool_data, indent=2)}")
        except:
            pass
            
        return True
    else:
        print("\n❌ Pool creation failed!")
        return False

def verify_pool():
    """Verify pool exists on ledger"""
    print("\n3. Verifying pool on ledger...")
    
    # List all parties
    cmd = ["grpcurl", "-plaintext", "-d", "{}", f"{LEDGER_HOST}:{LEDGER_PORT}",
           "com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties"]
    
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode == 0:
        data = json.loads(result.stdout)
        parties = data.get("party_details", [])
        
        # Find REAL parties
        real_parties = [p for p in parties if "REAL" in p.get("party", "")]
        print(f"\nFound {len(real_parties)} REAL parties")
        
        for party in real_parties[:5]:
            print(f"  - {party['party']}")
            
            # Query contracts for this party
            query_cmd = [
                "grpcurl", "-plaintext",
                "-d", json.dumps({
                    "filter": {
                        "filters_by_party": {
                            party["party"]: {}
                        }
                    }
                }),
                f"{LEDGER_HOST}:{LEDGER_PORT}",
                "com.daml.ledger.api.v2.StateService/GetActiveContracts"
            ]
            
            query_result = subprocess.run(query_cmd, capture_output=True, text=True)
            if "active_contracts" in query_result.stdout:
                contracts = query_result.stdout.count("contract_id")
                if contracts > 0:
                    print(f"    ✅ Has {contracts} contracts!")

if __name__ == "__main__":
    success = run_daml_script()
    if success:
        verify_pool()
    
    print("\n=== DONE ===")
    print("Check the backend API: curl http://localhost:8080/api/pools")
