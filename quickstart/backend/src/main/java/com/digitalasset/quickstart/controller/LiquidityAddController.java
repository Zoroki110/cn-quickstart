package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.LiquidityConsumeRequest;
import com.digitalasset.quickstart.dto.LiquidityConsumeResponse;
import com.digitalasset.quickstart.dto.LiquidityInspectResponse;
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
@RequestMapping("/api/liquidity/add")
@Profile("devnet")
public class LiquidityAddController {

    private final DevNetLiquidityController devNetLiquidityController;

    public LiquidityAddController(final DevNetLiquidityController devNetLiquidityController) {
        this.devNetLiquidityController = devNetLiquidityController;
    }

    /**
     * POST /api/liquidity/add/consume (agnostic surface)
     */
    @PostMapping("/consume")
    public CompletableFuture<ResponseEntity<ApiResponse<LiquidityConsumeResponse>>> consume(
            @RequestBody LiquidityConsumeRequest request,
            HttpServletRequest httpRequest
    ) {
        return devNetLiquidityController.consume(request, httpRequest)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }

    /**
     * GET /api/liquidity/add/inspect?requestId=...
     */
    @GetMapping("/inspect")
    public CompletableFuture<ResponseEntity<ApiResponse<LiquidityInspectResponse>>> inspect(
            @RequestParam("requestId") String requestId,
            @RequestParam(value = "poolCid", required = false) String poolCid,
            HttpServletRequest httpRequest
    ) {
        return devNetLiquidityController.inspect(requestId, poolCid, httpRequest)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }
}

