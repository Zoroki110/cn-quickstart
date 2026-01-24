package com.digitalasset.quickstart.dto;

/**
 * Response DTO for devnet swap TI consumption.
 */
public class SwapConsumeResponse {
    public String requestId;
    public String inboundTiCid;
    public String intentCid;
    public String poolCid;
    public String direction;
    public String amountIn;
    public String amountOut;
    public String minOut;
    public String executeSwapLedgerUpdateId;
    public String payoutCid;
    public String payoutExecuteBefore;
    public String nextAction;

    public SwapConsumeResponse() {}
}

