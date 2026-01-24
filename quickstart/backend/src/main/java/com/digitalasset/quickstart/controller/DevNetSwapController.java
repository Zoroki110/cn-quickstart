package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.ErrorCode;
import com.digitalasset.quickstart.dto.ErrorMapper;
import com.digitalasset.quickstart.dto.SwapConsumeRequest;
import com.digitalasset.quickstart.dto.SwapConsumeResponse;
import com.digitalasset.quickstart.service.SwapTiProcessorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/devnet/swap")
@Profile("devnet")
public class DevNetSwapController {

    private static final Logger logger = LoggerFactory.getLogger(DevNetSwapController.class);
    private final SwapTiProcessorService swapService;

    public DevNetSwapController(final SwapTiProcessorService swapService) {
        this.swapService = swapService;
    }

    /**
     * POST /api/devnet/swap/consume
     * Consume inbound swap TI, execute swap, and create payout.
     */
    @PostMapping("/consume")
    public java.util.concurrent.CompletableFuture<ResponseEntity<ApiResponse<SwapConsumeResponse>>> consume(
            @RequestBody SwapConsumeRequest request,
            HttpServletRequest httpRequest
    ) {
        String requestId = request != null ? request.requestId : null;
        if (requestId == null || requestId.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond("swap-consume", Result.err(validationError("requestId is required", "requestId"))));
        }
        if (httpRequest != null) {
            httpRequest.setAttribute("requestId", requestId);
        }
        logger.info("[DevNetSwap] POST /swap/consume requestId={}", requestId);
        return swapService.consume(request)
                .thenApply(result -> respond(requestId, result));
    }

    private ResponseEntity<ApiResponse<SwapConsumeResponse>> respond(String requestId, Result<SwapConsumeResponse, ApiError> result) {
        if (result.isOk()) {
            return ResponseEntity.ok(ApiResponse.success(requestId, result.getValueUnsafe()));
        }
        ApiError error = result.getErrorUnsafe();
        logger.error("[DevNetSwap] requestId={} failed code={} message={} details={}", requestId, error.code, error.message, error.details);
        return ResponseEntity.status(ErrorMapper.toHttpStatus(error.code))
                .body(ApiResponse.failure(requestId, error));
    }

    private ApiError validationError(String message, String field) {
        return new ApiError(
                ErrorCode.VALIDATION,
                message,
                Map.of("field", field),
                false,
                null,
                null
        );
    }
}

