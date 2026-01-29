package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.constants.SwapConstants;
import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ErrorCode;
import com.digitalasset.quickstart.dto.HoldingPoolCreateRequest;
import com.digitalasset.quickstart.dto.HoldingPoolResponse;
import com.digitalasset.quickstart.dto.LiquidityConsumeRequest;
import com.digitalasset.quickstart.dto.LiquidityConsumeResponse;
import com.digitalasset.quickstart.dto.LiquidityInspectResponse;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionDto;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionWithMemo;
import com.digitalasset.quickstart.service.TransferInstructionChoiceContextService.ChoiceContextResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("devnet")
public class LiquidityTiProcessorService {

    private static final Logger LOG = LoggerFactory.getLogger(LiquidityTiProcessorService.class);

    private final TransferInstructionAcsQueryService tiQueryService;
    private final HoldingPoolService holdingPoolService;
    private final TransferInstructionChoiceContextService choiceContextService;
    private final LedgerApi ledgerApi;
    private final AuthUtils authUtils;
    private final IdempotencyService idempotencyService;
    private final TransactionHistoryService transactionHistoryService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${holdingpool.package-id:}")
    private String holdingPoolPackageId;

    public LiquidityTiProcessorService(
            TransferInstructionAcsQueryService tiQueryService,
            HoldingPoolService holdingPoolService,
            TransferInstructionChoiceContextService choiceContextService,
            LedgerApi ledgerApi,
            AuthUtils authUtils,
            IdempotencyService idempotencyService,
            TransactionHistoryService transactionHistoryService
    ) {
        this.tiQueryService = tiQueryService;
        this.holdingPoolService = holdingPoolService;
        this.choiceContextService = choiceContextService;
        this.ledgerApi = ledgerApi;
        this.authUtils = authUtils;
        this.idempotencyService = idempotencyService;
        this.transactionHistoryService = transactionHistoryService;
    }

    @WithSpan
    public CompletableFuture<Result<LiquidityConsumeResponse, ApiError>> consume(final LiquidityConsumeRequest request) {
        if (request == null || request.requestId == null || request.requestId.isBlank()) {
            return CompletableFuture.completedFuture(Result.err(validationError("requestId is required", "requestId")));
        }
        if (request.poolCid == null || request.poolCid.isBlank()) {
            return CompletableFuture.completedFuture(Result.err(validationError("poolCid is required", "poolCid")));
        }
        Object cached = idempotencyService.checkIdempotency(request.requestId);
        if (cached instanceof LiquidityConsumeResponse cachedResponse) {
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

        return holdingPoolService.getByContractId(request.poolCid)
                .thenCompose(poolResult -> {
                    if (poolResult.isErr()) {
                        return completedError(domainError("Pool not found or not visible", poolResult.getErrorUnsafe()));
                    }
                    HoldingPoolResponse pool = poolResult.getValueUnsafe();
                    if (pool.status == null || !"active".equalsIgnoreCase(pool.status)) {
                        return completedError(preconditionError("Pool is not active", Map.of("status", pool.status)));
                    }
                    if (pool.instrumentA == null || pool.instrumentB == null) {
                        return completedError(preconditionError("Pool instruments are missing", Map.of("poolCid", request.poolCid)));
                    }

                    Result<PairSelection, ApiError> selection = selectPair(
                            pending.getValueUnsafe(),
                            request.requestId,
                            request.poolCid,
                            pool,
                            maxAgeSeconds,
                            operator
                    );
                    if (selection.isErr()) {
                        return completedError(selection.getErrorUnsafe());
                    }
                    PairSelection picked = selection.getValueUnsafe();
                    Candidate a = picked.candidateA;
                    Candidate b = picked.candidateB;

                    Instant deadline = picked.deadline;
                    BigDecimal amountA = picked.amountA;
                    BigDecimal amountB = picked.amountB;

                    Result<ChoiceContextResult, ApiError> ctxAResult =
                            choiceContextService.resolveDisclosedContracts(a.transfer.contractId(), pool.instrumentA.admin, request.requestId);
                    if (ctxAResult.isErr()) {
                        return completedError(ctxAResult.getErrorUnsafe());
                    }
                    Result<ChoiceContextResult, ApiError> ctxBResult =
                            choiceContextService.resolveDisclosedContracts(b.transfer.contractId(), pool.instrumentB.admin, request.requestId);
                    if (ctxBResult.isErr()) {
                        return completedError(ctxBResult.getErrorUnsafe());
                    }
                    ChoiceContextResult ctxA = ctxAResult.getValueUnsafe();
                    ChoiceContextResult ctxB = ctxBResult.getValueUnsafe();

                    List<com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> disclosed =
                            mergeDisclosed(ctxA.disclosedContracts(), ctxB.disclosedContracts());
                    String synchronizerId = firstNonBlank(ctxA.synchronizerId(), ctxB.synchronizerId());

                    LOG.info("[LiquidityConsume] requestId={} operator={} poolCid={} tiA={} tiB={} amountA={} amountB={} provider={} ctxAKeys={} ctxBKeys={} actAs=[{}]",
                            request.requestId,
                            operator,
                            request.poolCid,
                            a.transfer.contractId(),
                            b.transfer.contractId(),
                            amountA.toPlainString(),
                            amountB.toPlainString(),
                            picked.providerParty,
                            ctxA.contextKeyCount(),
                            ctxB.contextKeyCount(),
                            operator);

                    ValueOuterClass.Record choiceArgs = ValueOuterClass.Record.newBuilder()
                            .addFields(recordField("provider", partyValue(picked.providerParty)))
                            .addFields(recordField("tiCidA", contractIdValue(a.transfer.contractId())))
                            .addFields(recordField("tiCidB", contractIdValue(b.transfer.contractId())))
                            .addFields(recordField("amountA", numericValue(amountA)))
                            .addFields(recordField("amountB", numericValue(amountB)))
                            .addFields(recordField("deadline", timestampValue(deadline)))
                            .addFields(recordField("poolIdText", textValue(request.poolCid)))
                            .addFields(recordField("acceptExtraArgsA", optionalValue(recordValue(ctxA.extraArgs()))))
                            .addFields(recordField("acceptExtraArgsB", optionalValue(recordValue(ctxB.extraArgs()))))
                            .build();

                    return ledgerApi.exerciseRawWithLabel(
                                    "AddLiquidityFromTransferInstructionsV1",
                                    holdingPoolTemplateId(),
                                    request.poolCid,
                                    "AddLiquidityFromTransferInstructionsV1",
                                    choiceArgs,
                                    List.of(operator),
                                    List.of(),
                                    disclosed,
                                    synchronizerId
                            )
                            .handle((resp, throwable) -> {
                                if (throwable != null) {
                                    return Result.<CommandServiceOuterClass.SubmitAndWaitForTransactionResponse, ApiError>err(
                                            annotateNoSynchronizer(ApiError.of(ErrorCode.LEDGER_REJECTED, throwable.getMessage()),
                                                    "AddLiquidityFromTransferInstructionsV1",
                                                    List.of(operator),
                                                    List.of(),
                                                    synchronizerId));
                                }
                                return Result.ok(resp);
                            })
                            .thenCompose(result -> {
                                if (result.isErr()) {
                                    return completedError(result.getErrorUnsafe());
                                }
                                CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp = result.getValueUnsafe();
                                String updateId = extractUpdateId(resp);
                                LiquidityOutcome outcome = extractLiquidityOutcome(resp);
                                if (updateId == null || updateId.isBlank()) {
                                    return completedError(ApiError.of(ErrorCode.INTERNAL, "AddLiquidity returned no updateId"));
                                }
                                if (outcome == null || outcome.newPoolCid == null) {
                                    return completedError(ApiError.of(ErrorCode.INTERNAL, "AddLiquidity returned no result"));
                                }

                                LiquidityConsumeResponse response = new LiquidityConsumeResponse();
                                response.requestId = request.requestId;
                                response.poolCid = request.poolCid;
                                response.newPoolCid = outcome.newPoolCid;
                                response.providerParty = picked.providerParty;
                                response.tiCidA = a.transfer.contractId();
                                response.tiCidB = b.transfer.contractId();
                                response.amountA = amountA.toPlainString();
                                response.amountB = amountB.toPlainString();
                                response.lpMinted = outcome.lpMinted.toPlainString();
                                response.newReserveA = outcome.newReserveA.toPlainString();
                                response.newReserveB = outcome.newReserveB.toPlainString();
                                response.ledgerUpdateId = updateId;
                                response.executeStatus = "SUCCEEDED";

                                try {
                                    transactionHistoryService.recordAddLiquidity(
                                            outcome.newPoolCid,
                                            outcome.newPoolCid,
                                            displaySymbol(pool.instrumentA.id),
                                            displaySymbol(pool.instrumentB.id),
                                            amountA,
                                            amountB,
                                            BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN),
                                            outcome.lpMinted,
                                            picked.providerParty
                                    );
                                } catch (Exception e) {
                                    LOG.warn("[LiquidityConsume] Failed to record history: {}", e.getMessage());
                                }

                                idempotencyService.registerSuccess(request.requestId, request.requestId, updateId, response);
                                return CompletableFuture.completedFuture(Result.ok(response));
                            });
                });
    }

    @WithSpan
    public CompletableFuture<Result<LiquidityInspectResponse, ApiError>> inspect(final String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return CompletableFuture.completedFuture(Result.err(validationError("requestId is required", "requestId")));
        }
        Object cached = idempotencyService.checkIdempotency(requestId);
        boolean alreadyProcessed = cached instanceof LiquidityConsumeResponse;

        String operator = authUtils.getAppProviderPartyId();
        Result<List<TransferInstructionWithMemo>, DomainError> pending = tiQueryService.listForReceiverWithMemo(operator);
        if (pending.isErr()) {
            return CompletableFuture.completedFuture(Result.err(domainError("Failed to query pending TIs", pending.getErrorUnsafe())));
        }

        PairSelection selection = selectPairUnsafe(pending.getValueUnsafe(), requestId, operator);
        LiquidityInspectResponse response = new LiquidityInspectResponse();
        response.requestId = requestId;
        response.alreadyProcessed = alreadyProcessed;

        if (selection == null) {
            return CompletableFuture.completedFuture(Result.ok(response));
        }

        response.poolCid = selection.poolCid;
        response.providerParty = selection.providerParty;
        response.tiCidA = selection.candidateA.transfer.contractId();
        response.tiCidB = selection.candidateB.transfer.contractId();
        response.memoRaw = selection.memoRaw;
        response.deadline = selection.deadline != null ? selection.deadline.toString() : null;
        response.deadlineExpired = selection.deadline != null && Instant.now().isAfter(selection.deadline);

        if (selection.poolCid == null) {
            return CompletableFuture.completedFuture(Result.ok(response));
        }

        return holdingPoolService.getByContractId(selection.poolCid)
                .thenApply(poolResult -> {
                    if (poolResult.isOk()) {
                        response.poolStatus = poolResult.getValueUnsafe().status;
                    } else {
                        response.poolStatus = "unknown";
                    }
                    return Result.ok(response);
                });
    }

    private Result<PairSelection, ApiError> selectPair(
            List<TransferInstructionWithMemo> items,
            String requestId,
            String poolCid,
            HoldingPoolResponse pool,
            long maxAgeSeconds,
            String operator
    ) {
        List<Candidate> candidates = candidatesForRequest(items, requestId, operator);
        if (candidates.isEmpty()) {
            return Result.err(ApiError.of(ErrorCode.NOT_FOUND, "No inbound TIs found for requestId"));
        }
        Candidate a = candidates.stream()
                .filter(c -> matchesInstrument(c.transfer, pool.instrumentA))
                .max(Comparator.comparing(c -> c.transfer.executeBefore()))
                .orElse(null);
        Candidate b = candidates.stream()
                .filter(c -> matchesInstrument(c.transfer, pool.instrumentB))
                .max(Comparator.comparing(c -> c.transfer.executeBefore()))
                .orElse(null);
        if (a == null || b == null) {
            return Result.err(preconditionError("Missing inbound TIs for pool instruments",
                    Map.of("instrumentA", pool.instrumentA.id, "instrumentB", pool.instrumentB.id)));
        }
        if (a.transfer.contractId().equals(b.transfer.contractId())) {
            return Result.err(preconditionError("TIs must be distinct", Map.of("tiCid", a.transfer.contractId())));
        }

        LiquidityMemo memo = a.memo != null ? a.memo : b.memo;
        if (memo == null || memo.poolCid == null || memo.poolCid.isBlank()) {
            return Result.err(validationError("memo.poolCid is required", "poolCid"));
        }
        if (!memo.poolCid.equals(poolCid)) {
            return Result.err(preconditionError("memo.poolCid does not match request", Map.of("memoPoolCid", memo.poolCid, "requestPoolCid", poolCid)));
        }
        String provider = memo.receiverParty;
        if (provider == null || provider.isBlank()) {
            return Result.err(validationError("memo.receiverParty is required", "receiverParty"));
        }
        if (a.memo != null && a.memo.receiverParty != null && !provider.equals(a.memo.receiverParty)) {
            return Result.err(preconditionError("receiverParty mismatch across TIs", Map.of("tiCidA", a.transfer.contractId())));
        }
        if (b.memo != null && b.memo.receiverParty != null && !provider.equals(b.memo.receiverParty)) {
            return Result.err(preconditionError("receiverParty mismatch across TIs", Map.of("tiCidB", b.transfer.contractId())));
        }

        Instant deadline = parseDeadline(memo.deadline);
        Instant now = Instant.now();
        if (deadline == null || now.isAfter(deadline)) {
            return Result.err(preconditionError("Liquidity deadline has expired", Map.of("deadline", memo.deadline)));
        }
        Instant cutoff = now.minusSeconds(maxAgeSeconds);
        if (a.transfer.executeBefore() != null && a.transfer.executeBefore().isBefore(cutoff)) {
            return Result.err(preconditionError("TI A is too old", Map.of("executeBefore", a.transfer.executeBefore().toString())));
        }
        if (b.transfer.executeBefore() != null && b.transfer.executeBefore().isBefore(cutoff)) {
            return Result.err(preconditionError("TI B is too old", Map.of("executeBefore", b.transfer.executeBefore().toString())));
        }

        if (a.transfer.sender() != null && !provider.equals(a.transfer.sender())) {
            return Result.err(preconditionError("TI A sender does not match receiverParty",
                    Map.of("tiCidA", a.transfer.contractId(), "sender", a.transfer.sender(), "receiverParty", provider)));
        }
        if (b.transfer.sender() != null && !provider.equals(b.transfer.sender())) {
            return Result.err(preconditionError("TI B sender does not match receiverParty",
                    Map.of("tiCidB", b.transfer.contractId(), "sender", b.transfer.sender(), "receiverParty", provider)));
        }
        if (a.transfer.receiver() != null && !operator.equals(a.transfer.receiver())) {
            return Result.err(preconditionError("TI A receiver is not operator",
                    Map.of("tiCidA", a.transfer.contractId(), "receiver", a.transfer.receiver())));
        }
        if (b.transfer.receiver() != null && !operator.equals(b.transfer.receiver())) {
            return Result.err(preconditionError("TI B receiver is not operator",
                    Map.of("tiCidB", b.transfer.contractId(), "receiver", b.transfer.receiver())));
        }

        BigDecimal amountA;
        BigDecimal amountB;
        try {
            amountA = normalizeAmount(a.transfer.amount());
            amountB = normalizeAmount(b.transfer.amount());
        } catch (Exception e) {
            return Result.err(validationError("amount format is invalid", "amount"));
        }
        if (amountA.compareTo(BigDecimal.ZERO) <= 0 || amountB.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.err(preconditionError("Amounts must be positive", Map.of("amountA", amountA, "amountB", amountB)));
        }

        PairSelection selection = new PairSelection(
                a,
                b,
                memo.poolCid,
                memo,
                deadline,
                provider,
                rowMemo(a, b)
        );
        selection.amountA = amountA;
        selection.amountB = amountB;
        return Result.ok(selection);
    }

    private PairSelection selectPairUnsafe(
            List<TransferInstructionWithMemo> items,
            String requestId,
            String operator
    ) {
        List<Candidate> candidates = candidatesForRequest(items, requestId, operator);
        if (candidates.isEmpty()) {
            return null;
        }

        Optional<Candidate> newest = candidates.stream()
                .max(Comparator.comparing(c -> c.transfer.executeBefore()));
        if (newest.isEmpty()) {
            return null;
        }

        LiquidityMemo memo = newest.get().memo;
        String provider = memo.receiverParty;
        if (provider == null || provider.isBlank()) {
            return null;
        }

        Candidate a = candidates.stream()
                .filter(c -> provider.equals(c.transfer.sender()))
                .filter(c -> c.memo != null && provider.equals(c.memo.receiverParty))
                .max(Comparator.comparing(c -> c.transfer.executeBefore()))
                .orElse(null);
        Candidate b = candidates.stream()
                .filter(c -> provider.equals(c.transfer.sender()))
                .filter(c -> c.memo != null && provider.equals(c.memo.receiverParty))
                .filter(c -> !c.transfer.contractId().equals(a != null ? a.transfer.contractId() : ""))
                .max(Comparator.comparing(c -> c.transfer.executeBefore()))
                .orElse(null);

        if (a == null || b == null) {
            return null;
        }

        Instant deadline = parseDeadline(memo.deadline);
        return new PairSelection(a, b, memo.poolCid, memo, deadline, provider, rowMemo(a, b));
    }

    private List<Candidate> candidatesForRequest(
            List<TransferInstructionWithMemo> items,
            String requestId,
            String operator
    ) {
        List<Candidate> candidates = new ArrayList<>();
        for (TransferInstructionWithMemo row : items) {
            TransferInstructionDto ti = row.transferInstruction();
            if (ti == null || ti.contractId() == null) continue;
            if (operator != null && ti.receiver() != null && !operator.equals(ti.receiver())) continue;
            LiquidityMemo memo = parseMemo(row.memo());
            if (memo == null || memo.requestId == null) continue;
            if (!requestId.equals(memo.requestId)) continue;
            candidates.add(new Candidate(ti, memo, row.memo()));
        }
        return candidates;
    }

    private String rowMemo(Candidate a, Candidate b) {
        if (a != null && a.memoRaw != null && !a.memoRaw.isBlank()) return a.memoRaw;
        if (b != null) return b.memoRaw;
        return null;
    }

    private LiquidityMemo parseMemo(String memoRaw) {
        if (memoRaw == null || memoRaw.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(memoRaw, LiquidityMemo.class);
        } catch (Exception e) {
            LOG.warn("Failed to parse liquidity memo JSON: {}", e.getMessage());
            return null;
        }
    }

    private Instant parseDeadline(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean matchesInstrument(TransferInstructionDto ti, HoldingPoolCreateRequest.InstrumentRef expected) {
        return expected != null
                && expected.admin != null
                && expected.id != null
                && expected.admin.equals(ti.admin())
                && expected.id.equals(ti.instrumentId());
    }

    private BigDecimal normalizeAmount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        }
        return new BigDecimal(value).setScale(SwapConstants.SCALE, RoundingMode.DOWN);
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

    private ValueOuterClass.Value partyValue(String party) {
        return ValueOuterClass.Value.newBuilder().setParty(party).build();
    }

    private ValueOuterClass.Value textValue(String text) {
        return ValueOuterClass.Value.newBuilder().setText(text).build();
    }

    private ValueOuterClass.Value recordValue(ValueOuterClass.Record record) {
        return ValueOuterClass.Value.newBuilder().setRecord(record).build();
    }

    private ValueOuterClass.Value optionalValue(ValueOuterClass.Value value) {
        ValueOuterClass.Optional.Builder ob = ValueOuterClass.Optional.newBuilder();
        if (value != null) {
            ob.setValue(value);
        }
        return ValueOuterClass.Value.newBuilder().setOptional(ob.build()).build();
    }

    private ValueOuterClass.Value numericValue(BigDecimal value) {
        String raw = value.setScale(SwapConstants.SCALE, RoundingMode.DOWN).toPlainString();
        return ValueOuterClass.Value.newBuilder().setNumeric(raw).build();
    }

    private ValueOuterClass.Value timestampValue(Instant instant) {
        long micros = instant.toEpochMilli() * 1000L;
        return ValueOuterClass.Value.newBuilder().setTimestamp(micros).build();
    }

    private ValueOuterClass.RecordField recordField(String label, ValueOuterClass.Value value) {
        return ValueOuterClass.RecordField.newBuilder().setLabel(label).setValue(value).build();
    }

    private List<com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> mergeDisclosed(
            List<com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> a,
            List<com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> b
    ) {
        Map<String, com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> byCid = new LinkedHashMap<>();
        if (a != null) {
            for (var dc : a) {
                if (dc != null && !dc.getContractId().isBlank()) {
                    byCid.putIfAbsent(dc.getContractId(), dc);
                }
            }
        }
        if (b != null) {
            for (var dc : b) {
                if (dc != null && !dc.getContractId().isBlank()) {
                    byCid.putIfAbsent(dc.getContractId(), dc);
                }
            }
        }
        return new ArrayList<>(byCid.values());
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private String displaySymbol(String instrumentId) {
        if (instrumentId == null) {
            return "";
        }
        if ("Amulet".equalsIgnoreCase(instrumentId.trim())) {
            return "CC";
        }
        return instrumentId.trim();
    }

    private String extractUpdateId(CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp) {
        if (resp == null || !resp.hasTransaction()) return null;
        return resp.getTransaction().getUpdateId();
    }

    private LiquidityOutcome extractLiquidityOutcome(CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp) {
        if (resp == null || !resp.hasTransaction()) return null;
        for (var ev : resp.getTransaction().getEventsList()) {
            if (!ev.hasExercised()) continue;
            if (!"AddLiquidityFromTransferInstructionsV1".equals(ev.getExercised().getChoice())) continue;
            var result = ev.getExercised().getExerciseResult();
            if (!result.hasRecord()) continue;
            var rec = result.getRecord();
            if (rec.getFieldsCount() < 4) continue;
            String newPoolCid = rec.getFields(0).getValue().getContractId();
            BigDecimal lpMinted = numericFromValue(rec.getFields(1).getValue());
            BigDecimal newReserveA = numericFromValue(rec.getFields(2).getValue());
            BigDecimal newReserveB = numericFromValue(rec.getFields(3).getValue());
            if (newPoolCid == null || newPoolCid.isBlank()) continue;
            return new LiquidityOutcome(newPoolCid, lpMinted, newReserveA, newReserveB);
        }
        return null;
    }

    private BigDecimal numericFromValue(ValueOuterClass.Value value) {
        if (value == null || value.getSumCase() != ValueOuterClass.Value.SumCase.NUMERIC) {
            return BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        }
        try {
            return new BigDecimal(value.getNumeric());
        } catch (Exception e) {
            return BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        }
    }

    private CompletableFuture<Result<LiquidityConsumeResponse, ApiError>> completedError(ApiError err) {
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
                Map.of("cause", err.message()),
                false,
                null,
                null
        );
    }

    private ApiError annotateNoSynchronizer(
            ApiError error,
            String step,
            List<String> actAs,
            List<String> readAs,
            String synchronizerId
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (error.details != null) {
            details.putAll(error.details);
        }
        details.put("step", step);
        details.put("actAs", actAs);
        details.put("readAs", readAs);
        if (synchronizerId != null) {
            details.put("synchronizerId", synchronizerId);
        }
        return new ApiError(
                error.code,
                error.message,
                details,
                error.retryable,
                error.grpcStatus,
                error.grpcDescription
        );
    }

    private record Candidate(TransferInstructionDto transfer, LiquidityMemo memo, String memoRaw) {}

    private static final class PairSelection {
        private final Candidate candidateA;
        private final Candidate candidateB;
        private final String poolCid;
        private final LiquidityMemo memo;
        private final Instant deadline;
        private final String providerParty;
        private final String memoRaw;
        private BigDecimal amountA;
        private BigDecimal amountB;

        private PairSelection(Candidate candidateA,
                              Candidate candidateB,
                              String poolCid,
                              LiquidityMemo memo,
                              Instant deadline,
                              String providerParty,
                              String memoRaw) {
            this.candidateA = candidateA;
            this.candidateB = candidateB;
            this.poolCid = poolCid;
            this.memo = memo;
            this.deadline = deadline;
            this.providerParty = providerParty;
            this.memoRaw = memoRaw;
        }
    }

    private record LiquidityOutcome(String newPoolCid, BigDecimal lpMinted, BigDecimal newReserveA, BigDecimal newReserveB) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class LiquidityMemo {
        public int v;
        public String requestId;
        public String poolCid;
        public String receiverParty;
        public String deadline;
    }
}

