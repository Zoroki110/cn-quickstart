package com.digitalasset.quickstart.controller;

import com.daml.ledger.api.v2.CommandServiceGrpc;
import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.security.TokenProvider;
import io.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * DevNet-only helper to verify TransferInstruction visibility + acceptance.
 *
 * POST /api/devnet/transfer-instruction/visibility { contractId }
 *   -> { visible: true/false }
 *
 * POST /api/devnet/transfer-instruction/accept { contractId }
 *   -> 409 if not visible
 *   -> { visible:true, accepted:true, updateId, holdingCid }
 *   -> on failure { visible:true, accepted:false, error }
 */
@RestController
@RequestMapping("/api/devnet/transfer-instruction")
@Profile("devnet")
public class DevNetTransferInstructionController {

    private static final Logger logger = LoggerFactory.getLogger(DevNetTransferInstructionController.class);

    private final LedgerConfig ledgerConfig;
    private final TokenProvider tokenProvider;
    private final AuthUtils authUtils;

    public DevNetTransferInstructionController(LedgerConfig ledgerConfig, TokenProvider tokenProvider, AuthUtils authUtils) {
        this.ledgerConfig = ledgerConfig;
        this.tokenProvider = tokenProvider;
        this.authUtils = authUtils;
    }

    public record AcceptRequest(String contractId) { }
    public record VisibilityResponse(
            String operatorParty,
            String contractId,
            boolean visible
    ) { }

    public record AcceptResponse(
            String operatorParty,
            String contractId,
            boolean visible,
            boolean accepted,
            String updateId,
            String holdingCid,
            String error
    ) { }

    @PostMapping("/visibility")
    public ResponseEntity<VisibilityResponse> visibility(@RequestBody AcceptRequest request) {
        String contractId = request.contractId();
        String operatorParty = authUtils.getAppProviderPartyId();

        if (contractId == null || contractId.isBlank()) {
            return ResponseEntity.badRequest().body(new VisibilityResponse(operatorParty, contractId, false));
        }

        ManagedChannel channel = null;
        try {
            ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                    .forAddress(ledgerConfig.getHost(), ledgerConfig.getPort())
                    .usePlaintext();
            if (ledgerConfig.getGrpcAuthority() != null && !ledgerConfig.getGrpcAuthority().isBlank()) {
                builder = builder.overrideAuthority(ledgerConfig.getGrpcAuthority());
            }
            builder.intercept(new AuthInterceptor(tokenProvider.getToken()));
            channel = builder.build();

            boolean visible = isVisible(channel, operatorParty, contractId);
            logger.info("TI visibility check: operator={} contractId={} visible={}", operatorParty, contractId, visible);
            return ResponseEntity.ok(new VisibilityResponse(operatorParty, contractId, visible));
        } finally {
            if (channel != null) channel.shutdownNow();
        }
    }

    @PostMapping("/accept")
    public ResponseEntity<AcceptResponse> accept(@RequestBody AcceptRequest request) {
        String contractId = request.contractId();
        String operatorParty = authUtils.getAppProviderPartyId();

        if (contractId == null || contractId.isBlank()) {
            return ResponseEntity.badRequest().body(new AcceptResponse(operatorParty, contractId, false, false, null, null, "contractId is required"));
        }

        ManagedChannel channel = null;
        try {
            ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                    .forAddress(ledgerConfig.getHost(), ledgerConfig.getPort())
                    .usePlaintext();
            if (ledgerConfig.getGrpcAuthority() != null && !ledgerConfig.getGrpcAuthority().isBlank()) {
                builder = builder.overrideAuthority(ledgerConfig.getGrpcAuthority());
            }
            builder.intercept(new AuthInterceptor(tokenProvider.getToken()));
            channel = builder.build();

            StateServiceGrpc.StateServiceBlockingStub state = StateServiceGrpc.newBlockingStub(channel);
            ValueOuterClass.Identifier templateId = findTemplateId(state, operatorParty, contractId);
            boolean visible = templateId != null;
            logger.info("TI visibility check: operator={} contractId={} visible={}", operatorParty, contractId, visible);
            if (!visible) {
                return ResponseEntity.status(409).body(new AcceptResponse(operatorParty, contractId, false, false, null, null, "TransferInstruction not visible to operator"));
            }

            CommandServiceGrpc.CommandServiceBlockingStub cmd = CommandServiceGrpc.newBlockingStub(channel);
            CommandsOuterClass.ExerciseCommand exercise = CommandsOuterClass.ExerciseCommand.newBuilder()
                    .setTemplateId(templateId)
                    .setContractId(contractId)
                    .setChoice("TransferInstruction_Accept")
                    .setChoiceArgument(ValueOuterClass.Value.newBuilder()
                            .setRecord(ValueOuterClass.Record.newBuilder().build())
                            .build())
                    .build();

            CommandsOuterClass.Commands commands = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId("accept-ti-" + UUID.randomUUID())
                    .setUserId(operatorParty)
                    .addActAs(operatorParty)
                    .addReadAs(operatorParty)
                    .addCommands(CommandsOuterClass.Command.newBuilder().setExercise(exercise).build())
                    .build();

            TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                    .putFiltersByParty(operatorParty, TransactionFilterOuterClass.Filters.newBuilder()
                            .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                    .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                    .build())
                            .build())
                    .build();
            TransactionFilterOuterClass.TransactionFormat txFormat = TransactionFilterOuterClass.TransactionFormat.newBuilder()
                    .setEventFormat(eventFormat)
                    .setTransactionShape(TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                    .build();

            CommandServiceOuterClass.SubmitAndWaitForTransactionRequest req =
                    CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                            .setCommands(commands)
                            .setTransactionFormat(txFormat)
                            .build();

            var resp = cmd.submitAndWaitForTransaction(req);
            String updateId = resp.hasTransaction() ? resp.getTransaction().getUpdateId() : null;
            String holdingCid = extractHoldingCid(resp);

            logger.info("TI accept submit: operator={} contractId={} updateId={} holdingCid={}", operatorParty, contractId, updateId, holdingCid);

            return ResponseEntity.ok(new AcceptResponse(operatorParty, contractId, true, true, updateId, holdingCid, null));
        } catch (Exception e) {
            logger.warn("TI accept failed: operator={} contractId={} error={}", authUtils.getAppProviderPartyId(), request.contractId(), e.getMessage());
            return ResponseEntity.ok(new AcceptResponse(operatorParty, request.contractId(), true, false, null, null, e.getMessage()));
        } finally {
            if (channel != null) channel.shutdownNow();
        }
    }

    private static ValueOuterClass.Identifier findTemplateId(StateServiceGrpc.StateServiceBlockingStub state, String party, String contractId) {
        TransactionFilterOuterClass.Filters wildcardFilters = TransactionFilterOuterClass.Filters.newBuilder()
                .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                        .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                        .build())
                .build();
        TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                .putFiltersByParty(party, wildcardFilters)
                .setVerbose(true)
                .build();
        StateServiceOuterClass.GetActiveContractsRequest acsReq = StateServiceOuterClass.GetActiveContractsRequest.newBuilder()
                .setEventFormat(eventFormat)
                .build();
        var it = state.getActiveContracts(acsReq);
        while (it.hasNext()) {
            var resp = it.next();
            if (!resp.hasActiveContract()) continue;
            var created = resp.getActiveContract().getCreatedEvent();
            if (contractId.equals(created.getContractId())) {
                return created.getTemplateId();
            }
        }
        return null;
    }

    private static boolean isVisible(ManagedChannel channel, String party, String contractId) {
        return findTemplateId(StateServiceGrpc.newBlockingStub(channel), party, contractId) != null;
    }

    private static String extractHoldingCid(CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp) {
        if (resp == null || !resp.hasTransaction()) return null;
        for (var event : resp.getTransaction().getEventsList()) {
            if (!event.hasCreated()) continue;
            var created = event.getCreated();
            if ("Holding".equalsIgnoreCase(created.getTemplateId().getEntityName())) {
                return created.getContractId();
            }
        }
        return null;
    }

    private static final class AuthInterceptor implements ClientInterceptor {
        private static final Metadata.Key<String> AUTHORIZATION_HEADER = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
        private final String token;

        AuthInterceptor(String token) {
            this.token = token;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            ClientCall<ReqT, RespT> clientCall = next.newCall(method, callOptions);
            return new ForwardingClientCall.SimpleForwardingClientCall<>(clientCall) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    if (token != null && !token.isBlank()) {
                        headers.put(AUTHORIZATION_HEADER, "Bearer " + token);
                    }
                    super.start(responseListener, headers);
                }
            };
        }
    }
}

