package com.digitalasset.quickstart.dto;

import java.math.BigDecimal;

public class PriceQuote {
    public String symbol;
    public BigDecimal priceUsd;
    public String source;
    public String status;
    public String reason;

    public PriceQuote() {}

    public PriceQuote(String symbol, BigDecimal priceUsd, String source, String status, String reason) {
        this.symbol = symbol;
        this.priceUsd = priceUsd;
        this.source = source;
        this.status = status;
        this.reason = reason;
    }
}

