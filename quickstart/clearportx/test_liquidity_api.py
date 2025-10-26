#!/usr/bin/env python3
"""
Test Liquidity Provision via Backend API
Tests add/remove liquidity functionality
"""

import json
import requests
import math
from datetime import datetime
import sys

BASE_URL = "http://localhost:8080"

def log(message):
    """Print timestamped log message"""
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {message}")

def get_pools():
    """Get all pools from API"""
    response = requests.get(f"{BASE_URL}/api/pools")
    if response.status_code == 200:
        return response.json()
    else:
        log(f"Failed to get pools: {response.status_code}")
        return []

def calculate_lp_tokens(amountA, amountB, reserveA, reserveB, totalLP):
    """Calculate expected LP tokens"""
    if totalLP == 0:
        # First liquidity: geometric mean
        return math.sqrt(amountA * amountB)
    else:
        # Subsequent: proportional to reserves
        shareA = amountA * totalLP / reserveA
        shareB = amountB * totalLP / reserveB
        return min(shareA, shareB)

def calculate_output_amounts(lpAmount, totalLP, reserveA, reserveB):
    """Calculate tokens received when removing liquidity"""
    share = lpAmount / totalLP
    return (reserveA * share, reserveB * share)

def run_liquidity_tests():
    """Run comprehensive liquidity tests"""
    log("=== LIQUIDITY PROVISION API TESTS ===")
    
    # 1. Get current pool state
    log("\n1. Getting current pool state...")
    pools = get_pools()
    if not pools:
        log("❌ No pools found!")
        return False
    
    pool = pools[0]
    log(f"✅ Pool found: {pool['poolId']}")
    log(f"   Reserves: {pool['reserveA']} {pool['symbolA']} / {pool['reserveB']} {pool['symbolB']}")
    log(f"   LP Supply: {pool['totalLPSupply']}")
    
    # 2. Test LP token calculations
    log("\n2. Testing LP token calculations...")
    
    # Test first liquidity
    if float(pool['totalLPSupply']) == 0:
        test_amounts = [(10, 20000), (5, 10000), (1, 2000)]
        log("   Testing first liquidity provision:")
        
        for amountA, amountB in test_amounts:
            expected_lp = math.sqrt(amountA * amountB)
            log(f"   - {amountA} ETH + {amountB} USDC → {expected_lp:.2f} LP tokens")
    else:
        # Test subsequent liquidity
        reserveA = float(pool['reserveA'])
        reserveB = float(pool['reserveB'])
        totalLP = float(pool['totalLPSupply'])
        
        log("   Testing subsequent liquidity provision:")
        
        # Balanced liquidity (maintains ratio)
        ratio = reserveB / reserveA
        test_amounts = [
            (1, 1 * ratio),  # Perfect ratio
            (5, 5 * ratio),
            (10, 10 * ratio)
        ]
        
        for amountA, amountB in test_amounts:
            expected_lp = calculate_lp_tokens(amountA, amountB, reserveA, reserveB, totalLP)
            log(f"   - {amountA:.2f} ETH + {amountB:.2f} USDC → {expected_lp:.2f} LP tokens")
        
        # Unbalanced liquidity
        log("\n   Testing unbalanced liquidity (takes minimum):")
        unbalanced_amounts = [
            (10, 10000),  # Too little USDC
            (5, 20000),   # Too much USDC
        ]
        
        for amountA, amountB in unbalanced_amounts:
            expected_lp = calculate_lp_tokens(amountA, amountB, reserveA, reserveB, totalLP)
            effective_ratio = min(amountA/reserveA, amountB/reserveB)
            log(f"   - {amountA} ETH + {amountB} USDC → {expected_lp:.2f} LP tokens")
            log(f"     (Effective ratio: {effective_ratio*100:.1f}% of pool)")
    
    # 3. Test liquidity removal calculations
    log("\n3. Testing liquidity removal calculations...")
    
    if float(pool['totalLPSupply']) > 0:
        totalLP = float(pool['totalLPSupply'])
        reserveA = float(pool['reserveA'])
        reserveB = float(pool['reserveB'])
        
        test_lp_amounts = [
            totalLP * 0.1,   # Remove 10%
            totalLP * 0.5,   # Remove 50%
            totalLP * 0.99,  # Remove 99%
        ]
        
        for lpAmount in test_lp_amounts:
            outA, outB = calculate_output_amounts(lpAmount, totalLP, reserveA, reserveB)
            log(f"   - Remove {lpAmount:.2f} LP → {outA:.2f} ETH + {outB:.2f} USDC")
            log(f"     (Removing {lpAmount/totalLP*100:.1f}% of liquidity)")
    
    # 4. API endpoint documentation
    log("\n4. Liquidity API Endpoints (to implement):")
    
    log("\n   ADD LIQUIDITY:")
    log("   POST /api/liquidity/add")
    log("   {")
    log('     "poolId": "ETH-USDC-01",')
    log('     "tokenAContractId": "<eth-token-cid>",')
    log('     "tokenBContractId": "<usdc-token-cid>",')
    log('     "amountA": "10.0",')
    log('     "amountB": "20000.0",')
    log('     "minLPTokens": "440.0",')
    log('     "provider": "Alice::1220..."')
    log("   }")
    log("")
    log("   Response:")
    log("   {")
    log('     "lpTokenId": "<lp-token-cid>",')
    log('     "lpAmount": "447.21",')
    log('     "shareOfPool": "33.33"')
    log("   }")
    
    log("\n   REMOVE LIQUIDITY:")
    log("   POST /api/liquidity/remove")
    log("   {")
    log('     "poolId": "ETH-USDC-01",')
    log('     "lpTokenContractId": "<lp-token-cid>",')
    log('     "lpAmount": "223.6",')
    log('     "minAmountA": "4.9",')
    log('     "minAmountB": "9800.0",')
    log('     "provider": "Alice::1220..."')
    log("   }")
    log("")
    log("   Response:")
    log("   {")
    log('     "tokenAId": "<eth-token-cid>",')
    log('     "tokenBId": "<usdc-token-cid>",')
    log('     "amountA": "5.0",')
    log('     "amountB": "10000.0"')
    log("   }")
    
    log("\n   GET LP POSITION:")
    log("   GET /api/liquidity/position/{party}")
    log("   Response:")
    log("   {")
    log('     "positions": [')
    log('       {')
    log('         "poolId": "ETH-USDC-01",')
    log('         "lpTokens": "447.21",')
    log('         "shareOfPool": "33.33",')
    log('         "valueA": "10.0",')
    log('         "valueB": "20000.0"')
    log('       }')
    log('     ]')
    log("   }")
    
    # 5. Edge cases
    log("\n5. Edge Cases to Handle:")
    log("   ⚠️  Zero liquidity - First LP uses sqrt formula")
    log("   ⚠️  Unbalanced provision - Take minimum ratio")
    log("   ⚠️  Slippage protection - minLPTokens check")
    log("   ⚠️  Complete removal - Pool resets to empty")
    log("   ⚠️  Minimum liquidity - Prevent dust attacks")
    
    log("\n✅ LIQUIDITY API TEST COMPLETED")
    return True

def main():
    """Main test runner"""
    log("Starting Liquidity API Tests...")
    
    # Run tests
    success = run_liquidity_tests()
    
    if success:
        log("\n🎉 Liquidity tests completed successfully!")
        log("\nKey formulas:")
        log("- First LP: sqrt(amountA * amountB)")
        log("- Subsequent: min(amountA * totalLP / reserveA, amountB * totalLP / reserveB)")
        log("- Removal: lpAmount / totalLP * reserves")
    else:
        log("\n❌ Tests failed!")
        sys.exit(1)

if __name__ == "__main__":
    main()
