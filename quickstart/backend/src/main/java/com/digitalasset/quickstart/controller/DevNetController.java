package com.digitalasset.quickstart.controller;

import clearportx_amm_drain_credit.token.token.Token;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.security.devnet.DevNetAuthService;
import com.digitalasset.quickstart.service.CbtcTransferOfferService;
import com.digitalasset.quickstart.service.CbtcTransferOfferService.CbtcTransferOfferDto;
import com.digitalasset.transcode.java.ContractId;
import com.digitalasset.transcode.java.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * DevNet Testing Controller
 *
 * Provides convenience endpoints for DevNet testing and development.
 * These endpoints should NOT be available in production.
 *
 * Endpoints:
 * - POST /api/devnet/mint - Mint tokens for testing
 */
@RestController
@RequestMapping("/api/devnet")
@Profile("devnet")
@CrossOrigin(origins = "*")
public class DevNetController {

    private static final Logger logger = LoggerFactory.getLogger(DevNetController.class);

    @Autowired
    private LedgerApi ledgerApi;

    @Autowired
    private DevNetAuthService authService;

    @Autowired
    private CbtcTransferOfferService cbtcTransferOfferService;

    /**
     * POST /api/devnet/mint
     *
     * Mint test tokens for a user. The issuer is always app-provider.
     *
     * Request body:
     * {
     *   "owner": "alice",           // Username (will be resolved to full party ID)
     *   "symbol": "ETH",            // Token symbol
     *   "amount": "10.0"            // Amount to mint
     * }
     *
     * Headers:
     * - X-User: app-provider (required - only app-provider can mint)
     *
     * Response:
     * {
     *   "success": true,
     *   "contractId": "00abc123...",
     *   "owner": "alice",
     *   "ownerPartyId": "Alice-9cefe94d::1220...",
     *   "symbol": "ETH",
     *   "amount": "10.0",
     *   "issuer": "app-provider-4f1df03a::1220..."
     * }
     */
    @PostMapping("/mint")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> mintToken(
            @RequestBody MintTokenRequest request,
            @RequestHeader(value = "X-User", required = false) String username
    ) {
        Map<String, Object> response = new HashMap<>();

        try {
            // Validate request
            if (request.owner == null || request.owner.isBlank()) {
                response.put("success", false);
                response.put("error", "Owner username is required");
                return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(response));
            }

            if (request.symbol == null || request.symbol.isBlank()) {
                response.put("success", false);
                response.put("error", "Token symbol is required");
                return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(response));
            }

            if (request.amount == null || request.amount.compareTo(BigDecimal.ZERO) <= 0) {
                response.put("success", false);
                response.put("error", "Amount must be positive");
                return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(response));
            }

            // Resolve issuer (app-provider from X-User header)
            String issuerUsername = username != null ? username : "app-provider";
            var issuerPartyOpt = authService.resolvePartyId(issuerUsername);
            if (issuerPartyOpt.isEmpty()) {
                response.put("success", false);
                response.put("error", "Issuer not found: " + issuerUsername);
                response.put("hint", "Add 'X-User: app-provider' header");
                return CompletableFuture.completedFuture(ResponseEntity.status(401).body(response));
            }
            String issuerPartyId = issuerPartyOpt.get();

            // Resolve owner party ID
            var ownerPartyOpt = authService.resolvePartyId(request.owner);
            if (ownerPartyOpt.isEmpty()) {
                response.put("success", false);
                response.put("error", "Owner username not found: " + request.owner);
                response.put("availableUsers", authService.getAllMappings().keySet());
                return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(response));
            }
            String ownerPartyId = ownerPartyOpt.get();

            logger.info("Minting {} {} for {} (issuer: {})", request.amount, request.symbol, request.owner, issuerUsername);

            // Create token on ledger
            Token token = new Token(
                new Party(issuerPartyId),
                new Party(ownerPartyId),
                request.symbol,
                request.amount
            );

            String commandId = "mint-" + UUID.randomUUID().toString();

            // Create token and get contract ID
            return ledgerApi.createAndGetCid(
                token,
                java.util.List.of(issuerPartyId),  // actAs
                java.util.List.of(),                 // readAs
                commandId,
                token.templateId()
            ).thenApply(tokenCid -> {
                    logger.info("✅ Minted {} {} for {}: {}", request.amount, request.symbol, request.owner, tokenCid.getContractId);
                    response.put("success", true);
                    response.put("contractId", tokenCid.getContractId);
                    response.put("owner", request.owner);
                    response.put("ownerPartyId", ownerPartyId);
                    response.put("symbol", request.symbol);
                    response.put("amount", request.amount.toPlainString());
                    response.put("issuer", issuerUsername);
                    response.put("issuerPartyId", issuerPartyId);
                    return ResponseEntity.ok(response);
                })
                .exceptionally(ex -> {
                    logger.error("Failed to mint token: {}", ex.getMessage(), ex);
                    response.put("success", false);
                    response.put("error", "Failed to mint token: " + ex.getMessage());
                    return ResponseEntity.status(500).body(response);
                });

        } catch (Exception e) {
            logger.error("Error minting token: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return CompletableFuture.completedFuture(ResponseEntity.status(500).body(response));
        }
    }

    /**
     * Request body for mint endpoint
     */
    public static class MintTokenRequest {
        public String owner;      // Username (alice, bob, etc.)
        public String symbol;     // Token symbol (ETH, USDC, etc.)
        public BigDecimal amount; // Amount to mint

        @Override
        public String toString() {
            return String.format("MintTokenRequest{owner='%s', symbol='%s', amount=%s}", owner, symbol, amount);
        }
    }

    /**
     * GET /api/devnet/cbtc/offers
     *
     * List incoming CBTC TransferOffers for a receiver party.
     * Used by the frontend to display offers that can be accepted via Loop SDK.
     *
     * Query params:
     * - receiverParty: Full party ID of the receiver (e.g., ClearportX-DEX-1::1220...)
     *
     * Response:
     * [
     *   {
     *     "contractId": "00abc123...",
     *     "sender": "cbtc-network::1220...",
     *     "receiver": "ClearportX-DEX-1::1220...",
     *     "amount": "0.1",
     *     "reason": "CBTC transfer",
     *     "executeBefore": null,
     *     "instrumentId": "CBTC",
     *     "instrumentAdmin": "cbtc-network::1220...",
     *     "rawTemplateId": "Utility.Registry.App.V0.Model.Transfer:TransferOffer"
     *   }
     * ]
     */
    @GetMapping("/cbtc/offers")
    public CompletableFuture<ResponseEntity<List<CbtcTransferOfferDto>>> getCbtcOffers(
            @RequestParam(value = "receiverParty") String receiverParty
    ) {
        logger.info("[DevNetController] GET /cbtc/offers receiverParty={}", truncateParty(receiverParty));

        if (receiverParty == null || receiverParty.isBlank()) {
            logger.warn("[DevNetController] receiverParty is required");
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
        }

        return cbtcTransferOfferService.getIncomingOffers(receiverParty)
                .thenApply(offers -> {
                    logger.info("[DevNetController] Returning {} CBTC offers for receiver", offers.size());
                    return ResponseEntity.ok(offers);
                })
                .exceptionally(ex -> {
                    logger.error("[DevNetController] Error fetching CBTC offers: {}", ex.getMessage(), ex);
                    return ResponseEntity.status(500).build();
                });
    }

    private String truncateParty(String party) {
        if (party == null || party.length() <= 30) return party;
        int sep = party.indexOf("::");
        if (sep > 0 && sep < 20) {
            return party.substring(0, sep + 10) + "...";
        }
        return party.substring(0, 20) + "...";
    }
}
