#!/usr/bin/env python3
"""
Verify pool creation by querying the ledger directly via gRPC
"""
import json
import subprocess
import sys

# The parties we created
PARTIES = {
    "operator": "OP_2025-10-25-d4d95138::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388",
    "pool": "POOL_2025-10-25-9b3970be::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388",
    "fresh_op": "FRESH_OP::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388"
}

def query_active_contracts(party_id, template_filter=None):
    """Query active contracts for a party using grpcurl"""
    
    # Build the filter
    filter_data = {
        "filters_by_party": {
            party_id: {}
        }
    }
    
    if template_filter:
        filter_data["filters_by_party"][party_id]["template_filters"] = [{
            "template_id": {
                "module_name": template_filter["module"],
                "entity_name": template_filter["entity"]
            }
        }]
    
    # Use grpcurl to query
    cmd = [
        "grpcurl",
        "-plaintext",
        "-d", json.dumps({"filter": filter_data}),
        "localhost:5001",
        "com.daml.ledger.api.v2.StateService/GetActiveContracts"
    ]
    
    print(f"Querying contracts for {party_id[:30]}...")
    
    try:
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"Error: {result.stderr}")
            return []
        
        contracts = []
        # Parse the streaming response
        for line in result.stdout.strip().split('\n'):
            if line.strip():
                try:
                    data = json.loads(line)
                    if "active_contracts" in data:
                        contracts.extend(data["active_contracts"])
                except json.JSONDecodeError:
                    continue
        
        return contracts
        
    except Exception as e:
        print(f"Error querying contracts: {e}")
        return []

def main():
    print("=== VERIFYING POOL CREATION ===\n")
    
    # Query for Pool contracts
    pool_filter = {"module": "AMM.Pool", "entity": "Pool"}
    
    all_pools = []
    
    for party_name, party_id in PARTIES.items():
        print(f"\nChecking {party_name}...")
        contracts = query_active_contracts(party_id)
        
        # Filter for pool contracts
        pool_contracts = [c for c in contracts 
                         if c.get("template_id", {}).get("entity_name") == "Pool"]
        
        print(f"  Total contracts: {len(contracts)}")
        print(f"  Pool contracts: {len(pool_contracts)}")
        
        for pool in pool_contracts:
            all_pools.append(pool)
            print(f"\n  ✅ POOL FOUND!")
            print(f"  Contract ID: {pool.get('contract_id', 'N/A')}")
            
            # Try to parse the payload
            if "create_arguments" in pool:
                args = pool["create_arguments"]
                if "fields" in args:
                    for field in args["fields"]:
                        if field.get("label") in ["poolId", "symbolA", "symbolB"]:
                            print(f"  {field['label']}: {field.get('value', 'N/A')}")
    
    print(f"\n{'='*50}")
    if all_pools:
        print(f"✅ SUCCESS: Found {len(all_pools)} pool(s) on the ledger!")
    else:
        print("❌ No pools found. The pool may not be visible to these parties.")
        print("\nTrying to list all parties to find the pool...")
        
        # List all known parties
        cmd = ["grpcurl", "-plaintext", "-d", "{}", "localhost:5001", 
               "com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties"]
        
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode == 0:
            try:
                data = json.loads(result.stdout)
                parties = data.get("party_details", [])
                print(f"\nFound {len(parties)} parties on ledger")
                
                # Show parties created today
                today_parties = [p for p in parties if "2025-10-25" in p.get("party", "")]
                print(f"\nParties created today:")
                for p in today_parties[:10]:
                    print(f"  {p['party']}")
                    
            except json.JSONDecodeError:
                pass

if __name__ == "__main__":
    main()
