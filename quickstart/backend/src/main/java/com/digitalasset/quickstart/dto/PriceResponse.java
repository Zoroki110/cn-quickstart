package com.digitalasset.quickstart.dto;

import java.time.Instant;
import java.util.Map;

public class PriceResponse {
    public Map<String, PriceQuote> quotes;
    public Instant asOf;

    public PriceResponse() {}

    public PriceResponse(Map<String, PriceQuote> quotes, Instant asOf) {
        this.quotes = quotes;
        this.asOf = asOf;
    }
}

