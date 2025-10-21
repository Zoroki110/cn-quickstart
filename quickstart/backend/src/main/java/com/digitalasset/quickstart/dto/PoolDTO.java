// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.dto;

/**
 * Pool DTO for frontend consumption
 * Represents a liquidity pool with token symbols, reserves, LP supply, and fee
 */
public class PoolDTO {
    public final String symbolA;
    public final String symbolB;
    public final String reserveA;
    public final String reserveB;
    public final String totalLPSupply;
    public final String feeRate;

    public PoolDTO(String symbolA, String symbolB, String reserveA, String reserveB,
                   String totalLPSupply, String feeRate) {
        this.symbolA = symbolA;
        this.symbolB = symbolB;
        this.reserveA = reserveA;
        this.reserveB = reserveB;
        this.totalLPSupply = totalLPSupply;
        this.feeRate = feeRate;
    }
}
