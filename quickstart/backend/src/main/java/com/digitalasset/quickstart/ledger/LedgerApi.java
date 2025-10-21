// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.ledger;

import com.daml.ledger.api.v2.*;
import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.security.TokenProvider;
import com.digitalasset.transcode.Converter;
import com.digitalasset.transcode.codec.proto.ProtobufCodec;
import com.digitalasset.transcode.java.Choice;
import com.digitalasset.transcode.java.ContractId;
import com.digitalasset.transcode.java.Template;
import com.digitalasset.transcode.java.Utils;
import com.digitalasset.transcode.schema.Dictionary;
import com.digitalasset.transcode.schema.Identifier;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import daml.Daml;
import io.grpc.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.digitalasset.quickstart.utility.TracingUtils.*;

@Component
public class LedgerApi {
    private final String APP_ID;
    private final CommandSubmissionServiceGrpc.CommandSubmissionServiceFutureStub submission;
    private final CommandServiceGrpc.CommandServiceFutureStub commands;
    private final StateServiceGrpc.StateServiceStub stateService;
    private final Dictionary<Converter<Object, ValueOuterClass.Value>> dto2Proto;
    private final Dictionary<Converter<ValueOuterClass.Value, Object>> proto2Dto;

    private final Logger logger = LoggerFactory.getLogger(LedgerApi.class);
    private final String appProviderParty;

    @Autowired
    public LedgerApi(LedgerConfig ledgerConfig, Optional<TokenProvider> tokenProvider, AuthUtils authUtils) {
        APP_ID = ledgerConfig.getApplicationId();
        appProviderParty = authUtils.getAppProviderPartyId();
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(ledgerConfig.getHost(), ledgerConfig.getPort())
                .usePlaintext();
        if (tokenProvider.isEmpty()) {
            throw new IllegalStateException("TokenProvider is required for authentication");
        }
        builder.intercept(new Interceptor(tokenProvider.get()));
        ManagedChannel channel = builder.build();

        // Single log statement, not duplicating attributes for spans, so leaving as-is:
        logger.atInfo()
                .addKeyValue("host", ledgerConfig.getHost())
                .addKeyValue("port", ledgerConfig.getPort())
                .log("Connected to ledger");

        submission = CommandSubmissionServiceGrpc.newFutureStub(channel);
        commands = CommandServiceGrpc.newFutureStub(channel);
        stateService = StateServiceGrpc.newStub(channel);  // Use regular stub for streaming

        ProtobufCodec protoCodec = new ProtobufCodec();
        dto2Proto = Utils.getConverters(Daml.ENTITIES, protoCodec);
        proto2Dto = Utils.getConverters(protoCodec, Daml.ENTITIES);
    }

    @WithSpan
    public <T extends Template> CompletableFuture<Void> create(
            T entity,
            String commandId
    ) {
        var ctx = tracingCtx(logger, "Creating contract",
                "commandId", commandId,
                "templateId", entity.templateId().toString(),
                "applicationId", APP_ID
        );
        return traceWithStartEvent(ctx, () -> {
            CommandsOuterClass.Command.Builder command = CommandsOuterClass.Command.newBuilder();
            ValueOuterClass.Value payload = dto2Proto.template(entity.templateId()).convert(entity);
            command.getCreateBuilder().setTemplateId(toIdentifier(entity.templateId())).setCreateArguments(payload.getRecord());
            return submitCommands(List.of(command.build()), commandId).thenApply(submitResponse -> null);
        });
    }

    @WithSpan
    public <T extends Template, Result, C extends Choice<T, Result>>
    CompletableFuture<Result> exerciseAndGetResult(
            ContractId<T> contractId,
            C choice,
            String commandId
    ) {
        return exerciseAndGetResult(contractId, choice, commandId, List.of());
    }

    /**
     * Exercise a choice with multiple actAs parties (for multi-party workflows)
     * This is essential for AddLiquidity (liquidityProvider + poolParty + lpIssuer)
     * and other multi-party operations.
     *
     * @param contractId The contract to exercise the choice on
     * @param choice The choice to exercise
     * @param commandId Unique command ID for idempotency
     * @param actAsParties List of parties that will act (must match DAML controllers)
     * @param readAsParties List of parties that can read (for visibility)
     * @return The result of the choice execution
     */
    @WithSpan
    public <T extends Template, Result, C extends Choice<T, Result>>
    CompletableFuture<Result> exerciseAndGetResultWithParties(
            ContractId<T> contractId,
            C choice,
            String commandId,
            List<String> actAsParties,
            List<String> readAsParties
    ) {
        return exerciseAndGetResultWithParties(contractId, choice, commandId, actAsParties, readAsParties, List.of());
    }

    /**
     * Exercise a choice with multiple actAs parties and disclosed contracts
     */
    @WithSpan
    public <T extends Template, Result, C extends Choice<T, Result>>
    CompletableFuture<Result> exerciseAndGetResultWithParties(
            ContractId<T> contractId,
            C choice,
            String commandId,
            List<String> actAsParties,
            List<String> readAsParties,
            List<CommandsOuterClass.DisclosedContract> disclosedContracts
    ) {
        var ctx = tracingCtx(logger, "Exercising choice with multi-party",
                "commandId", commandId,
                "contractId", contractId.getContractId,
                "choiceName", choice.choiceName(),
                "templateId", choice.templateId().toString(),
                "actAsParties", String.join(",", actAsParties),
                "readAsParties", String.join(",", readAsParties),
                "applicationId", APP_ID
        );
        return trace(ctx, () -> {
            CommandsOuterClass.Command.Builder cmdBuilder = CommandsOuterClass.Command.newBuilder();
            ValueOuterClass.Value payload =
                    dto2Proto.choiceArgument(choice.templateId(), choice.choiceName()).convert(choice);

            cmdBuilder.getExerciseBuilder()
                    .setTemplateId(toIdentifier(choice.templateId()))
                    .setContractId(contractId.getContractId)
                    .setChoice(choice.choiceName())
                    .setChoiceArgument(payload);

            // Build Commands with multi-party actAs and readAs
            CommandsOuterClass.Commands.Builder commandsBuilder = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(commandId)
                    .addAllActAs(actAsParties)      // Multi-party actAs!
                    .addAllReadAs(readAsParties)    // Multi-party readAs!
                    .addCommands(cmdBuilder.build());

            if (disclosedContracts != null && !disclosedContracts.isEmpty()) {
                commandsBuilder.addAllDisclosedContracts(disclosedContracts);
            }

            CommandServiceOuterClass.SubmitAndWaitRequest request =
                    CommandServiceOuterClass.SubmitAndWaitRequest.newBuilder()
                            .setCommands(commandsBuilder.build())
                            .build();

            addEventWithAttributes(Span.current(), "built ledger submit request", Map.of(
                "actAsParties", String.join(",", actAsParties),
                "readAsParties", String.join(",", readAsParties)
            ));
            logger.info("Submitting multi-party ledger command: actAs={}, readAs={}", actAsParties, readAsParties);

            return toCompletableFuture(commands.submitAndWaitForTransactionTree(request))
                    .thenApply(response -> {
                        TransactionOuterClass.TransactionTree txTree = response.getTransaction();
                        long offset = txTree.getOffset();
                        String workflowId = txTree.getWorkflowId();
                        Map<Integer, TransactionOuterClass.TreeEvent> eventsById = txTree.getEventsByIdMap();
                        Integer eventId = eventsById.isEmpty() ? null : Collections.min(eventsById.keySet());
                        TransactionOuterClass.TreeEvent event = eventId != null ? txTree.getEventsByIdMap().get(eventId) : null;

                        Map<String, Object> completionAttrs = new HashMap<>();
                        completionAttrs.put("ledgerOffset", offset);
                        completionAttrs.put("workflowId", workflowId);
                        completionAttrs.put("actAsParties", String.join(",", actAsParties));
                        if (eventId != null) {
                            completionAttrs.put("eventId", eventId);
                        }

                        setSpanAttributes(Span.current(), completionAttrs);
                        logInfo(logger, "Exercised multi-party choice", completionAttrs);

                        ValueOuterClass.Value resultPayload = event != null ? event.getExercised().getExerciseResult() : ValueOuterClass.Value.getDefaultInstance();

                        @SuppressWarnings("unchecked")
                        Result result = (Result) proto2Dto.choiceResult(choice.templateId(), choice.choiceName()).convert(resultPayload);
                        return result;
                    })
                    .exceptionally(ex -> {
                        logger.error("Multi-party choice exercise failed: commandId={}, actAs={}, error={}",
                                commandId, actAsParties, ex.getMessage());
                        throw new RuntimeException("Failed to exercise choice with multi-party: " + ex.getMessage(), ex);
                    });
        });
    }

    @WithSpan
    public <T extends Template, Result, C extends Choice<T, Result>>
    CompletableFuture<Result> exerciseAndGetResult(
            ContractId<T> contractId,
            C choice,
            String commandId,
            List<CommandsOuterClass.DisclosedContract> disclosedContracts
    ) {
        var ctx = tracingCtx(logger, "Exercising choice",
                "commandId", commandId,
                "contractId", contractId.getContractId,
                "choiceName", choice.choiceName(),
                "templateId", choice.templateId().toString(),
                "applicationId", APP_ID
        );
        return trace(ctx, () -> {
            CommandsOuterClass.Command.Builder cmdBuilder = CommandsOuterClass.Command.newBuilder();
            ValueOuterClass.Value payload =
                    dto2Proto.choiceArgument(choice.templateId(), choice.choiceName()).convert(choice);

            cmdBuilder.getExerciseBuilder()
                    .setTemplateId(toIdentifier(choice.templateId()))
                    .setContractId(contractId.getContractId)
                    .setChoice(choice.choiceName())
                    .setChoiceArgument(payload);

            CommandsOuterClass.Commands.Builder commandsBuilder = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(commandId)
                    .addActAs(appProviderParty)
                    .addReadAs(appProviderParty)
                    .addCommands(cmdBuilder.build());

            if (disclosedContracts != null && !disclosedContracts.isEmpty()) {
                commandsBuilder.addAllDisclosedContracts(disclosedContracts);
            }

            CommandServiceOuterClass.SubmitAndWaitRequest request =
                    CommandServiceOuterClass.SubmitAndWaitRequest.newBuilder()
                            .setCommands(commandsBuilder.build())
                            .build();

            addEventWithAttributes(Span.current(), "built ledger submit request", Map.of());
            logger.info("Submitting ledger command");
            return toCompletableFuture(commands.submitAndWaitForTransactionTree(request))
                    .thenApply(response -> {
                        TransactionOuterClass.TransactionTree txTree = response.getTransaction();
                        long offset = txTree.getOffset();
                        String workflowId = txTree.getWorkflowId();
                        Map<Integer, TransactionOuterClass.TreeEvent> eventsById = txTree.getEventsByIdMap();
                        Integer eventId = eventsById.isEmpty() ? null : Collections.min(eventsById.keySet());
                        TransactionOuterClass.TreeEvent event = eventId != null ? txTree.getEventsByIdMap().get(eventId) : null;

                        Map<String, Object> completionAttrs = new HashMap<>();
                        completionAttrs.put("ledgerOffset", offset);
                        completionAttrs.put("workflowId", workflowId);
                        if (eventId != null) {
                            completionAttrs.put("eventId", eventId);
                        }

                        setSpanAttributes(Span.current(), completionAttrs);
                        logInfo(logger, "Exercised choice", completionAttrs);

                        ValueOuterClass.Value resultPayload = event != null ? event.getExercised().getExerciseResult() : ValueOuterClass.Value.getDefaultInstance();

                        @SuppressWarnings("unchecked")
                        Result result = (Result) proto2Dto.choiceResult(choice.templateId(), choice.choiceName()).convert(resultPayload);
                        return result;
                    });
        });
    }

    @WithSpan
    public CompletableFuture<CommandSubmissionServiceOuterClass.SubmitResponse> submitCommands(
            List<CommandsOuterClass.Command> cmds,
            String commandId
    ) {
        return submitCommands(cmds, commandId, List.of());
    }

    @WithSpan
    public CompletableFuture<CommandSubmissionServiceOuterClass.SubmitResponse> submitCommands(
            List<CommandsOuterClass.Command> cmds,
            String commandId,
            List<CommandsOuterClass.DisclosedContract> disclosedContracts
    ) {
        var ctx = tracingCtx(logger, "Submitting commands",
                "commands.count", cmds.size(),
                "commandId", commandId,
                "applicationId", APP_ID
        );
        return trace(ctx, () -> {
            CommandsOuterClass.Commands.Builder commandsBuilder = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(commandId)
                    .addActAs(appProviderParty)
                    .addReadAs(appProviderParty)
                    .addAllCommands(cmds);

            if (disclosedContracts != null && !disclosedContracts.isEmpty()) {
                commandsBuilder.addAllDisclosedContracts(disclosedContracts);
            }

            CommandSubmissionServiceOuterClass.SubmitRequest request =
                    CommandSubmissionServiceOuterClass.SubmitRequest.newBuilder()
                            .setCommands(commandsBuilder.build())
                            .build();

            return toCompletableFuture(submission.submit(request));
        });
    }


    /**
     * Query active contracts from the Ledger API (authoritative, no PQS lag)
     * Returns all active contracts of the specified template type for the app provider party
     */
    @WithSpan
    public <T extends Template> CompletableFuture<List<ActiveContract<T>>> getActiveContracts(Class<T> clazz) {
        Identifier templateId = Utils.getTemplateIdByClass(clazz);
        var ctx = tracingCtx(logger, "Getting active contracts",
                "templateId", templateId.toString(),
                "party", appProviderParty
        );
        return trace(ctx, () -> {
            // First, get the current ledger end offset
            CompletableFuture<List<ActiveContract<T>>> resultFuture = new CompletableFuture<>();

            stateService.getLedgerEnd(
                StateServiceOuterClass.GetLedgerEndRequest.newBuilder().build(),
                new io.grpc.stub.StreamObserver<StateServiceOuterClass.GetLedgerEndResponse>() {
                    @Override
                    public void onNext(StateServiceOuterClass.GetLedgerEndResponse response) {
                        long ledgerEndOffset = response.getOffset();
                        logger.debug("Current ledger end offset: {}", ledgerEndOffset);

                        // Build ACS request with template filter at current ledger end
                        StateServiceOuterClass.GetActiveContractsRequest request =
                                StateServiceOuterClass.GetActiveContractsRequest.newBuilder()
                                        .setFilter(TransactionFilterOuterClass.TransactionFilter.newBuilder()
                                                .putFiltersByParty(appProviderParty,
                                                    TransactionFilterOuterClass.Filters.newBuilder()
                                                        .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                                            .setTemplateFilter(TransactionFilterOuterClass.TemplateFilter.newBuilder()
                                                                .setTemplateId(toIdentifier(templateId))
                                                                .setIncludeCreatedEventBlob(false)
                                                                .build())
                                                            .build())
                                                        .build())
                                                .build())
                                        .setVerbose(false)
                                        .setActiveAtOffset(ledgerEndOffset)  // Use current ledger end
                                        .build();

                        // Call StateService.GetActiveContracts (streaming API)
                        List<ActiveContract<T>> contracts = new ArrayList<>();

                        stateService.getActiveContracts(request, new io.grpc.stub.StreamObserver<StateServiceOuterClass.GetActiveContractsResponse>() {
                @Override
                public void onNext(StateServiceOuterClass.GetActiveContractsResponse response) {
                    // Each response contains a single active contract
                    if (response.hasActiveContract()) {
                        StateServiceOuterClass.ActiveContract activeContract = response.getActiveContract();
                        String contractId = activeContract.getCreatedEvent().getContractId();
                        ValueOuterClass.Value payloadValue = ValueOuterClass.Value.newBuilder()
                                .setRecord(activeContract.getCreatedEvent().getCreateArguments())
                                .build();

                        @SuppressWarnings("unchecked")
                        T payload = (T) proto2Dto.template(templateId).convert(payloadValue);

                        contracts.add(new ActiveContract<>(new ContractId<>(contractId), payload));
                    }
                }

                            @Override
                            public void onError(Throwable t) {
                                logger.error("Error fetching active contracts for {}: {}", templateId, t.getMessage());
                                resultFuture.completeExceptionally(t);
                            }

                            @Override
                            public void onCompleted() {
                                logger.info("Fetched {} active contracts for {}", contracts.size(), templateId.qualifiedName());
                                resultFuture.complete(contracts);
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable t) {
                        logger.error("Error getting ledger end: {}", t.getMessage());
                        resultFuture.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                        // GetLedgerEnd completes after onNext
                    }
                });

            return resultFuture;
        });
    }

    /**
     * Check if a contract is active by querying all active contracts of that type
     * Returns true if active, false if archived or not found
     *
     * This is a simple implementation that queries all contracts of the type and checks if the CID exists.
     * For large contract sets, consider implementing a more efficient lookup.
     */
    @WithSpan
    public <T extends Template> CompletableFuture<Boolean> isContractActive(ContractId<T> contractId, Class<T> clazz) {
        return getActiveContracts(clazz)
                .thenApply(contracts -> contracts.stream()
                        .anyMatch(c -> c.contractId.getContractId.equals(contractId.getContractId)))
                .exceptionally(ex -> {
                    logger.warn("Error checking contract status for {}: {}", contractId.getContractId, ex.getMessage());
                    return false;  // Treat errors as "not active"
                });
    }

    /**
     * Validate and get active contract with precondition check
     * Returns the contract if it exists and meets the precondition, otherwise throws
     *
     * @param contractId The contract ID to validate
     * @param clazz The template class
     * @param precondition Optional validation function (return true if valid)
     * @param errorMessage Error message if contract not found or precondition fails
     * @throws IllegalStateException if contract not found or precondition fails
     */
    @WithSpan
    public <T extends Template> CompletableFuture<ActiveContract<T>> validateContract(
            ContractId<T> contractId,
            Class<T> clazz,
            java.util.function.Predicate<T> precondition,
            String errorMessage
    ) {
        return getActiveContracts(clazz)
                .thenApply(contracts -> {
                    Optional<ActiveContract<T>> maybeContract = contracts.stream()
                            .filter(c -> c.contractId.getContractId.equals(contractId.getContractId))
                            .findFirst();

                    if (maybeContract.isEmpty()) {
                        logger.error("Contract not found: {} ({})", contractId.getContractId, errorMessage);
                        throw new IllegalStateException("CONTRACT_NOT_FOUND: " + errorMessage);
                    }

                    ActiveContract<T> contract = maybeContract.get();
                    if (precondition != null && !precondition.test(contract.payload)) {
                        logger.error("Contract precondition failed: {} ({})", contractId.getContractId, errorMessage);
                        throw new IllegalStateException("PRECONDITION_FAILED: " + errorMessage);
                    }

                    logger.info("✅ Contract validated: {}", contractId.getContractId);
                    return contract;
                });
    }

    /**
     * Validate multiple contracts at once (more efficient - single ACS query per template type)
     * Returns map of contractId -> ActiveContract for all valid contracts
     *
     * @param contractIds List of contract IDs to validate
     * @param clazz The template class
     * @throws IllegalStateException if any contract not found
     */
    @WithSpan
    public <T extends Template> CompletableFuture<Map<String, ActiveContract<T>>> validateContracts(
            List<ContractId<T>> contractIds,
            Class<T> clazz
    ) {
        return getActiveContracts(clazz)
                .thenApply(contracts -> {
                    Map<String, ActiveContract<T>> validContracts = new HashMap<>();
                    Set<String> requestedIds = contractIds.stream()
                            .map(cid -> cid.getContractId)
                            .collect(java.util.stream.Collectors.toSet());

                    for (ActiveContract<T> contract : contracts) {
                        if (requestedIds.contains(contract.contractId.getContractId)) {
                            validContracts.put(contract.contractId.getContractId, contract);
                        }
                    }

                    // Check all requested contracts were found
                    for (String requestedId : requestedIds) {
                        if (!validContracts.containsKey(requestedId)) {
                            logger.error("Contract not found: {}", requestedId);
                            throw new IllegalStateException("CONTRACT_NOT_FOUND: " + requestedId);
                        }
                    }

                    logger.info("✅ Validated {} contracts", validContracts.size());
                    return validContracts;
                });
    }

    /**
     * Create a contract and deterministically retrieve its ContractId from the transaction tree.
     * This avoids the create-then-query race condition by using the command completion + transaction tree.
     *
     * @param template The contract template to create
     * @param actAsParties List of parties that will create the contract
     * @param readAsParties List of parties that can read (for visibility)
     * @param commandId Unique command ID for idempotency and traceability
     * @param templateId The template identifier (for filtering created events)
     * @return The ContractId of the newly created contract
     */
    @WithSpan
    public <T extends Template> CompletableFuture<ContractId<T>> createAndGetCid(
            T template,
            List<String> actAsParties,
            List<String> readAsParties,
            String commandId,
            Identifier templateId
    ) {
        var ctx = tracingCtx(logger, "Creating contract and getting CID",
                "commandId", commandId,
                "templateId", templateId.toString(),
                "actAsParties", String.join(",", actAsParties),
                "applicationId", APP_ID
        );
        return trace(ctx, () -> {
            // Build create command
            CommandsOuterClass.Command.Builder command = CommandsOuterClass.Command.newBuilder();
            ValueOuterClass.Value payload = dto2Proto.template(template.templateId()).convert(template);
            command.getCreateBuilder()
                    .setTemplateId(toIdentifier(template.templateId()))
                    .setCreateArguments(payload.getRecord());

            // Build Commands with parties
            CommandsOuterClass.Commands.Builder commandsBuilder = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(commandId)
                    .addAllActAs(actAsParties)
                    .addAllReadAs(readAsParties)
                    .addCommands(command.build());

            // Submit and wait for transaction tree
            CommandServiceOuterClass.SubmitAndWaitRequest request =
                    CommandServiceOuterClass.SubmitAndWaitRequest.newBuilder()
                            .setCommands(commandsBuilder.build())
                            .build();

            logger.info("Submitting create command: commandId={}, templateId={}, actAs={}",
                    commandId, templateId, String.join(",", actAsParties));

            return toCompletableFuture(commands.submitAndWaitForTransactionTree(request))
                    .thenApply(response -> {
                        TransactionOuterClass.TransactionTree txTree = response.getTransaction();
                        String txId = txTree.getUpdateId();  // Use updateId instead of transactionId
                        long offset = txTree.getOffset();

                        logger.info("Transaction completed: updateId={}, offset={}, commandId={}", txId, offset, commandId);

                        // Extract all events from the transaction tree
                        Map<Integer, TransactionOuterClass.TreeEvent> eventsById = txTree.getEventsByIdMap();
                        ValueOuterClass.Identifier targetTemplateId = toIdentifier(templateId);

                        // Debug logging (can be enabled via logger level if needed)
                        logger.debug("Transaction tree has {} events for commandId={}", eventsById.size(), commandId);

                        // Find the created event matching module + entity name (package-id independent)
                        String targetModule = templateId.moduleName();
                        String targetEntity = templateId.entityName();

                        Optional<String> createdContractId = eventsById.values().stream()
                                .filter(TransactionOuterClass.TreeEvent::hasCreated)
                                .map(TransactionOuterClass.TreeEvent::getCreated)
                                .filter(created ->
                                        created.getTemplateId().getModuleName().equals(targetModule) &&
                                        created.getTemplateId().getEntityName().equals(targetEntity))
                                .map(created -> created.getContractId())
                                .findFirst();

                        if (createdContractId.isEmpty()) {
                            logger.error("No CreatedEvent found for {}:{} in transaction updateId={}",
                                    targetModule, targetEntity, txId);
                            throw new IllegalStateException(
                                    "Contract creation failed: No CreatedEvent found for " + targetModule + ":" + targetEntity);
                        }

                        String contractId = createdContractId.get();
                        logger.info("✅ Contract created: cid={}, updateId={}, commandId={}",
                                contractId, txId, commandId);

                        return new ContractId<T>(contractId);
                    })
                    .exceptionally(ex -> {
                        logger.error("Create and get CID failed: commandId={}, error={}",
                                commandId, ex.getMessage(), ex);
                        throw new RuntimeException("Failed to create contract: " + ex.getMessage(), ex);
                    });
        });
    }

    /**
     * Simple wrapper for active contract data from Ledger API
     */
    public static class ActiveContract<T extends Template> {
        public final ContractId<T> contractId;
        public final T payload;

        public ActiveContract(ContractId<T> contractId, T payload) {
            this.contractId = contractId;
            this.payload = payload;
        }
    }

    private static <T> CompletableFuture<T> toCompletableFuture(ListenableFuture<T> listenableFuture) {
        CompletableFuture<T> completableFuture = new CompletableFuture<>();
        Futures.addCallback(listenableFuture, new FutureCallback<>() {
            @Override
            public void onSuccess(T result) {
                completableFuture.complete(result);
            }

            @Override
            public void onFailure(@Nonnull Throwable t) {
                completableFuture.completeExceptionally(t);
            }
        }, MoreExecutors.directExecutor());
        return completableFuture;
    }

    private static ValueOuterClass.Identifier toIdentifier(Identifier id) {
        return ValueOuterClass.Identifier.newBuilder()
                .setPackageId(id.packageNameAsPackageId())
                .setModuleName(id.moduleName())
                .setEntityName(id.entityName())
                .build();
    }


    private static class Interceptor implements ClientInterceptor {
        private final Metadata.Key<String> AUTHORIZATION_HEADER = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
        private final TokenProvider tokenProvider;

        public Interceptor(TokenProvider tokenProvider) {
            this.tokenProvider = tokenProvider;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            ClientCall<ReqT, RespT> clientCall = next.newCall(method, callOptions);
            return new ForwardingClientCall.SimpleForwardingClientCall<>(clientCall) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    headers.put(AUTHORIZATION_HEADER, "Bearer " + tokenProvider.getToken());
                    super.start(responseListener, headers);
                }
            };
        }
    }
}
