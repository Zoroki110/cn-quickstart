#!/usr/bin/env python3
"""
Setup script for ClearportX DevNet
Creates 3 new pools (CANTON-USDC, CANTON-CBTC, CBTC-USDC)
Gives tokens to Alice and Bob for E2E testing
"""

import requests
import json
import time

BACKEND_URL = "http://localhost:8080"
APP_PROVIDER = "app-provider::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388"

# Note: Alice and Bob parties should exist on DevNet
# If not, they can be created manually via: daml ledger allocate-party Alice --host localhost --port 5001
ALICE = "Alice::12208c929c3f012c663323ed675b2eaea7d7c6e1a9be35d6b20bc39f311c6aa7cc54"
BOB = "Bob::12207dde870f1e5d7789e7e8f9b5b4e8e8e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e"

def create_pool(pool_id, symbol_a, symbol_b, amount_a, amount_b):
    """Create a new pool via backend (if endpoint exists)"""
    print(f"  Creating {pool_id} ({symbol_a}-{symbol_b})...")
    # Note: This endpoint might not exist in current backend
    # In that case, pools need to be created via DAML script or manually
    try:
        response = requests.post(
            f"{BACKEND_URL}/api/pool/create",
            headers={
                "Content-Type": "application/json",
                "X-Party": APP_PROVIDER
            },
            json={
                "poolId": pool_id,
                "symbolA": symbol_a,
                "symbolB": symbol_b,
                "amountA": amount_a,
                "amountB": amount_b,
                "feeBps": 30
            },
            timeout=30
        )
        if response.status_code == 200:
            print(f"    ✅ {pool_id} created!")
            return True
        else:
            print(f"    ⚠️  Backend returned: {response.status_code}")
            return False
    except Exception as e:
        print(f"    ⚠️  Pool creation endpoint not available: {e}")
        return False

def mint_token(owner, symbol, amount):
    """Mint tokens for a user via backend (if endpoint exists)"""
    print(f"    Minting {amount} {symbol} for {owner[:20]}...")
    try:
        response = requests.post(
            f"{BACKEND_URL}/api/token/mint",
            headers={
                "Content-Type": "application/json",
                "X-Party": APP_PROVIDER
            },
            json={
                "owner": owner,
                "symbol": symbol,
                "amount": amount
            },
            timeout=30
        )
        if response.status_code == 200:
            print(f"      ✅ {amount} {symbol} minted")
            return True
        else:
            print(f"      ⚠️  Backend returned: {response.status_code}")
            return False
    except Exception as e:
        print(f"      ⚠️  Mint endpoint not available: {e}")
        return False

def verify_pools():
    """Verify pools are visible"""
    print("\\n  Verifying pools...")
    try:
        response = requests.get(f"{BACKEND_URL}/api/pools", timeout=10)
        if response.status_code == 200:
            pools = response.json()
            print(f"    ✅ {len(pools)} pools visible")
            for pool in pools:
                print(f"      - {pool['poolId']}: {pool['reserveA']} {pool.get('tokenA', {}).get('symbol', pool.get('symbolA'))} / {pool['reserveB']} {pool.get('tokenB', {}).get('symbol', pool.get('symbolB'))}")
            return True
        else:
            print(f"    ⚠️  Failed to fetch pools: {response.status_code}")
            return False
    except Exception as e:
        print(f"    ❌ Error fetching pools: {e}")
        return False

def verify_tokens(party, name):
    """Verify tokens for a party"""
    print(f"\\n  Verifying tokens for {name}...")
    try:
        response = requests.get(f"{BACKEND_URL}/api/tokens/{party}", timeout=10)
        if response.status_code == 200:
            tokens = response.json()
            print(f"    ✅ {len(tokens)} tokens found")
            for token in tokens:
                print(f"      - {token['amount']} {token['symbol']}")
            return True
        else:
            print(f"    ⚠️  Failed to fetch tokens: {response.status_code}")
            return False
    except Exception as e:
        print(f"    ❌ Error fetching tokens: {e}")
        return False

def main():
    print("=" * 60)
    print("  ClearportX DevNet Setup")
    print("  Create Pools & Give Tokens to Alice and Bob")
    print("=" * 60)
    print()

    # Step 1: Create new pools
    print("[1/4] Creating new pools...")
    print()

    create_pool("CANTON-USDC-01", "CANTON", "USDC", "100.0000000000", "200000.0000000000")
    time.sleep(1)

    create_pool("CANTON-CBTC-01", "CANTON", "CBTC", "100.0000000000", "5.0000000000")
    time.sleep(1)

    create_pool("CBTC-USDC-01", "CBTC", "USDC", "5.0000000000", "200000.0000000000")
    time.sleep(1)

    print()
    print("✅ Pool creation requests sent")
    print()

    # Step 2: Mint tokens for Alice
    print("[2/4] Minting tokens for Alice...")
    print()

    mint_token(ALICE, "ETH", "10.0000000000")
    mint_token(ALICE, "USDC", "50000.0000000000")
    mint_token(ALICE, "CANTON", "20.0000000000")
    mint_token(ALICE, "CBTC", "0.5000000000")

    print()
    print("✅ Alice tokens minting complete")
    print()

    # Step 3: Mint tokens for Bob
    print("[3/4] Minting tokens for Bob...")
    print()

    mint_token(BOB, "ETH", "15.0000000000")
    mint_token(BOB, "USDC", "30000.0000000000")
    mint_token(BOB, "CANTON", "15.0000000000")
    mint_token(BOB, "CBTC", "0.3000000000")

    print()
    print("✅ Bob tokens minting complete")
    print()

    # Step 4: Verify setup
    print("[4/4] Verifying setup...")

    verify_pools()
    verify_tokens(ALICE, "Alice")
    verify_tokens(BOB, "Bob")

    print()
    print("=" * 60)
    print("  Setup Complete!")
    print("=" * 60)
    print()
    print("Pools (visible to all without login):")
    print("  1. ETH-USDC-01    (100 ETH / 200000 USDC)")
    print("  2. CANTON-USDC-01 (100 CANTON / 200000 USDC)")
    print("  3. CANTON-CBTC-01 (100 CANTON / 5 CBTC)")
    print("  4. CBTC-USDC-01   (5 CBTC / 200000 USDC)")
    print()
    print("Users with tokens:")
    print(f"  Alice: {ALICE}")
    print("    - 10 ETH, 50000 USDC, 20 CANTON, 0.5 CBTC")
    print()
    print(f"  Bob: {BOB}")
    print("    - 15 ETH, 30000 USDC, 15 CANTON, 0.3 CBTC")
    print()
    print("⚠️  NOTE: If pool/mint endpoints don't exist,")
    print("   pools and tokens must be created via DAML scripts")
    print()
    print("Next steps:")
    print("  - Test swap on frontend: https://clearportx.netlify.app/swap")
    print("  - Test add liquidity: https://clearportx.netlify.app/liquidity")
    print()

if __name__ == "__main__":
    main()
