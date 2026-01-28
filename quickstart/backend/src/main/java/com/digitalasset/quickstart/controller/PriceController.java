package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.dto.PriceResponse;
import com.digitalasset.quickstart.service.PriceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/prices")
public class PriceController {
    private final PriceService priceService;

    public PriceController(final PriceService priceService) {
        this.priceService = priceService;
    }

    @GetMapping
    public ResponseEntity<PriceResponse> getPrices(
            @RequestParam(name = "symbols", required = false) String symbols,
            HttpServletRequest request
    ) {
        String requestId = extractRequestId(request);
        Set<String> requested = parseSymbols(symbols);
        PriceResponse response = priceService.getQuotes(requested, requestId);
        return ResponseEntity.ok(response);
    }

    private Set<String> parseSymbols(String symbols) {
        if (symbols == null || symbols.isBlank()) {
            return Set.of("CBTC", "CC");
        }
        Set<String> set = new LinkedHashSet<>();
        Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .forEach(s -> set.add(s.toUpperCase()));
        return set;
    }

    private String extractRequestId(HttpServletRequest request) {
        if (request == null) {
            return UUID.randomUUID().toString();
        }
        String header = request.getHeader("X-Request-ID");
        if (header != null && !header.isBlank()) {
            return header;
        }
        Object attr = request.getAttribute("requestId");
        if (attr instanceof String str && !str.isBlank()) {
            return str;
        }
        return UUID.randomUUID().toString();
    }
}

