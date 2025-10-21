// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.dto;

/**
 * AddLiquidityResponse - Response from adding liquidity
 */
public class AddLiquidityResponse {
    public final String lpTokenCid;
    public final String newPoolCid;
    public final String reserveA;
    public final String reserveB;

    public AddLiquidityResponse(String lpTokenCid, String newPoolCid, String reserveA, String reserveB) {
        this.lpTokenCid = lpTokenCid;
        this.newPoolCid = newPoolCid;
        this.reserveA = reserveA;
        this.reserveB = reserveB;
    }
}
