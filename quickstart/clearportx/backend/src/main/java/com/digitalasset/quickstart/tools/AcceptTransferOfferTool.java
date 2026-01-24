package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.CommandServiceGrpc;
import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.TransactionOuterClass;
import com.daml.ledger.api.v2.UpdateServiceGrpc;
import com.daml.ledger.api.v2.UpdateServiceOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import io.grpc.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Local CLI tool: fetch transaction by updateId, find a TransferOffer-like created event,
 * and exercise Accept as the given party.
 *
 * Usage:
 *   ./gradlew backend:run --args="accept-transfer-offer --party <PARTY> --updateId <UPDATE_ID> [--host <HOST>] [--port <PORT>] [--token <TOKEN>] [--verbose]"
 *
 * No HTTP endpoints are added; this is for operators running locally.
 */
public final class AcceptTransferOfferTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: accept-transfer-offer --party <PARTY> --updateId <UPDATE_ID> [--host <HOST>] [--port <PORT>] [--token <TOKEN>] [--verbose]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(cli.host, cli.port)
                .usePlaintext();
        if (cli.grpcAuthority != null && !cli.grpcAuthority.isBlank()) {
            builder = builder.overrideAuthority(cli.grpcAuthority);
        }
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
            UpdateServiceGrpc.UpdateServiceFutureStub updates = UpdateServiceGrpc.newFutureStub(channel);
            CommandServiceGrpc.CommandServiceFutureStub commands = CommandServiceGrpc.newFutureStub(channel);

            TransactionOuterClass.Transaction txn = fetchUpdate(updates, cli.updateId, cli.party);
            if (txn == null) {
                System.err.println("No transaction/update visible for updateId=" + cli.updateId + " as party=" + cli.party);
                System.exit(2);
            }

            List<CreatedInfo> candidates = extractCandidates(txn, cli.verbose);
            if (candidates.isEmpty()) {
                System.err.println("No TransferOffer-like created events found in updateId=" + cli.updateId);
                System.exit(3);
            }
            CreatedInfo selected = pickBest(candidates);

            System.out.println("Selected contractId=" + selected.contractId);
            System.out.println("TemplateId=" + selected.templateId.getModuleName() + ":" + selected.templateId.getEntityName());
            if (selected.instrumentId != null) {
                System.out.println("InstrumentId=" + selected.instrumentId);
            }

            CommandsOuterClass.ExerciseCommand exercise = CommandsOuterClass.ExerciseCommand.newBuilder()
                    .setTemplateId(selected.templateId)
                    .setContractId(selected.contractId)
                    .setChoice("Accept")
                    .setChoiceArgument(ValueOuterClass.Value.newBuilder()
                            .setRecord(ValueOuterClass.Record.newBuilder().build())
                            .build())
                    .build();

            CommandsOuterClass.Commands cmds = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(UUID.randomUUID().toString())
                    .setApplicationId(cli.applicationId)
                    .addActAs(cli.party)
                    .addReadAs(cli.party)
                    .addCommands(CommandsOuterClass.Command.newBuilder().setExercise(exercise).build())
                    .build();

            CommandServiceOuterClass.SubmitAndWaitForTransactionRequest req =
                    CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                            .setCommands(cmds)
                            .setTransactionFormat(com.daml.ledger.api.v2.TransactionFilterOuterClass.TransactionFormat.newBuilder()
                                    .setTransactionShape(com.daml.ledger.api.v2.TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                                    .build())
                            .build();

            var resp = commands.submitAndWaitForTransaction(req).get();
            String updateId = resp.hasTransaction() ? resp.getTransaction().getUpdateId() : "(no updateId)";
            System.out.println("Accept submitted. UpdateId=" + updateId);
        } finally {
            channel.shutdownNow();
        }
    }

    private static TransactionOuterClass.Transaction fetchUpdate(UpdateServiceGrpc.UpdateServiceFutureStub updates,
                                                                 String updateId,
                                                                 String party) throws Exception {
        UpdateServiceOuterClass.GetUpdateByIdRequest req = UpdateServiceOuterClass.GetUpdateByIdRequest.newBuilder()
                .setUpdateId(updateId)
                .setRequestingParties(ValueOuterClass.Parties.newBuilder().addParties(party).build())
                .build();
        var resp = updates.getUpdateById(req).get();
        if (resp.hasUpdate() && resp.getUpdate().hasTransaction()) {
            return resp.getUpdate().getTransaction();
        }
        return null;
    }

    private static List<CreatedInfo> extractCandidates(TransactionOuterClass.Transaction txn, boolean verbose) {
        List<CreatedInfo> out = new ArrayList<>();
        for (TransactionOuterClass.Event ev : txn.getEventsList()) {
            if (!ev.hasCreated()) continue;
            EventOuterClass.CreatedEvent created = ev.getCreated();
            ValueOuterClass.Identifier tid = created.getTemplateId();
            String mod = tid.getModuleName().toLowerCase(Locale.ROOT);
            String ent = tid.getEntityName().toLowerCase(Locale.ROOT);
            if (mod.contains("transfer") || ent.contains("transfer")) {
                String instrumentId = extractInstrumentId(created.getCreateArguments());
                if (verbose) {
                    System.out.println("Candidate: cid=" + created.getContractId()
                            + " tmpl=" + tid.getModuleName() + ":" + tid.getEntityName()
                            + " instrument=" + instrumentId);
                }
                out.add(new CreatedInfo(created.getContractId(), tid, instrumentId));
            }
        }
        return out;
    }

    private static CreatedInfo pickBest(List<CreatedInfo> candidates) {
        return candidates.stream()
                .sorted((a, b) -> {
                    boolean ah = a.instrumentId != null && !a.instrumentId.isBlank();
                    boolean bh = b.instrumentId != null && !b.instrumentId.isBlank();
                    if (ah == bh) return 0;
                    return ah ? -1 : 1;
                })
                .findFirst()
                .orElse(candidates.get(0));
    }

    private static String extractInstrumentId(ValueOuterClass.Record rec) {
        if (rec == null) return null;
        ValueOuterClass.Value v = getField(rec, "instrumentId", 4);
        if (v != null && v.hasRecord()) {
            ValueOuterClass.Record r = v.getRecord();
            ValueOuterClass.Value id = getField(r, "id", 1);
            if (id != null && id.hasText()) {
                return id.getText();
            }
        }
        for (var f : rec.getFieldsList()) {
            if (f.getValue().hasText()) {
                String lbl = f.getLabel().toLowerCase(Locale.ROOT);
                if (lbl.contains("instrument") || lbl.equals("id")) {
                    return f.getValue().getText();
                }
            }
        }
        return null;
    }

    private static ValueOuterClass.Value getField(ValueOuterClass.Record rec, String label, int index) {
        if (rec == null) return null;
        for (ValueOuterClass.RecordField f : rec.getFieldsList()) {
            if (label.equals(f.getLabel())) {
                return f.getValue();
            }
        }
        if (rec.getFieldsCount() > index) {
            return rec.getFields(index).getValue();
        }
        return null;
    }

    private record CreatedInfo(String contractId, ValueOuterClass.Identifier templateId, String instrumentId) {}

    private record Cli(String party, String updateId, String host, int port, String token, boolean verbose, String grpcAuthority, String applicationId) {
        static Cli parse(String[] args) {
            String party = null;
            String updateId = null;
            String host = System.getenv().getOrDefault("LEDGER_HOST", "localhost");
            int port = Integer.parseInt(System.getenv().getOrDefault("LEDGER_PORT", "5001"));
            String token = System.getenv().getOrDefault("LEDGER_API_TOKEN", "");
            String grpcAuthority = System.getenv().getOrDefault("LEDGER_GRPC_AUTHORITY", "");
            String appId = System.getenv().getOrDefault("LEDGER_APP_ID", "quickstart-backend");
            boolean verbose = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--party" -> party = (i + 1 < args.length) ? args[++i] : null;
                    case "--updateId" -> updateId = (i + 1 < args.length) ? args[++i] : null;
                    case "--host" -> host = (i + 1 < args.length) ? args[++i] : host;
                    case "--port" -> {
                        if (i + 1 < args.length) {
                            port = Integer.parseInt(args[++i]);
                        }
                    }
                    case "--token" -> token = (i + 1 < args.length) ? args[++i] : token;
                    case "--grpcAuthority" -> grpcAuthority = (i + 1 < args.length) ? args[++i] : grpcAuthority;
                    case "--verbose" -> verbose = true;
                    default -> { }
                }
            }
            if (party == null || updateId == null) {
                return null;
            }
            return new Cli(party, updateId, host, port, token, verbose, grpcAuthority, appId);
        }
    }

    private static class AuthInterceptor implements ClientInterceptor {
        private final Metadata.Key<String> AUTH_HEADER = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
        private final String token;
        AuthInterceptor(String token) {
            this.token = token == null ? "" : token;
        }
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
            return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    if (!token.isEmpty()) {
                        headers.put(AUTH_HEADER, "Bearer " + token);
                    }
                    super.start(responseListener, headers);
                }
            };
        }
    }
}
package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.CommandServiceGrpc;
import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.TransactionOuterClass;
import com.daml.ledger.api.v2.UpdateServiceGrpc;
import com.daml.ledger.api.v2.UpdateServiceOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import io.grpc.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Local CLI tool: fetch transaction by updateId, find a TransferOffer-like created event,
 * and exercise Accept as the given party.
 *
 * Usage:
 *   ./gradlew backend:run --args="accept-transfer-offer --party <PARTY> --updateId <UPDATE_ID> [--host <HOST>] [--port <PORT>] [--token <TOKEN>] [--verbose]"
 *
 * No HTTP endpoints are added; this is for operators running locally.
 */
public final class AcceptTransferOfferTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: accept-transfer-offer --party <PARTY> --updateId <UPDATE_ID> [--host <HOST>] [--port <PORT>] [--token <TOKEN>] [--verbose]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(cli.host, cli.port)
                .usePlaintext();
        if (cli.grpcAuthority != null && !cli.grpcAuthority.isBlank()) {
            builder = builder.overrideAuthority(cli.grpcAuthority);
        }
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
            UpdateServiceGrpc.UpdateServiceFutureStub updates = UpdateServiceGrpc.newFutureStub(channel);
            CommandServiceGrpc.CommandServiceFutureStub commands = CommandServiceGrpc.newFutureStub(channel);

            TransactionOuterClass.Transaction txn = fetchUpdate(updates, cli.updateId, cli.party);
            if (txn == null) {
                System.err.println("No transaction/update visible for updateId=" + cli.updateId + " as party=" + cli.party);
                System.exit(2);
            }

            List<CreatedInfo> candidates = extractCandidates(txn, cli.verbose);
            if (candidates.isEmpty()) {
                System.err.println("No TransferOffer-like created events found in updateId=" + cli.updateId);
                System.exit(3);
            }
            CreatedInfo selected = pickBest(candidates);

            System.out.println("Selected contractId=" + selected.contractId);
            System.out.println("TemplateId=" + selected.templateId.getModuleName() + ":" + selected.templateId.getEntityName());
            if (selected.instrumentId != null) {
                System.out.println("InstrumentId=" + selected.instrumentId);
            }

            // Build exercise command (Accept with unit/empty record)
            CommandsOuterClass.ExerciseCommand exercise = CommandsOuterClass.ExerciseCommand.newBuilder()
                    .setTemplateId(selected.templateId)
                    .setContractId(selected.contractId)
                    .setChoice("Accept")
                    .setChoiceArgument(ValueOuterClass.Value.newBuilder()
                            .setRecord(ValueOuterClass.Record.newBuilder().build())
                            .build())
                    .build();

            CommandsOuterClass.Commands cmds = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(UUID.randomUUID().toString())
                    .setApplicationId(cli.applicationId)
                    .addActAs(cli.party)
                    .addReadAs(cli.party)
                    .addCommands(CommandsOuterClass.Command.newBuilder().setExercise(exercise).build())
                    .build();

            CommandServiceOuterClass.SubmitAndWaitForTransactionRequest req =
                    CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                            .setCommands(cmds)
                            .setTransactionFormat(com.daml.ledger.api.v2.TransactionFilterOuterClass.TransactionFormat.newBuilder()
                                    .setTransactionShape(com.daml.ledger.api.v2.TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                                    .build())
                            .build();

            var resp = commands.submitAndWaitForTransaction(req).get();
            String updateId = resp.hasTransaction() ? resp.getTransaction().getUpdateId() : "(no updateId)";
            System.out.println("Accept submitted. UpdateId=" + updateId);
        } finally {
            channel.shutdownNow();
        }
    }

    private static TransactionOuterClass.Transaction fetchUpdate(UpdateServiceGrpc.UpdateServiceFutureStub updates,
                                                                 String updateId,
                                                                 String party) throws Exception {
        UpdateServiceOuterClass.GetUpdateByIdRequest req = UpdateServiceOuterClass.GetUpdateByIdRequest.newBuilder()
                .setUpdateId(updateId)
                .setRequestingParties(ValueOuterClass.Parties.newBuilder().addParties(party).build())
                .build();
        var resp = updates.getUpdateById(req).get();
        if (resp.hasUpdate() && resp.getUpdate().hasTransaction()) {
            return resp.getUpdate().getTransaction();
        }
        return null;
    }

    private static List<CreatedInfo> extractCandidates(TransactionOuterClass.Transaction txn, boolean verbose) {
        List<CreatedInfo> out = new ArrayList<>();
        for (TransactionOuterClass.Event ev : txn.getEventsList()) {
            if (!ev.hasCreated()) continue;
            EventOuterClass.CreatedEvent created = ev.getCreated();
            ValueOuterClass.Identifier tid = created.getTemplateId();
            String mod = tid.getModuleName().toLowerCase(Locale.ROOT);
            String ent = tid.getEntityName().toLowerCase(Locale.ROOT);
            if (mod.contains("transfer") || ent.contains("transfer")) {
                String instrumentId = extractInstrumentId(created.getCreateArguments());
                if (verbose) {
                    System.out.println("Candidate: cid=" + created.getContractId()
                            + " tmpl=" + tid.getModuleName() + ":" + tid.getEntityName()
                            + " instrument=" + instrumentId);
                }
                out.add(new CreatedInfo(created.getContractId(), tid, instrumentId));
            }
        }
        return out;
    }

    private static CreatedInfo pickBest(List<CreatedInfo> candidates) {
        return candidates.stream()
                .sorted((a, b) -> {
                    boolean ah = a.instrumentId != null && !a.instrumentId.isBlank();
                    boolean bh = b.instrumentId != null && !b.instrumentId.isBlank();
                    if (ah == bh) return 0;
                    return ah ? -1 : 1;
                })
                .findFirst()
                .orElse(candidates.get(0));
    }

    private static String extractInstrumentId(ValueOuterClass.Record rec) {
        if (rec == null) return null;
        ValueOuterClass.Value v = getField(rec, "instrumentId", 4);
        if (v != null && v.hasRecord()) {
            ValueOuterClass.Record r = v.getRecord();
            ValueOuterClass.Value id = getField(r, "id", 1);
            if (id != null && id.hasText()) {
                return id.getText();
            }
        }
        for (var f : rec.getFieldsList()) {
            if (f.getValue().hasText()) {
                String lbl = f.getLabel().toLowerCase(Locale.ROOT);
                if (lbl.contains("instrument") || lbl.equals("id")) {
                    return f.getValue().getText();
                }
            }
        }
        return null;
    }

    private static ValueOuterClass.Value getField(ValueOuterClass.Record rec, String label, int index) {
        if (rec == null) return null;
        for (ValueOuterClass.RecordField f : rec.getFieldsList()) {
            if (label.equals(f.getLabel())) {
                return f.getValue();
            }
        }
        if (rec.getFieldsCount() > index) {
            return rec.getFields(index).getValue();
        }
        return null;
    }

    private record CreatedInfo(String contractId, ValueOuterClass.Identifier templateId, String instrumentId) {}

    private record Cli(String party, String updateId, String host, int port, String token, boolean verbose, String grpcAuthority, String applicationId) {
        static Cli parse(String[] args) {
            String party = null;
            String updateId = null;
            String host = System.getenv().getOrDefault("LEDGER_HOST", "localhost");
            int port = Integer.parseInt(System.getenv().getOrDefault("LEDGER_PORT", "5001"));
            String token = System.getenv().getOrDefault("LEDGER_API_TOKEN", "");
            String grpcAuthority = System.getenv().getOrDefault("LEDGER_GRPC_AUTHORITY", "");
            String appId = System.getenv().getOrDefault("LEDGER_APP_ID", "quickstart-backend");
            boolean verbose = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--party" -> party = (i + 1 < args.length) ? args[++i] : null;
                    case "--updateId" -> updateId = (i + 1 < args.length) ? args[++i] : null;
                    case "--host" -> host = (i + 1 < args.length) ? args[++i] : host;
                    case "--port" -> {
                        if (i + 1 < args.length) {
                            port = Integer.parseInt(args[++i]);
                        }
                    }
                    case "--token" -> token = (i + 1 < args.length) ? args[++i] : token;
                    case "--grpcAuthority" -> grpcAuthority = (i + 1 < args.length) ? args[++i] : grpcAuthority;
                    case "--verbose" -> verbose = true;
                    default -> { }
                }
            }
            if (party == null || updateId == null) {
                return null;
            }
            return new Cli(party, updateId, host, port, token, verbose, grpcAuthority, appId);
        }
    }

    private static class AuthInterceptor implements ClientInterceptor {
        private final Metadata.Key<String> AUTH_HEADER = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
        private final String token;
        AuthInterceptor(String token) {
            this.token = token == null ? "" : token;
        }
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
            return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    if (!token.isEmpty()) {
                        headers.put(AUTH_HEADER, "Bearer " + token);
                    }
                    super.start(responseListener, headers);
                }
            };
        }
    }
}
package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.TransactionOuterClass;
import com.daml.ledger.api.v2.UpdateServiceGrpc;
import com.daml.ledger.api.v2.UpdateServiceOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.security.TokenProvider;
import com.google.protobuf.Empty;
import com.google.protobuf.util.JsonFormat;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Local CLI to accept a pending CBTC transfer offer by updateId.
 *
 * Usage:
 *   ./gradlew backend:run --args="accept-transfer-offer --party <PARTY> --updateId <UPDATE_ID> [--verbose]"
 *
 * This tool:
 * 1) Fetches the transaction/update by updateId for the provided party (using UpdateService).
 * 2) Heuristically finds a TransferOffer-like created event (template module/entity containing "TransferOffer").
 * 3) Exercises choice "Accept" on that contract as the given party (Unit/empty record argument).
 * 4) Prints the resulting update/transaction id on success.
 *
 * No HTTP endpoints are added; this is for local operator use only.
 */
public class AcceptTransferOfferTool {

    public static void main(String[] args) throws Exception {
        CliArgs cli = CliArgs.parse(args);
        if (cli == null) {
            System.err.println("Usage: accept-transfer-offer --party <PARTY> --updateId <UPDATE_ID> [--verbose]");
            System.exit(1);
        }

        LedgerConfig ledgerConfig = LedgerConfig.load();
        TokenProvider tokenProvider = TokenProvider.local(ledgerConfig);
        AuthUtils authUtils = new AuthUtils(ledgerConfig);

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(ledgerConfig.getHost(), ledgerConfig.getPort())
                .usePlaintext()
                .build();

        try {
            UpdateServiceGrpc.UpdateServiceFutureStub updates =
                    UpdateServiceGrpc.newFutureStub(channel)
                            .withCallCredentials(tokenProvider.asCallCredentials());

            TransactionOuterClass.Transaction txn = fetchUpdate(updates, cli.updateId(), cli.party());
            if (txn == null) {
                System.err.println("No transaction/update found for updateId=" + cli.updateId());
                System.exit(2);
            }

            Optional<CreatedInfo> candidate = findTransferOffer(txn, cli.verbose());
            if (candidate.isEmpty()) {
                System.err.println("No TransferOffer-like contract found in updateId=" + cli.updateId());
                System.exit(3);
            }

            CreatedInfo offer = candidate.get();
            System.out.println("Selected contractId=" + offer.contractId);
            System.out.println("TemplateId=" + offer.templateId.toString());
            if (offer.instrumentId != null) {
                System.out.println("InstrumentId=" + offer.instrumentId);
            }

            LedgerApi ledgerApi = new LedgerApi(ledgerConfig, Optional.of(tokenProvider), authUtils);
            ValueOuterClass.Record emptyArgs = ValueOuterClass.Record.newBuilder().build();

            var resp = ledgerApi.exerciseRaw(
                    offer.templateId,
                    offer.contractId,
                    "Accept",
                    emptyArgs,
                    List.of(cli.party()),
                    List.of(cli.party()),
                    List.of()
            ).join();

            String resultingUpdate = resp.hasTransaction()
                    ? resp.getTransaction().getUpdateId()
                    : "(no updateId in response)";
            System.out.println("Accept submitted. UpdateId=" + resultingUpdate);

        } finally {
            channel.shutdownNow();
        }
    }

    private record CliArgs(String party, String updateId, boolean verbose) {
        static CliArgs parse(String[] args) {
            String party = null;
            String updateId = null;
            boolean verbose = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--party" -> party = (i + 1 < args.length) ? args[++i] : null;
                    case "--updateId" -> updateId = (i + 1 < args.length) ? args[++i] : null;
                    case "--verbose" -> verbose = true;
                    default -> { /* ignore */ }
                }
            }
            if (party == null || updateId == null) {
                return null;
            }
            return new CliArgs(party, updateId, verbose);
        }
    }

    private record CreatedInfo(String contractId,
                               ValueOuterClass.Identifier templateId,
                               String instrumentId) { }

    private static TransactionOuterClass.Transaction fetchUpdate(
            UpdateServiceGrpc.UpdateServiceFutureStub updates,
            String updateId,
            String requestingParty
    ) throws Exception {
        UpdateServiceOuterClass.GetUpdateByIdRequest req = UpdateServiceOuterClass.GetUpdateByIdRequest.newBuilder()
                .setUpdateId(updateId)
                .setRequestingParties(com.daml.ledger.api.v2.ValueOuterClass.Parties.newBuilder()
                        .addParties(requestingParty)
                        .build())
                .build();
        var resp = updates.getUpdateById(req).get();
        if (resp.hasUpdate()) {
            if (resp.getUpdate().hasTransaction()) {
                return resp.getUpdate().getTransaction();
            }
            if (resp.getUpdate().hasTransactionTree()) {
                // Flatten the tree into Transaction; fall back to raw tree JSON if needed.
                String json = JsonFormat.printer().print(resp.getUpdate().getTransactionTree());
                System.out.println("Received transactionTree (not flat). Dumping JSON snippet:");
                System.out.println(json.substring(0, Math.min(json.length(), 2000)));
            }
        }
        return null;
    }

    private static Optional<CreatedInfo> findTransferOffer(TransactionOuterClass.Transaction txn, boolean verbose) {
        List<CreatedInfo> candidates = new ArrayList<>();
        for (TransactionOuterClass.Event event : txn.getEventsList()) {
            if (!event.hasCreated()) continue;
            var created = event.getCreated();
            ValueOuterClass.Identifier tmpl = created.getTemplateId();
            String mod = tmpl.getModuleName().toLowerCase(Locale.ROOT);
            String ent = tmpl.getEntityName().toLowerCase(Locale.ROOT);
            if (mod.contains("transfer") || ent.contains("transfer")) {
                String instrumentId = extractInstrumentId(created.getCreateArguments());
                if (verbose) {
                    System.out.println("Candidate: cid=" + created.getContractId()
                            + " template=" + tmpl.getModuleName() + ":" + tmpl.getEntityName()
                            + " instrumentId=" + instrumentId);
                }
                candidates.add(new CreatedInfo(created.getContractId(), tmpl, instrumentId));
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        // Prefer ones with instrumentId present, otherwise first.
        return candidates.stream()
                .sorted((a, b) -> {
                    boolean aHas = a.instrumentId != null && !a.instrumentId.isBlank();
                    boolean bHas = b.instrumentId != null && !b.instrumentId.isBlank();
                    if (aHas == bHas) return 0;
                    return aHas ? -1 : 1;
                })
                .findFirst();
    }

    private static String extractInstrumentId(ValueOuterClass.Record args) {
        if (args == null) return null;
        ValueOuterClass.Value instrVal = getField(args, "instrumentId", 4);
        if (instrVal != null && instrVal.hasRecord()) {
            ValueOuterClass.Record rec = instrVal.getRecord();
            ValueOuterClass.Value idV = getField(rec, "id", 1);
            if (idV != null && idV.hasText()) {
                return idV.getText();
            }
        }
        // heuristic: search any text field named "instrument" or "id"
        for (var f : args.getFieldsList()) {
            if (f.getValue().hasText()) {
                String lbl = f.getLabel().toLowerCase(Locale.ROOT);
                if (lbl.contains("instrument") || lbl.equals("id")) {
                    return f.getValue().getText();
                }
            }
        }
        return null;
    }

    private static ValueOuterClass.Value getField(final ValueOuterClass.Record record, final String label, final int index) {
        if (record == null) {
            return null;
        }
        for (ValueOuterClass.RecordField field : record.getFieldsList()) {
            if (label.equals(field.getLabel())) {
                return field.getValue();
            }
        }
        if (record.getFieldsCount() > index) {
            return record.getFields(index).getValue();
        }
        return null;
    }
}

