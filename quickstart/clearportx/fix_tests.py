#!/usr/bin/env python3
"""
Script to fix DAML test files after Pool template changes.
Adds missing fields: tokenACid, tokenBCid, maxInBps, maxOutBps
"""

import re
import sys

def fix_pool_creation(content):
    """Add missing fields to Pool creations"""

    # Pattern to match Pool creation (multi-line)
    # We need to add fields before 'protocolFeeReceiver'
    pattern = r'(createCmd P\.Pool with\s+(?:[^\n]+\n)+?)\s*(protocolFeeReceiver\s*=\s*\w+)'

    def replacement(match):
        before = match.group(1)
        protocol_fee = match.group(2)

        # Skip if already has tokenACid
        if 'tokenACid' in before:
            return match.group(0)

        # Add new fields before protocolFeeReceiver
        return f"{before}    tokenACid = None\n    tokenBCid = None\n    {protocol_fee}\n    maxInBps = 10000\n    maxOutBps = 5000"

    return re.sub(pattern, replacement, content, flags=re.MULTILINE)

def fix_execute_swap(content):
    """Remove poolTokenACid/poolTokenBCid parameters from ExecuteSwap calls"""

    # Pattern 1: exerciseCmd swapReady SR.ExecuteSwap with\n      poolTokenACid = ...
    pattern1 = r'exerciseCmd\s+(\w+)\s+SR\.ExecuteSwap\s+with\s*\n\s*poolTokenACid\s*=\s*\w+\s*\n\s*poolTokenBCid\s*=\s*\w+'
    content = re.sub(pattern1, r'exerciseCmd \1 SR.ExecuteSwap', content)

    # Pattern 2: submitMustFail ... exerciseCmd swapReady SR.ExecuteSwap with ...
    pattern2 = r'(submitMustFail\s+\w+\s+\$\s+exerciseCmd\s+\w+\s+SR\.ExecuteSwap)\s+with\s*\n\s*poolTokenACid\s*=\s*\w+\s*\n\s*poolTokenBCid\s*=\s*\w+'
    content = re.sub(pattern2, r'\1', content)

    return content

def fix_execute_swap_result(content):
    """Fix ExecuteSwap result handling to use Receipt"""

    # Pattern: (outputToken, newPool) <- ... ExecuteSwap
    # Replace with: receiptCid <- ... ExecuteSwap
    # Then add lines to extract from receipt

    # This is complex, so we'll just remove the poolToken query sections
    # Users will need to adjust their result handling manually

    return content

def main():
    if len(sys.argv) < 2:
        print("Usage: fix_tests.py <daml_file>")
        sys.exit(1)

    filepath = sys.argv[1]

    with open(filepath, 'r') as f:
        content = f.read()

    # Apply fixes
    content = fix_pool_creation(content)
    content = fix_execute_swap(content)

    with open(filepath, 'w') as f:
        f.write(content)

    print(f"Fixed {filepath}")

if __name__ == '__main__':
    main()
