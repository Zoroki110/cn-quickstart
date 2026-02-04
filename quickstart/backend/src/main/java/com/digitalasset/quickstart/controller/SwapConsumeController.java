package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.SwapConsumeRequest;
import com.digitalasset.quickstart.dto.SwapConsumeResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
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
@RequestMapping("/api/swap")
@Profile("devnet")
public class SwapConsumeController {

    private final DevNetSwapController devNetSwapController;

    public SwapConsumeController(final DevNetSwapController devNetSwapController) {
        this.devNetSwapController = devNetSwapController;
    }

    /**
     * POST /api/swap/consume (agnostic surface)
     */
    @PostMapping("/consume")
    public CompletableFuture<ResponseEntity<ApiResponse<SwapConsumeResponse>>> consume(
            @RequestBody SwapConsumeRequest request,
            HttpServletRequest httpRequest
    ) {
        return devNetSwapController.consume(request, httpRequest)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }

    /**
     * GET /api/swap/inspect?requestId=...
     */
    @GetMapping("/inspect")
    public CompletableFuture<ResponseEntity<ApiResponse<Map<String, Object>>>> inspect(
            @RequestParam("requestId") String requestId,
            HttpServletRequest httpRequest
    ) {
        return devNetSwapController.inspect(requestId, httpRequest)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }
}

