package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.ErrorMapper;
import com.digitalasset.quickstart.dto.LiquidityConsumeRequest;
import com.digitalasset.quickstart.dto.LiquidityConsumeResponse;
import com.digitalasset.quickstart.dto.LiquidityInspectResponse;
import com.digitalasset.quickstart.service.LiquidityTiProcessorService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devnet/liquidity")
@Profile("devnet")
public class DevNetLiquidityController {

    private static final Logger logger = LoggerFactory.getLogger(DevNetLiquidityController.class);
    private final LiquidityTiProcessorService liquidityService;

    public DevNetLiquidityController(final LiquidityTiProcessorService liquidityService) {
        this.liquidityService = liquidityService;
    }

    /**
     * POST /api/devnet/liquidity/consume
     * Consume inbound liquidity TIs and mint LP.
     */
    @PostMapping("/consume")
    public java.util.concurrent.CompletableFuture<ResponseEntity<ApiResponse<LiquidityConsumeResponse>>> consume(
            @RequestBody LiquidityConsumeRequest request,
            HttpServletRequest httpRequest
    ) {
        String requestId = request != null ? request.requestId : null;
        if (requestId == null || requestId.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond("liquidity-consume", Result.err(validationError("requestId is required", "requestId"))));
        }
        if (httpRequest != null) {
            httpRequest.setAttribute("requestId", requestId);
        }
        logger.info("[DevNetLiquidity] POST /liquidity/consume requestId={}", requestId);
        return liquidityService.consume(request)
                .thenApply(result -> respond(requestId, result));
    }

    /**
     * GET /api/devnet/liquidity/inspect?requestId=...
     */
    @GetMapping("/inspect")
    public java.util.concurrent.CompletableFuture<ResponseEntity<ApiResponse<LiquidityInspectResponse>>> inspect(
            @RequestParam("requestId") String requestId,
            HttpServletRequest httpRequest
    ) {
        if (requestId == null || requestId.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond("liquidity-inspect", Result.err(validationError("requestId is required", "requestId"))));
        }
        if (httpRequest != null) {
            httpRequest.setAttribute("requestId", requestId);
        }
        logger.info("[DevNetLiquidity] GET /liquidity/inspect requestId={}", requestId);
        return liquidityService.inspect(requestId)
                .thenApply(result -> respond(requestId, result));
    }

    private <T> ResponseEntity<ApiResponse<T>> respond(String requestId, Result<T, ApiError> result) {
        if (result.isOk()) {
            return ResponseEntity.ok(ApiResponse.success(requestId, result.getValueUnsafe()));
        }
        ApiError error = result.getErrorUnsafe();
        logger.error("[DevNetLiquidity] requestId={} failed code={} message={} details={}", requestId, error.code, error.message, error.details);
        return ResponseEntity.status(ErrorMapper.toHttpStatus(error.code))
                .body(ApiResponse.failure(requestId, error));
    }

    private ApiError validationError(String message, String field) {
        return new ApiError(
                com.digitalasset.quickstart.dto.ErrorCode.VALIDATION,
                message,
                java.util.Map.of("field", field),
                false,
                null,
                null
        );
    }
}

