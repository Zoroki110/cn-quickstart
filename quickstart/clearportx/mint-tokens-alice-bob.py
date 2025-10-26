#!/usr/bin/env python3
"""
Mint tokens for Alice and Bob on Canton Network DevNet
Uses the same package hash as the frozen DAR to ensure compatibility
"""

import json
import subprocess
import sys

# Configuration
PARTICIPANT = "localhost"
PORT = "5001"
DAR_PATH = "artifacts/devnet/clearportx-amm-v1.0.4-frozen.dar"
PACKAGE_HASH = "5ce4bf9f9cd097fa7d4d63b821bd902869354ddf2726d70ed766ba507c3af1b4"

# Party IDs (these must exist on DevNet)
APP_PROVIDER = "app-provider::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388"

# Alice and Bob - we need to find their real party IDs
def get_party_id(display_name):
    """Get party ID by display name"""
    try:
        result = subprocess.run(
            ["daml", "ledger", "list-parties", "--host", PARTICIPANT, "--port", PORT],
            capture_output=True,
            text=True,
            timeout=30
        )

        for line in result.stdout.split('\n'):
            if display_name in line and "isLocal = True" in line:
                # Extract party ID from line like: PartyDetails {party = 'Alice::1220...', isLocal = True}
                start = line.find("party = '") + 9
                end = line.find("'", start)
                if start > 8 and end > start:
                    return line[start:end]
        return None
    except Exception as e:
        print(f"❌ Error getting party ID for {display_name}: {e}")
        return None

def create_token_json(issuer, owner, symbol, amount):
    """Create JSON payload for Token contract"""
    return {
        "moduleName": f"{PACKAGE_HASH}:Token.Token",
        "templateFields": {
            "issuer": issuer,
            "owner": owner,
            "symbol": symbol,
            "amount": str(amount)
        }
    }

def mint_token_via_ledger_api(issuer, owner, symbol, amount):
    """
    Mint token using daml ledger create command
    This creates a Token contract on the ledger
    """
    print(f"  Minting {amount} {symbol} for {owner[:30]}...")

    # Create JSON payload
    payload = create_token_json(issuer, owner, symbol, amount)
    payload_json = json.dumps(payload)

    try:
        # Use daml ledger create command
        result = subprocess.run(
            [
                "daml", "ledger", "create",
                "--host", PARTICIPANT,
                "--port", PORT,
                f"--template-id={PACKAGE_HASH}:Token.Token:Token",
                f"--contract-payload={payload_json}"
            ],
            capture_output=True,
            text=True,
            timeout=30
        )

        if result.returncode == 0:
            print(f"    ✅ {amount} {symbol} minted successfully")
            return True
        else:
            print(f"    ❌ Failed: {result.stderr[:200]}")
            return False
    except Exception as e:
        print(f"    ❌ Error: {e}")
        return False

def main():
    print("=" * 60)
    print("  Mint Tokens for Alice and Bob")
    print("  Using frozen DAR package hash")
    print("=" * 60)
    print()

    # Step 1: Get Alice and Bob party IDs
    print("[1/3] Finding Alice and Bob party IDs...")
    alice_id = get_party_id("Alice")
    bob_id = get_party_id("Bob")

    if not alice_id:
        print("❌ Alice not found! Creating Alice party...")
        subprocess.run(["daml", "ledger", "allocate-party", "Alice", "--host", PARTICIPANT, "--port", PORT])
        alice_id = get_party_id("Alice")

    if not bob_id:
        print("❌ Bob not found! Creating Bob party...")
        subprocess.run(["daml", "ledger", "allocate-party", "Bob", "--host", PARTICIPANT, "--port", PORT])
        bob_id = get_party_id("Bob")

    print(f"  Alice: {alice_id}")
    print(f"  Bob: {bob_id}")
    print()

    # Step 2: Mint tokens for Alice
    print("[2/3] Minting tokens for Alice...")
    mint_token_via_ledger_api(APP_PROVIDER, alice_id, "ETH", 10.0)
    mint_token_via_ledger_api(APP_PROVIDER, alice_id, "USDC", 50000.0)
    mint_token_via_ledger_api(APP_PROVIDER, alice_id, "CANTON", 20.0)
    mint_token_via_ledger_api(APP_PROVIDER, alice_id, "CBTC", 0.5)
    print()

    # Step 3: Mint tokens for Bob
    print("[3/3] Minting tokens for Bob...")
    mint_token_via_ledger_api(APP_PROVIDER, bob_id, "ETH", 15.0)
    mint_token_via_ledger_api(APP_PROVIDER, bob_id, "USDC", 30000.0)
    mint_token_via_ledger_api(APP_PROVIDER, bob_id, "CANTON", 15.0)
    mint_token_via_ledger_api(APP_PROVIDER, bob_id, "CBTC", 0.3)
    print()

    print("=" * 60)
    print("  Token Minting Complete!")
    print("=" * 60)
    print()
    print("Alice tokens: 10 ETH, 50000 USDC, 20 CANTON, 0.5 CBTC")
    print("Bob tokens:   15 ETH, 30000 USDC, 15 CANTON, 0.3 CBTC")
    print()
    print(f"Verify via API:")
    print(f"  curl 'http://localhost:8080/api/tokens/{alice_id}'")
    print(f"  curl 'http://localhost:8080/api/tokens/{bob_id}'")
    print()

if __name__ == "__main__":
    main()
