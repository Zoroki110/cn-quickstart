package com.digitalasset.quickstart.dto;

/**
 * Inspect response for devnet liquidity requests.
 */
public class LiquidityInspectResponse {
    public String requestId;
    public String poolCid;
    public String providerParty;
    public String tiCidA;
    public String tiCidB;
    public String memoRaw;
    public String deadline;
    public boolean deadlineExpired;
    public String poolStatus;
    public boolean alreadyProcessed;

    public LiquidityInspectResponse() {}
}

