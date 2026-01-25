package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.constants.SwapConstants;
import com.digitalasset.quickstart.dto.*;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionDto;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionWithMemo;
import com.digitalasset.quickstart.service.TransferInstructionChoiceContextService.ChoiceContextResult;
import com.digitalasset.quickstart.validation.SwapValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@Profile("devnet")
public class SwapTiProcessorService {

    private static final Logger LOG = LoggerFactory.getLogger(SwapTiProcessorService.class);

    private final TransferInstructionAcsQueryService tiQueryService;
    private final HoldingPoolService holdingPoolService;
    private final HoldingSelectorService holdingSelectorService;
    private final SwapIntentAcsQueryService swapIntentQueryService;
    private final PayoutService payoutService;
    private final LedgerApi ledgerApi;
    private final AuthUtils authUtils;
    private final IdempotencyService idempotencyService;
    private final TransferInstructionChoiceContextService choiceContextService;
    private final SwapValidator swapValidator;

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${holdingpool.package-id:}")
    private String holdingPoolPackageId;

    public SwapTiProcessorService(
            TransferInstructionAcsQueryService tiQueryService,
            HoldingPoolService holdingPoolService,
            HoldingSelectorService holdingSelectorService,
            SwapIntentAcsQueryService swapIntentQueryService,
            PayoutService payoutService,
            LedgerApi ledgerApi,
            AuthUtils authUtils,
            IdempotencyService idempotencyService,
            TransferInstructionChoiceContextService choiceContextService,
            SwapValidator swapValidator
    ) {
        this.tiQueryService = tiQueryService;
        this.holdingPoolService = holdingPoolService;
        this.holdingSelectorService = holdingSelectorService;
        this.swapIntentQueryService = swapIntentQueryService;
        this.payoutService = payoutService;
        this.ledgerApi = ledgerApi;
        this.authUtils = authUtils;
        this.idempotencyService = idempotencyService;
        this.choiceContextService = choiceContextService;
        this.swapValidator = swapValidator;
    }

    @WithSpan
    public CompletableFuture<Result<SwapConsumeResponse, ApiError>> consume(final SwapConsumeRequest request) {
        if (request == null || request.requestId == null || request.requestId.isBlank()) {
            return CompletableFuture.completedFuture(Result.err(validationError("requestId is required", "requestId")));
        }

        Object cached = idempotencyService.checkIdempotency(request.requestId);
        if (cached instanceof SwapConsumeResponse cachedResponse) {
            return CompletableFuture.completedFuture(Result.ok(cachedResponse));
        }

        if (holdingPoolPackageId == null || holdingPoolPackageId.isBlank()) {
            return CompletableFuture.completedFuture(Result.err(validationError("holdingpool.package-id is not configured", "holdingpool.package-id")));
        }

        String operator = authUtils.getAppProviderPartyId();
        long maxAgeSeconds = request.maxAgeSeconds != null && request.maxAgeSeconds > 0
                ? request.maxAgeSeconds
                : 7200L;

        Result<List<TransferInstructionWithMemo>, DomainError> pending = tiQueryService.listForReceiverWithMemo(operator);
        if (pending.isErr()) {
            return CompletableFuture.completedFuture(Result.err(domainError("Failed to query pending TIs", pending.getErrorUnsafe())));
        }

        Result<Selection, ApiError> selected = selectCandidate(pending.getValueUnsafe(), request.requestId, maxAgeSeconds, operator);
        if (selected.isErr()) {
            return CompletableFuture.completedFuture(Result.err(selected.getErrorUnsafe()));
        }

        Selection chosen = selected.getValueUnsafe();
        Candidate candidate = chosen.candidate;
        SwapMemo memo = candidate.memo;
        String memoRaw = candidate.memoRaw;
        TransferInstructionDto ti = candidate.transfer;

        Result<SwapMemo, ApiError> memoValidation = validateMemo(memo, request.requestId, chosen.matchedRequestId);
        if (memoValidation.isErr()) {
            return CompletableFuture.completedFuture(Result.err(memoValidation.getErrorUnsafe()));
        }

        Instant deadline;
        try {
            deadline = Instant.parse(memo.deadline);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(Result.err(validationError("memo.deadline is invalid", "deadline")));
        }

        Instant now = Instant.now();
        if (now.isAfter(deadline)) {
            return CompletableFuture.completedFuture(Result.err(preconditionError(
                    "Swap deadline has expired",
                    Map.of("deadline", memo.deadline, "now", now.toString())
            )));
        }

        return holdingPoolService.getByContractId(memo.poolCid)
                .thenCompose(poolResult -> {
                    if (poolResult.isErr()) {
                        return completedError(domainError("Pool not found or not visible", poolResult.getErrorUnsafe()));
                    }
                    HoldingPoolResponse pool = poolResult.getValueUnsafe();
                    if (pool.status == null || !"active".equalsIgnoreCase(pool.status)) {
                        return completedError(preconditionError("Pool is not active", Map.of("status", pool.status)));
                    }

                    SwapDirection direction = SwapDirection.fromMemo(memo.direction);
                    if (direction == null) {
                        return completedError(validationError("memo.direction is invalid", "direction"));
                    }

                    HoldingPoolCreateRequest.InstrumentRef inputInstrument = direction == SwapDirection.A2B ? pool.instrumentA : pool.instrumentB;
                    HoldingPoolCreateRequest.InstrumentRef outputInstrument = direction == SwapDirection.A2B ? pool.instrumentB : pool.instrumentA;

                    if (inputInstrument == null || outputInstrument == null) {
                        return completedError(preconditionError("Pool instruments are missing", Map.of("poolCid", memo.poolCid)));
                    }

                    if (!matchesInstrument(ti, inputInstrument)) {
                        return completedError(preconditionError(
                                "Inbound TI instrument does not match swap direction",
                                Map.of("tiInstrumentAdmin", ti.admin(), "tiInstrumentId", ti.instrumentId(),
                                        "expectedAdmin", inputInstrument.admin, "expectedId", inputInstrument.id,
                                        "direction", memo.direction)
                        ));
                    }

                    BigDecimal amountIn;
                    BigDecimal minOut;
                    try {
                        amountIn = normalizeAmount(ti.amount());
                        minOut = normalizeAmount(memo.minOut);
                    } catch (Exception e) {
                        return completedError(validationError("amount format is invalid", "amount"));
                    }

                    try {
                        swapValidator.validateInputAmount(amountIn);
                        swapValidator.validateMinOutput(minOut);
                    } catch (Exception e) {
                        return completedError(validationError(e.getMessage(), "amountIn"));
                    }

                    BigDecimal amountOut = computeOutputAmount(pool, direction, amountIn);
                    if (amountOut.compareTo(minOut) < 0) {
                        return completedError(preconditionError(
                                "Output amount below minimum",
                                Map.of("amountOut", amountOut.toPlainString(), "minOut", minOut.toPlainString())
                        ));
                    }

                    BigDecimal availableOut = getAvailableOut(pool, direction);
                    if (amountOut.compareTo(availableOut) > 0) {
                        return completedError(preconditionError(
                                "Insufficient available liquidity for output",
                                Map.of("amountOut", amountOut.toPlainString(), "availableOut", availableOut.toPlainString())
                        ));
                    }

                    HoldingSelectRequest selectRequest = new HoldingSelectRequest(
                            operator,
                            outputInstrument.admin,
                            outputInstrument.id,
                            amountOut,
                            0,
                            0
                    );

                    return holdingSelectorService.selectHoldingOnce(selectRequest)
                            .thenCompose(holdingSelection -> {
                                if (!holdingSelection.found()) {
                                    return completedError(preconditionError(
                                            "No output holding found for payout",
                                            Map.of("admin", outputInstrument.admin, "id", outputInstrument.id, "minAmount", amountOut.toPlainString())
                                    ));
                                }

                                String outputHoldingCid = holdingSelection.holdingCid();
                                Result<ChoiceContextResult, ApiError> choiceCtx =
                                        choiceContextService.resolveDisclosedContracts(ti.contractId(), inputInstrument.admin);
                                if (choiceCtx.isErr()) {
                                    return completedError(choiceCtx.getErrorUnsafe());
                                }
                                ChoiceContextResult ctx = choiceCtx.getValueUnsafe();
                                Optional<SwapIntentAcsQueryService.SwapIntentDto> intent =
                                        swapIntentQueryService.findByTransferInstructionCid(operator, ti.contractId());
                                if (intent.isEmpty()) {
                                    return completedError(new ApiError(
                                            ErrorCode.NOT_FOUND,
                                            "SWAP_INTENT_NOT_FOUND",
                                            Map.of("requestId", request.requestId, "tiCid", ti.contractId()),
                                            false,
                                            null,
                                            null
                                    ));
                                }

                                String intentCid = intent.get().contractId();
                                LOG.info("[SwapConsume] requestId={} operator={} inboundTiCid={} swapIntentCid={} poolCid={} amountIn={} amountOut={} actAs=[{}] readAs=[{}]",
                                        request.requestId,
                                        operator,
                                        ti.contractId(),
                                        intentCid,
                                        pool.contractId,
                                        amountIn.toPlainString(),
                                        amountOut.toPlainString(),
                                        operator,
                                        "");

                                return executeSwap(
                                                pool.contractId,
                                                intentCid,
                                                outputHoldingCid,
                                                ctx.disclosedContracts(),
                                                ctx.synchronizerId()
                                        )
                                        .thenCompose(executeResult -> {
                                            if (executeResult.isErr()) {
                                                return completedError(annotateNoSynchronizer(executeResult.getErrorUnsafe(),
                                                        "ExecuteSwap",
                                                        List.of(operator),
                                                        List.of(),
                                                        ctx.synchronizerId()));
                                            }
                                            String updateId = extractUpdateId(executeResult.getValueUnsafe());
                                            if (updateId == null || updateId.isBlank()) {
                                                return completedError(ApiError.of(ErrorCode.INTERNAL, "ExecuteSwap returned no updateId"));
                                            }

                                            Result<PayoutResponse, ApiError> payoutResult = createPayout(
                                                    outputInstrument,
                                                    memo.receiverParty,
                                                    amountOut,
                                                    memoRaw,
                                                    deadline,
                                                    request.requestId
                                            );
                                            if (payoutResult.isErr()) {
                                                return completedError(annotateNoSynchronizer(payoutResult.getErrorUnsafe(),
                                                        "Payout",
                                                        List.of(operator),
                                                        List.of(operator),
                                                        null));
                                            }

                                            PayoutResponse payout = payoutResult.getValueUnsafe();
                                            SwapConsumeResponse response = new SwapConsumeResponse();
                                            response.requestId = request.requestId;
                                            response.inboundTiCid = ti.contractId();
                                            response.intentCid = intentCid;
                                            response.swapIntentCid = intentCid;
                                            response.poolCid = memo.poolCid;
                                            response.direction = memo.direction;
                                            response.amountIn = amountIn.toPlainString();
                                            response.amountOut = amountOut.toPlainString();
                                            response.minOut = minOut.toPlainString();
                                            response.executeSwapLedgerUpdateId = updateId;
                                            response.executeSwapStatus = "SUCCEEDED";
                                            response.payoutCid = payout.cid();
                                            response.payoutExecuteBefore = payout.executeBefore();
                                            response.payoutFactoryId = payout.factoryId();
                                            response.payoutDisclosedContractsCount = payout.disclosedContractsCount();
                                            response.payoutStatus = "CREATED";
                                            response.nextAction = "ACCEPT_PAYOUT_IN_LOOP";

                                            idempotencyService.registerSuccess(request.requestId, request.requestId, updateId, response);
                                            return CompletableFuture.completedFuture(Result.ok(response));
                                        });
                            });
                });
    }

    private Result<Selection, ApiError> selectCandidate(
            List<TransferInstructionWithMemo> items,
            String requestId,
            long maxAgeSeconds,
            String receiverParty
    ) {
        Instant cutoff = Instant.now().minusSeconds(maxAgeSeconds);
        List<Candidate> candidates = new ArrayList<>();
        List<String> invalidMemoCids = new ArrayList<>();

        for (TransferInstructionWithMemo row : items) {
            TransferInstructionDto ti = row.transferInstruction();
            if (ti == null || ti.contractId() == null) continue;
            if (receiverParty != null && !receiverParty.equals(ti.receiver())) continue;

            String memoRaw = row.memo();
            if (memoRaw == null || memoRaw.isBlank()) {
                invalidMemoCids.add(ti.contractId());
                continue;
            }

            SwapMemo memo = parseMemo(memoRaw);
            if (memo == null) {
                if (requestId != null && memoRaw.contains(requestId)) {
                    return Result.err(validationError("memo JSON is invalid", "memo"));
                }
                invalidMemoCids.add(ti.contractId());
                continue;
            }
            if (memo.poolCid == null || memo.direction == null) continue;
            candidates.add(new Candidate(ti, memo, memoRaw));
        }

        Optional<Candidate> match = candidates.stream()
                .filter(c -> requestId.equals(c.memo.requestId))
                .max(Comparator.comparing(c -> c.transfer.executeBefore()));
        if (match.isPresent()) {
            return Result.ok(new Selection(match.get(), true));
        }

        Optional<Selection> fallback = candidates.stream()
                .filter(c -> c.transfer.executeBefore() != null && c.transfer.executeBefore().isAfter(cutoff))
                .max(Comparator.comparing(c -> c.transfer.executeBefore()))
                .map(c -> new Selection(c, false));
        if (fallback.isPresent()) {
            return Result.ok(fallback.get());
        }

        if (!invalidMemoCids.isEmpty()) {
            return Result.err(new ApiError(
                    ErrorCode.VALIDATION,
                    "memo JSON is invalid or missing",
                    Map.of("contractIds", invalidMemoCids),
                    false,
                    null,
                    null
            ));
        }

        return Result.err(preconditionError(
                "Inbound TransferInstruction not found for requestId (may be consumed already)",
                Map.of("requestId", requestId)
        ));
    }

    private SwapMemo parseMemo(String memoRaw) {
        if (memoRaw == null || memoRaw.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(memoRaw, SwapMemo.class);
        } catch (Exception e) {
            LOG.warn("Failed to parse swap memo JSON: {}", e.getMessage());
            return null;
        }
    }

    private Result<SwapMemo, ApiError> validateMemo(SwapMemo memo, String requestId, boolean requireRequestIdMatch) {
        if (memo == null) {
            return Result.err(validationError("Swap memo is missing", "memo"));
        }
        if (memo.requestId == null || memo.requestId.isBlank()) {
            return Result.err(validationError("memo.requestId is required", "requestId"));
        } else if (requireRequestIdMatch && !memo.requestId.equals(requestId)) {
            return Result.err(preconditionError(
                    "requestId does not match memo",
                    Map.of("requestId", requestId, "memoRequestId", memo.requestId)
            ));
        }
        if (memo.poolCid == null || memo.poolCid.isBlank()) {
            return Result.err(validationError("memo.poolCid is required", "poolCid"));
        }
        if (memo.direction == null || memo.direction.isBlank()) {
            return Result.err(validationError("memo.direction is required", "direction"));
        }
        if (memo.minOut == null || memo.minOut.isBlank()) {
            return Result.err(validationError("memo.minOut is required", "minOut"));
        }
        if (memo.receiverParty == null || memo.receiverParty.isBlank()) {
            return Result.err(validationError("memo.receiverParty is required", "receiverParty"));
        }
        if (memo.deadline == null || memo.deadline.isBlank()) {
            return Result.err(validationError("memo.deadline is required", "deadline"));
        }
        return Result.ok(memo);
    }

    private boolean matchesInstrument(TransferInstructionDto ti, HoldingPoolCreateRequest.InstrumentRef expected) {
        return expected != null
                && expected.admin != null
                && expected.id != null
                && expected.admin.equals(ti.admin())
                && expected.id.equals(ti.instrumentId());
    }

    private BigDecimal computeOutputAmount(HoldingPoolResponse pool, SwapDirection direction, BigDecimal amountIn) {
        BigDecimal reserveIn = direction == SwapDirection.A2B
                ? normalizeAmount(pool.reserveAmountA)
                : normalizeAmount(pool.reserveAmountB);
        BigDecimal reserveOut = direction == SwapDirection.A2B
                ? normalizeAmount(pool.reserveAmountB)
                : normalizeAmount(pool.reserveAmountA);

        BigDecimal feeRate = pool.feeBps != null
                ? new BigDecimal(pool.feeBps).movePointLeft(4).setScale(SwapConstants.SCALE, RoundingMode.DOWN)
                : SwapConstants.FEE_RATE;

        BigDecimal inputAfterFee = amountIn.multiply(BigDecimal.ONE.subtract(feeRate), MathContext.DECIMAL64)
                .setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        BigDecimal numerator = inputAfterFee.multiply(reserveOut, MathContext.DECIMAL64);
        BigDecimal denominator = reserveIn.add(inputAfterFee);
        if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        }
        return numerator.divide(denominator, SwapConstants.SCALE, RoundingMode.DOWN);
    }

    private BigDecimal getAvailableOut(HoldingPoolResponse pool, SwapDirection direction) {
        BigDecimal reserveOut = direction == SwapDirection.A2B
                ? normalizeAmount(pool.reserveAmountB)
                : normalizeAmount(pool.reserveAmountA);
        BigDecimal lockedOut = direction == SwapDirection.A2B
                ? normalizeAmount(pool.lockedAmountB)
                : normalizeAmount(pool.lockedAmountA);
        BigDecimal available = reserveOut.subtract(lockedOut);
        if (available.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        }
        return available.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
    }

    private CompletableFuture<Result<CommandServiceOuterClass.SubmitAndWaitForTransactionResponse, ApiError>> executeSwap(
            String poolCid,
            String intentCid,
            String outputHoldingCid,
            List<com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> disclosedContracts,
            String synchronizerId
    ) {
        ValueOuterClass.Record choiceArgs = ValueOuterClass.Record.newBuilder()
                .addFields(recordField("intentCid", contractIdValue(intentCid)))
                .addFields(recordField("outputHoldingCid", contractIdValue(outputHoldingCid)))
                .build();

        return ledgerApi.exerciseRawWithLabel(
                        "ExecuteSwap",
                        holdingPoolTemplateId(),
                        poolCid,
                        "ExecuteSwap",
                        choiceArgs,
                        List.of(authUtils.getAppProviderPartyId()),
                        List.of(),
                        disclosedContracts,
                        synchronizerId
                )
                .handle((resp, throwable) -> {
                    if (throwable != null) {
                        return Result.err(ApiError.of(ErrorCode.LEDGER_REJECTED, throwable.getMessage()));
                    }
                    return Result.ok(resp);
                });
    }

    private Result<PayoutResponse, ApiError> createPayout(
            HoldingPoolCreateRequest.InstrumentRef outputInstrument,
            String receiverParty,
            BigDecimal amountOut,
            String memo,
            Instant deadline,
            String requestId
    ) {
        PayoutRequest payout = new PayoutRequest();
        payout.receiverParty = receiverParty;
        payout.amount = amountOut.setScale(SwapConstants.SCALE, RoundingMode.DOWN).toPlainString();
        payout.memo = memo;

        long remainingSeconds = Duration.between(Instant.now(), deadline).getSeconds();
        if (remainingSeconds <= 0) {
            return Result.err(preconditionError("Swap deadline expired before payout", Map.of("deadline", deadline.toString())));
        }
        payout.executeBeforeSeconds = Math.min(remainingSeconds, 7200L);

        if ("Amulet".equalsIgnoreCase(outputInstrument.id)) {
            return payoutService.createAmuletPayout(payout, "swap-payout-amulet-" + requestId);
        }
        if ("CBTC".equalsIgnoreCase(outputInstrument.id)) {
            return payoutService.createCbtcPayout(payout, "swap-payout-cbtc-" + requestId);
        }
        return Result.err(validationError("Unsupported output instrument: " + outputInstrument.id, "instrumentId"));
    }

    private ValueOuterClass.Identifier holdingPoolTemplateId() {
        return ValueOuterClass.Identifier.newBuilder()
                .setPackageId(holdingPoolPackageId)
                .setModuleName("AMM.HoldingPool")
                .setEntityName("HoldingPool")
                .build();
    }

    private ValueOuterClass.Value contractIdValue(String cid) {
        return ValueOuterClass.Value.newBuilder().setContractId(cid).build();
    }


    private ValueOuterClass.RecordField recordField(String label, ValueOuterClass.Value value) {
        return ValueOuterClass.RecordField.newBuilder().setLabel(label).setValue(value).build();
    }

    private BigDecimal normalizeAmount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        }
        return new BigDecimal(value).setScale(SwapConstants.SCALE, RoundingMode.DOWN);
    }

    private String extractUpdateId(CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp) {
        if (resp == null || !resp.hasTransaction()) return null;
        return resp.getTransaction().getUpdateId();
    }


    private CompletableFuture<Result<SwapConsumeResponse, ApiError>> completedError(ApiError err) {
        return CompletableFuture.completedFuture(Result.err(err));
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

    private ApiError preconditionError(String message, Map<String, Object> details) {
        return new ApiError(
                ErrorCode.PRECONDITION_FAILED,
                message,
                details,
                false,
                null,
                null
        );
    }

    private ApiError domainError(String message, DomainError err) {
        return new ApiError(
                ErrorCode.INTERNAL,
                message,
                Map.of("cause", err.message(), "code", err.code()),
                false,
                null,
                null
        );
    }

    private ApiError annotateNoSynchronizer(ApiError error,
                                            String step,
                                            List<String> actAs,
                                            List<String> readAs,
                                            String synchronizerId) {
        if (error == null || error.message == null) {
            return error;
        }
        if (!error.message.contains("NO_SYNCHRONIZER_ON_WHICH_ALL_SUBMITTERS_CAN_SUBMIT")) {
            return error;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (error.details != null) {
            details.putAll(error.details);
        }
        details.put("step", step);
        details.put("actAs", actAs);
        details.put("readAs", readAs);
        details.put("synchronizerId", synchronizerId);
        return new ApiError(
                error.code,
                error.message,
                details,
                error.retryable,
                error.grpcStatus,
                error.grpcDescription
        );
    }

    private record Candidate(TransferInstructionDto transfer, SwapMemo memo, String memoRaw) { }
    private record Selection(Candidate candidate, boolean matchedRequestId) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class SwapMemo {
        public int v;
        public String requestId;
        public String poolCid;
        public String direction;
        public String minOut;
        public String receiverParty;
        public String deadline;
    }

    private enum SwapDirection {
        A2B("AtoB"),
        B2A("BtoA");

        private final String damlConstructor;

        SwapDirection(String damlConstructor) {
            this.damlConstructor = damlConstructor;
        }

        static SwapDirection fromMemo(String memoDirection) {
            if (memoDirection == null) return null;
            String norm = memoDirection.trim().toUpperCase();
            return switch (norm) {
                case "A2B" -> A2B;
                case "B2A" -> B2A;
                default -> null;
            };
        }
    }

    
}

