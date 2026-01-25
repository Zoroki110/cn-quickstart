package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.ErrorCode;
import com.digitalasset.quickstart.dto.ErrorMapper;
import com.digitalasset.quickstart.dto.SwapConsumeRequest;
import com.digitalasset.quickstart.dto.SwapConsumeResponse;
import com.digitalasset.quickstart.dto.SwapIntentLookupResponse;
import com.digitalasset.quickstart.dto.SwapTransferInstructionResponse;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.service.HoldingPoolService;
import com.digitalasset.quickstart.service.SwapIntentAcsQueryService;
import com.digitalasset.quickstart.service.SwapTiProcessorService;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionDto;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionWithMemo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/devnet/swap")
@Profile("devnet")
public class DevNetSwapController {

    private static final Logger logger = LoggerFactory.getLogger(DevNetSwapController.class);
    private final SwapTiProcessorService swapService;
    private final TransferInstructionAcsQueryService tiQueryService;
    private final SwapIntentAcsQueryService swapIntentQueryService;
    private final HoldingPoolService holdingPoolService;
    private final AuthUtils authUtils;
    private final ObjectMapper mapper = new ObjectMapper();

    public DevNetSwapController(final SwapTiProcessorService swapService,
                                final TransferInstructionAcsQueryService tiQueryService,
                                final SwapIntentAcsQueryService swapIntentQueryService,
                                final HoldingPoolService holdingPoolService,
                                final AuthUtils authUtils) {
        this.swapService = swapService;
        this.tiQueryService = tiQueryService;
        this.swapIntentQueryService = swapIntentQueryService;
        this.holdingPoolService = holdingPoolService;
        this.authUtils = authUtils;
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

    /**
     * GET /api/devnet/swap/transfer-instruction?requestId=...
     * Resolve inbound TI by requestId (memo) for the operator.
     */
    @GetMapping("/transfer-instruction")
    public ResponseEntity<ApiResponse<SwapTransferInstructionResponse>> getTransferInstruction(
            @RequestParam("requestId") String requestId,
            HttpServletRequest httpRequest
    ) {
        String op = authUtils.getAppProviderPartyId();
        if (requestId == null || requestId.isBlank()) {
            return respond(requestId, Result.err(validationError("requestId is required", "requestId")));
        }
        if (httpRequest != null) {
            httpRequest.setAttribute("requestId", requestId);
        }

        Result<List<TransferInstructionWithMemo>, com.digitalasset.quickstart.common.DomainError> res =
                tiQueryService.listForReceiverWithMemo(op);
        if (res.isErr()) {
            return respond(requestId, Result.err(new ApiError(
                    ErrorCode.INTERNAL,
                    "Failed to query pending TIs",
                    Map.of("cause", res.getErrorUnsafe().message()),
                    false,
                    null,
                    null
            )));
        }

        TransferInstructionWithMemo match = res.getValueUnsafe().stream()
                .filter(row -> requestId.equals(extractRequestId(row.memo())))
                .findFirst()
                .orElse(null);

        if (match == null) {
            return respond(requestId, Result.err(new ApiError(
                    ErrorCode.NOT_FOUND,
                    "No inbound TI found for requestId",
                    Map.of("requestId", requestId),
                    false,
                    null,
                    null
            )));
        }

        TransferInstructionDto ti = match.transferInstruction();
        SwapTransferInstructionResponse payload = new SwapTransferInstructionResponse();
        payload.requestId = requestId;
        payload.contractId = ti.contractId();
        payload.instrumentAdmin = ti.admin();
        payload.instrumentId = ti.instrumentId();
        payload.amount = ti.amount();
        payload.executeBefore = ti.executeBefore() != null ? ti.executeBefore().toString() : null;
        payload.memo = match.memo();
        return respond(requestId, Result.ok(payload));
    }

    /**
     * GET /api/devnet/swap/intent?requestId=...
     * Resolve SwapIntent cid by requestId (memo -> TI -> SwapIntent).
     */
    @GetMapping("/intent")
    public ResponseEntity<ApiResponse<SwapIntentLookupResponse>> getIntentByRequestId(
            @RequestParam("requestId") String requestId,
            HttpServletRequest httpRequest
    ) {
        String op = authUtils.getAppProviderPartyId();
        if (requestId == null || requestId.isBlank()) {
            return respond(requestId, Result.err(validationError("requestId is required", "requestId")));
        }
        if (httpRequest != null) {
            httpRequest.setAttribute("requestId", requestId);
        }

        Result<List<TransferInstructionWithMemo>, com.digitalasset.quickstart.common.DomainError> res =
                tiQueryService.listForReceiverWithMemo(op);
        if (res.isErr()) {
            return respond(requestId, Result.err(new ApiError(
                    ErrorCode.INTERNAL,
                    "Failed to query pending TIs",
                    Map.of("cause", res.getErrorUnsafe().message()),
                    false,
                    null,
                    null
            )));
        }

        TransferInstructionWithMemo match = res.getValueUnsafe().stream()
                .filter(row -> requestId.equals(extractRequestId(row.memo())))
                .findFirst()
                .orElse(null);
        if (match == null) {
            return respond(requestId, Result.err(new ApiError(
                    ErrorCode.NOT_FOUND,
                    "No inbound TI found for requestId",
                    Map.of("requestId", requestId),
                    false,
                    null,
                    null
            )));
        }

        String tiCid = match.transferInstruction().contractId();
        var intent = swapIntentQueryService.findByTransferInstructionCid(op, tiCid);
        if (intent.isEmpty()) {
            return respond(requestId, Result.err(new ApiError(
                    ErrorCode.NOT_FOUND,
                    "SWAP_INTENT_NOT_FOUND",
                    Map.of("requestId", requestId, "tiCid", tiCid),
                    false,
                    null,
                    null
            )));
        }

        SwapIntentLookupResponse payload = new SwapIntentLookupResponse();
        payload.requestId = requestId;
        payload.transferInstructionCid = tiCid;
        payload.intentCid = intent.get().contractId();
        return respond(requestId, Result.ok(payload));
    }

    /**
     * GET /api/devnet/swap/inspect?requestId=...
     * Returns inbound TI + SwapIntent visibility + pool status.
     */
    @GetMapping("/inspect")
    public java.util.concurrent.CompletableFuture<ResponseEntity<ApiResponse<Map<String, Object>>>> inspect(
            @RequestParam("requestId") String requestId,
            HttpServletRequest httpRequest
    ) {
        String op = authUtils.getAppProviderPartyId();
        if (requestId == null || requestId.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond(requestId, Result.err(validationError("requestId is required", "requestId"))));
        }
        if (httpRequest != null) {
            httpRequest.setAttribute("requestId", requestId);
        }

        Result<List<TransferInstructionWithMemo>, com.digitalasset.quickstart.common.DomainError> res =
                tiQueryService.listForReceiverWithMemo(op);
        if (res.isErr()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond(requestId, Result.err(new ApiError(
                            ErrorCode.INTERNAL,
                            "Failed to query pending TIs",
                            Map.of("cause", res.getErrorUnsafe().message()),
                            false,
                            null,
                            null
                    )))
            );
        }

        TransferInstructionWithMemo match = res.getValueUnsafe().stream()
                .filter(row -> requestId.equals(extractRequestId(row.memo())))
                .findFirst()
                .orElse(null);
        if (match == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond(requestId, Result.err(new ApiError(
                            ErrorCode.NOT_FOUND,
                            "No inbound TI found for requestId",
                            Map.of("requestId", requestId),
                            false,
                            null,
                            null
                    )))
            );
        }

        String tiCid = match.transferInstruction().contractId();
        var intent = swapIntentQueryService.findByTransferInstructionCid(op, tiCid);
        String intentCid = intent.map(SwapIntentAcsQueryService.SwapIntentDto::contractId).orElse(null);
        String memoRaw = match.memo();

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("inboundTiCid", tiCid);
        payload.put("swapIntentCid", intentCid);
        payload.put("memoRaw", memoRaw);

        String poolCid = null;
        try {
            var node = mapper.readTree(memoRaw == null ? "" : memoRaw);
            var poolNode = node.get("poolCid");
            if (poolNode != null) {
                poolCid = poolNode.asText();
                payload.put("poolCid", poolCid);
            }
        } catch (Exception e) {
            payload.put("memoParseError", e.getMessage());
        }

        if (poolCid == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(respond(requestId, Result.ok(payload)));
        }

        return holdingPoolService.getByContractId(poolCid)
                .thenApply(poolResult -> {
                    if (poolResult.isOk()) {
                        var pool = poolResult.getValueUnsafe();
                        payload.put("poolStatus", pool.status);
                        payload.put("reserveAmountA", pool.reserveAmountA);
                        payload.put("reserveAmountB", pool.reserveAmountB);
                    } else {
                        payload.put("poolError", poolResult.getErrorUnsafe().message());
                    }
                    return respond(requestId, Result.ok(payload));
                });
    }

    private <T> ResponseEntity<ApiResponse<T>> respond(String requestId, Result<T, ApiError> result) {
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

    private String extractRequestId(String memoRaw) {
        if (memoRaw == null || memoRaw.isBlank()) {
            return null;
        }
        try {
            var node = mapper.readTree(memoRaw);
            var rid = node.get("requestId");
            return rid != null ? rid.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}

