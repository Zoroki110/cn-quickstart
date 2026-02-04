package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.LiquidityRemoveConsumeRequest;
import com.digitalasset.quickstart.dto.LiquidityRemoveConsumeResponse;
import com.digitalasset.quickstart.dto.LiquidityRemoveInspectResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.CompletableFuture;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/liquidity/remove")
@Profile("devnet")
public class LiquidityRemoveController {

    private final DevNetLiquidityRemoveController devNetLiquidityRemoveController;

    public LiquidityRemoveController(final DevNetLiquidityRemoveController devNetLiquidityRemoveController) {
        this.devNetLiquidityRemoveController = devNetLiquidityRemoveController;
    }

    /**
     * POST /api/liquidity/remove/consume (agnostic surface)
     */
    @PostMapping("/consume")
    public CompletableFuture<ResponseEntity<ApiResponse<LiquidityRemoveConsumeResponse>>> consume(
            @RequestBody LiquidityRemoveConsumeRequest request,
            HttpServletRequest httpRequest
    ) {
        return devNetLiquidityRemoveController.consume(request, httpRequest)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }

    /**
     * GET /api/liquidity/remove/inspect
     */
    @GetMapping("/inspect")
    public CompletableFuture<ResponseEntity<ApiResponse<LiquidityRemoveInspectResponse>>> inspect(
            @RequestParam("requestId") String requestId,
            @RequestParam(value = "poolCid", required = false) String poolCid,
            @RequestParam("lpCid") String lpCid,
            @RequestParam("receiverParty") String receiverParty,
            @RequestParam(value = "lpBurnAmount", required = false) String lpBurnAmount,
            HttpServletRequest httpRequest
    ) {
        return devNetLiquidityRemoveController.inspect(requestId, poolCid, lpCid, receiverParty, lpBurnAmount, httpRequest)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }
}

