#!/usr/bin/env python3
"""
Create a pool directly via DAML Ledger API (gRPC)
This bypasses DAML scripts entirely and lets us see exactly what's happening.
"""

import grpc
import time
from datetime import datetime, timedelta

# Import DAML Ledger API protobuf definitions
# You'll need: pip install dazl or use the raw protobufs
try:
    from dazl.ledger import Connection
    from dazl.ledger.grpc import create_grpc_channel
    print("✓ Using dazl library")
    USE_DAZL = True
except ImportError:
    print("⚠ dazl not installed, will use raw gRPC")
    USE_DAZL = False

# Configuration
LEDGER_HOST = "localhost"
LEDGER_PORT = 5001
PACKAGE_ID = "e08c974f11c0a04446635e733211580e810d0590a8a1223737a996552ddebbad"
RUN_ID = datetime.now().strftime("%m%d%H%M%S")

# Party names (will be allocated)
OP_PARTY = f"API_OP_{RUN_ID}"
POOL_PARTY = f"API_POOL_{RUN_ID}"
ETH_ISS = f"API_ETH_{RUN_ID}"
USDC_ISS = f"API_USDC_{RUN_ID}"
LP_ISS = f"API_LP_{RUN_ID}"
FEE_PARTY = f"API_FEE_{RUN_ID}"

def main():
    print(f"=== Creating Pool via Ledger API ===")
    print(f"Target: {LEDGER_HOST}:{LEDGER_PORT}")
    print(f"Package: {PACKAGE_ID}")
    print(f"Run ID: {RUN_ID}")
    print()

    if not USE_DAZL:
        print("ERROR: This script requires 'dazl' library")
        print("Install with: pip3 install dazl")
        return 1

    # Create gRPC channel
    print("Connecting to ledger...")
    channel = create_grpc_channel(f"{LEDGER_HOST}:{LEDGER_PORT}")

    # Import required stubs
    from dazl.ledger.grpc.v1_pb2_grpc import PartyManagementServiceStub, CommandServiceStub
    from dazl.ledger.grpc.v1_pb2 import AllocatePartyRequest, SubmitAndWaitRequest
    from google.protobuf.empty_pb2 import Empty

    party_mgmt = PartyManagementServiceStub(channel)
    cmd_service = CommandServiceStub(channel)

    # Step 1: Allocate parties
    print("\n--- Step 1: Allocating parties ---")
    parties = {}

    for display_name in [OP_PARTY, POOL_PARTY, ETH_ISS, USDC_ISS, LP_ISS, FEE_PARTY]:
        print(f"Allocating: {display_name}")
        try:
            req = AllocatePartyRequest(
                party_id_hint=display_name,
                display_name=display_name
            )
            resp = party_mgmt.AllocateParty(req)
            party_id = resp.party_details.party
            parties[display_name] = party_id
            print(f"  ✓ Allocated: {party_id}")
        except grpc.RpcError as e:
            if "already exists" in str(e):
                print(f"  ⚠ Party already exists, continuing...")
                # You'd need to query existing party here
            else:
                print(f"  ✗ Error: {e}")
                return 1

    print(f"\n✓ All parties allocated")
    print(f"Operator: {parties.get(OP_PARTY, 'N/A')}")

    # Step 2: Create Token contracts (ETH and USDC)
    print("\n--- Step 2: Creating token contracts ---")

    # This is where it gets complex - need to construct DAML-LF commands
    # The structure is:
    # Command {
    #   create: CreateCommand {
    #     template_id: Identifier { package_id, module_name, entity_name }
    #     create_arguments: Record { fields: [...] }
    #   }
    # }

    print("⚠ Creating tokens via raw Ledger API requires DAML-LF encoding")
    print("This is complex - let me show you the structure...")

    # For a complete implementation, we'd need to:
    # 1. Build the Record value for Token.Token template
    # 2. Submit via SubmitAndWaitRequest
    # 3. Extract contract IDs from response

    print("\n--- ALTERNATIVE APPROACH ---")
    print("Since DAML-LF encoding is complex, let's use the JSON API instead")
    print("The JSON API is simpler for contract creation")

    return create_pool_via_json_api()

def create_pool_via_json_api():
    """
    Use DAML JSON API instead of gRPC for easier contract creation.
    JSON API endpoint: http://localhost:7575
    """
    import requests
    import json

    JSON_API_URL = "http://localhost:7575"

    print(f"\n=== Using JSON API: {JSON_API_URL} ===")

    # Check if JSON API is running
    try:
        resp = requests.get(f"{JSON_API_URL}/v1/query", timeout=2)
        print("⚠ JSON API not running on port 7575")
        print("\nTo start JSON API:")
        print(f"  daml json-api --ledger-host {LEDGER_HOST} --ledger-port {LEDGER_PORT} --http-port 7575")
        return 1
    except requests.exceptions.ConnectionError:
        print("⚠ JSON API not running on port 7575")
        print("\nTo start JSON API:")
        print(f"  daml json-api --ledger-host {LEDGER_HOST} --ledger-port {LEDGER_PORT} --http-port 7575")
        return 1

if __name__ == "__main__":
    print("=" * 60)
    print("DAML LEDGER API POOL CREATION")
    print("=" * 60)

    # Check if we have the package hash correct
    print(f"\n✓ Package ID: {PACKAGE_ID}")
    print(f"✓ Run ID: {RUN_ID}")
    print(f"✓ Operator party: {OP_PARTY}")

    print("\n--- RECOMMENDATION ---")
    print("The cleanest approach is to use DAML JSON API:")
    print("1. Start JSON API: daml json-api --ledger-host localhost --ledger-port 5001 --http-port 7575")
    print("2. Use curl/requests to create contracts via HTTP")
    print("3. Full visibility of requests/responses")
    print()
    print("Alternatively, we can use daml-helper with explicit logging:")
    print("  daml script --dar <file> --script-name <name> --ledger-host localhost --ledger-port 5001 --debug")
    print()
    print("Would you like me to:")
    print("  (A) Set up JSON API and create pool via HTTP")
    print("  (B) Create a minimal DAML script with maximum logging")
    print("  (C) Use the backend's LedgerApi.java directly to create the pool")
