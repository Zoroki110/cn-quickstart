#!/usr/bin/env python3
"""
Fix ExecuteSwap result handling to use Receipt
"""

import re
import sys

def fix_execute_results(content):
    """
    Change:
        (outputToken, _) <- ... ExecuteSwap
        token <- queryContractId alice outputToken

    To:
        receiptCid <- ... ExecuteSwap
        Some receipt <- queryContractId alice receiptCid
        token <- queryContractId alice receipt.outputTokenCid
    """

    # Pattern: (varName, _) <- ... ExecuteSwap
    pattern = r'\((\w+),\s*_\)\s*<-\s*(.*?)\s+SR\.ExecuteSwap'

    def replacement(match):
        output_var = match.group(1)
        submit_part = match.group(2)
        return f"receiptCid <- {submit_part} SR.ExecuteSwap\n  Some receipt <- queryContractId {output_var.replace('Received', '').replace('Token', '').lower() if 'Received' in output_var else 'trader'} receiptCid"

    content = re.sub(pattern, replacement, content)

    # Fix queryContractId to use receipt.outputTokenCid
    # Pattern: token <- queryContractId party outputVar
    # But only if outputVar looks like it was from ExecuteSwap
    pattern2 = r'(\w+Token)\s*<-\s*queryContractId\s+(\w+)\s+(ethReceived|usdcReceived|outputToken|cantonReceived)'

    def replacement2(match):
        token_var = match.group(1)
        party = match.group(2)
        return f"{token_var} <- queryContractId {party} receipt.outputTokenCid"

    content = re.sub(pattern2, replacement2, content)

    return content

def main():
    if len(sys.argv) < 2:
        print("Usage: fix_execute_results.py <daml_file>")
        sys.exit(1)

    filepath = sys.argv[1]

    with open(filepath, 'r') as f:
        content = f.read()

    content = fix_execute_results(content)

    with open(filepath, 'w') as f:
        f.write(content)

    print(f"Fixed ExecuteSwap results in {filepath}")

if __name__ == '__main__':
    main()
