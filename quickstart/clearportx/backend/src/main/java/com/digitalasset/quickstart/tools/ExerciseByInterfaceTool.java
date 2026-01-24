package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.CommandServiceGrpc;
import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.google.protobuf.ByteString;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Generic interface exercise CLI.
 *
 * Usage example:
 *   ./gradlew backend:run --args="exercise-by-interface --host localhost --port 5001 --party <PARTY> --contractId <CID> --interfacePackageId <PKG> --interfaceModule <Module> --interfaceEntity <Entity> --choice <Choice> --verbose"
 */
public final class ExerciseByInterfaceTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: exercise-by-interface --party <PARTY> --contractId <CID> --interfacePackageId <PKG> --interfaceModule <Module> --interfaceEntity <Entity> --choice <Choice> [--host <HOST>] [--port <PORT>] [--token <JWT>] [--argJson <JSON>] [--disclosedContractsJson <PATH>] [--skipVisibility] [--verbose]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(cli.host, cli.port).usePlaintext();
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
            if (!cli.skipVisibility && !isVisible(channel, cli)) {
                System.err.println("Contract " + cli.contractId + " is not visible to party " + cli.party + " (ACS lookup failed). Use --skipVisibility to try anyway.");
                System.exit(2);
            }

            ValueOuterClass.Identifier interfaceId = ValueOuterClass.Identifier.newBuilder()
                    .setPackageId(cli.interfacePackageId)
                    .setModuleName(cli.interfaceModule)
                    .setEntityName(cli.interfaceEntity)
                    .build();

            ValueOuterClass.Value choiceArg = unitValue(); // default Unit; argJson optional not parsed here

            CommandsOuterClass.ExerciseCommand exercise = CommandsOuterClass.ExerciseCommand.newBuilder()
                    .setTemplateId(interfaceId) // interface id
                    .setContractId(cli.contractId)
                    .setChoice(cli.choice)
                    .setChoiceArgument(choiceArg)
                    .build();

            CommandsOuterClass.Commands.Builder cmds = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(UUID.randomUUID().toString())
                    .setUserId(cli.party)
                    .addActAs(cli.party)
                    .addReadAs(cli.party)
                    .addCommands(CommandsOuterClass.Command.newBuilder().setExercise(exercise).build());

            if (cli.disclosedContractsJson != null) {
                List<CommandsOuterClass.DisclosedContract> disclosed = loadDisclosed(cli.disclosedContractsJson);
                cmds.addAllDisclosedContracts(disclosed);
            }

            TransactionFilterOuterClass.EventFormat submitEventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                    .putFiltersByParty(cli.party, TransactionFilterOuterClass.Filters.newBuilder()
                            .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                    .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                    .build())
                            .build())
                    .build();
            TransactionFilterOuterClass.TransactionFormat txFormat = TransactionFilterOuterClass.TransactionFormat.newBuilder()
                    .setEventFormat(submitEventFormat)
                    .setTransactionShape(TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                    .build();

            CommandServiceOuterClass.SubmitAndWaitForTransactionRequest req =
                    CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                            .setCommands(cmds)
                            .setTransactionFormat(txFormat)
                            .build();

            CommandServiceGrpc.CommandServiceBlockingStub stub = CommandServiceGrpc.newBlockingStub(channel);
            try {
                var resp = stub.submitAndWaitForTransaction(req);
                String updateId = resp.hasTransaction() ? resp.getTransaction().getUpdateId() : "(no updateId)";
                System.out.println("SUCCESS: exercised interface choice " + cli.choice + " on " + cli.contractId + " updateId=" + updateId);
            } catch (StatusRuntimeException e) {
                System.err.println("FAILED exercising interface choice on " + cli.contractId);
                System.err.println("InterfaceId=" + fmtId(interfaceId) + " choice=" + cli.choice);
                System.err.println("gRPC status: " + e.getStatus());
                if (e.getStatus().getDescription() != null) {
                    System.err.println("description: " + e.getStatus().getDescription());
                }
                throw e;
            }
        } finally {
            channel.shutdownNow();
        }
    }

    private static boolean isVisible(ManagedChannel channel, Cli cli) {
        StateServiceGrpc.StateServiceBlockingStub state = StateServiceGrpc.newBlockingStub(channel);
        TransactionFilterOuterClass.Filters wildcardFilters = TransactionFilterOuterClass.Filters.newBuilder()
                .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                        .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                        .build())
                .build();
        TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                .putFiltersByParty(cli.party, wildcardFilters)
                .build();
        StateServiceOuterClass.GetActiveContractsRequest req = StateServiceOuterClass.GetActiveContractsRequest.newBuilder()
                .setEventFormat(eventFormat)
                .build();
        var it = state.getActiveContracts(req);
        while (it.hasNext()) {
            var resp = it.next();
            if (!resp.hasActiveContract()) continue;
            var created = resp.getActiveContract().getCreatedEvent();
            if (cli.contractId.equals(created.getContractId())) {
                return true;
            }
        }
        return false;
    }

    private static List<CommandsOuterClass.DisclosedContract> loadDisclosed(String path) {
        try {
            String json = Files.readString(Path.of(path));
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);
            List<CommandsOuterClass.DisclosedContract> res = new ArrayList<>();
            if (!node.isArray()) {
                System.err.println("disclosedContractsJson must be an array");
                return res;
            }
            for (var el : node) {
                String tmpl = el.path("templateId").asText(null);
                String cid = el.path("contractId").asText(null);
                String blobB64 = el.path("createdEventBlob").asText(null);
                if (cid == null || blobB64 == null) {
                    continue;
                }
                ValueOuterClass.Identifier tid = null;
                if (tmpl != null && tmpl.contains(":")) {
                    String[] parts = tmpl.split(":", 3);
                    if (parts.length == 3) {
                        tid = ValueOuterClass.Identifier.newBuilder()
                                .setPackageId(parts[0])
                                .setModuleName(parts[1])
                                .setEntityName(parts[2])
                                .build();
                    }
                }
                CommandsOuterClass.DisclosedContract.Builder b = CommandsOuterClass.DisclosedContract.newBuilder()
                        .setContractId(cid)
                        .setCreatedEventBlob(ByteString.copyFrom(java.util.Base64.getDecoder().decode(blobB64)));
                if (tid != null) b.setTemplateId(tid);
                res.add(b.build());
            }
            return res;
        } catch (Exception e) {
            System.err.println("Failed to read disclosedContractsJson: " + e.getMessage());
            return List.of();
        }
    }

    private static ValueOuterClass.Value unitValue() {
        return ValueOuterClass.Value.newBuilder()
                .setRecord(ValueOuterClass.Record.newBuilder().build())
                .build();
    }

    private static String fmtId(ValueOuterClass.Identifier id) {
        return id.getPackageId() + ":" + id.getModuleName() + ":" + id.getEntityName();
    }

    private record Cli(String host, int port, String party, String interfacePackageId, String interfaceModule,
                       String interfaceEntity, String contractId, String choice, String argJson,
                       String disclosedContractsJson, String token, boolean verbose, boolean skipVisibility) {
        static Cli parse(String[] args) {
            if (args.length == 0 || !"exercise-by-interface".equals(args[0])) {
                return null;
            }
            String host = "localhost";
            int port = 5001;
            String party = null;
            String contractId = null;
            String interfacePackageId = null;
            String interfaceModule = null;
            String interfaceEntity = null;
            String choice = null;
            String argJson = null;
            String disclosedContractsJson = null;
            String token = "";
            boolean verbose = false;
            boolean skipVisibility = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = next(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(next(args, ++i, "--port"));
                    case "--party" -> party = next(args, ++i, "--party");
                    case "--contractId" -> contractId = next(args, ++i, "--contractId");
                    case "--interfacePackageId" -> interfacePackageId = next(args, ++i, "--interfacePackageId");
                    case "--interfaceModule" -> interfaceModule = next(args, ++i, "--interfaceModule");
                    case "--interfaceEntity" -> interfaceEntity = next(args, ++i, "--interfaceEntity");
                    case "--choice" -> choice = next(args, ++i, "--choice");
                    case "--argJson" -> argJson = next(args, ++i, "--argJson");
                    case "--disclosedContractsJson" -> disclosedContractsJson = next(args, ++i, "--disclosedContractsJson");
                    case "--token" -> token = next(args, ++i, "--token");
                    case "--verbose" -> verbose = true;
                    case "--skipVisibility" -> skipVisibility = true;
                    default -> {
                        System.err.println("Unknown arg: " + args[i]);
                        return null;
                    }
                }
            }
            if (party == null || contractId == null || interfaceModule == null || interfaceEntity == null || interfacePackageId == null || choice == null) {
                return null;
            }
            return new Cli(host, port, party, interfacePackageId, interfaceModule, interfaceEntity, contractId, choice, argJson, disclosedContractsJson, token, verbose, skipVisibility);
        }

        private static String next(String[] args, int idx, String flag) {
            if (idx >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
            return args[idx];
        }
    }

    private static final class AuthInterceptor implements ClientInterceptor {
        private final Metadata.Key<String> AUTHORIZATION_HEADER = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
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
package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.CommandServiceGrpc;
import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.google.protobuf.ByteString;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Generic interface exercise CLI.
 *
 * Usage example:
 *   ./gradlew backend:run --args="exercise-by-interface --host localhost --port 5001 --party <PARTY> --contractId <CID> --interfacePackageId <PKG> --interfaceModule <Module> --interfaceEntity <Entity> --choice <Choice> --verbose"
 */
public final class ExerciseByInterfaceTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: exercise-by-interface --party <PARTY> --contractId <CID> --interfacePackageId <PKG> --interfaceModule <Module> --interfaceEntity <Entity> --choice <Choice> [--host <HOST>] [--port <PORT>] [--token <JWT>] [--argJson <JSON>] [--disclosedContractsJson <PATH>] [--verbose]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(cli.host, cli.port).usePlaintext();
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
            // Visibility check
            if (!cli.skipVisibility && !isVisible(channel, cli)) {
                System.err.println("Contract " + cli.contractId + " is not visible to party " + cli.party + " (ACS lookup failed). Use --skipVisibility to force submit.");
                System.exit(2);
            }

            ValueOuterClass.Identifier interfaceId = ValueOuterClass.Identifier.newBuilder()
                    .setPackageId(cli.interfacePackageId)
                    .setModuleName(cli.interfaceModule)
                    .setEntityName(cli.interfaceEntity)
                    .build();

            ValueOuterClass.Value choiceArg = cli.argJson == null ? unitValue() : unitValue(); // keep unit; argJson optional not parsed here

            CommandsOuterClass.ExerciseCommand exercise = CommandsOuterClass.ExerciseCommand.newBuilder()
                    .setTemplateId(interfaceId) // interface id
                    .setContractId(cli.contractId)
                    .setChoice(cli.choice)
                    .setChoiceArgument(choiceArg)
                    .build();

            CommandsOuterClass.Commands.Builder cmds = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(UUID.randomUUID().toString())
                    .setUserId(cli.party)
                    .addActAs(cli.party)
                    .addReadAs(cli.party)
                    .addCommands(CommandsOuterClass.Command.newBuilder().setExercise(exercise).build());

            if (cli.disclosedContractsJson != null) {
                List<CommandsOuterClass.DisclosedContract> disclosed = loadDisclosed(cli.disclosedContractsJson);
                cmds.addAllDisclosedContracts(disclosed);
            }

            TransactionFilterOuterClass.EventFormat submitEventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                    .putFiltersByParty(cli.party, TransactionFilterOuterClass.Filters.newBuilder()
                            .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                    .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                    .build())
                            .build())
                    .build();
            TransactionFilterOuterClass.TransactionFormat txFormat = TransactionFilterOuterClass.TransactionFormat.newBuilder()
                    .setEventFormat(submitEventFormat)
                    .setTransactionShape(TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                    .build();

            CommandServiceOuterClass.SubmitAndWaitForTransactionRequest req =
                    CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                            .setCommands(cmds)
                            .setTransactionFormat(txFormat)
                            .build();

            CommandServiceGrpc.CommandServiceBlockingStub stub = CommandServiceGrpc.newBlockingStub(channel);
            try {
                var resp = stub.submitAndWaitForTransaction(req);
                String updateId = resp.hasTransaction() ? resp.getTransaction().getUpdateId() : "(no updateId)";
                System.out.println("SUCCESS: exercised interface choice " + cli.choice + " on " + cli.contractId + " updateId=" + updateId);
            } catch (StatusRuntimeException e) {
                System.err.println("FAILED exercising interface choice on " + cli.contractId);
                System.err.println("InterfaceId=" + fmtId(interfaceId) + " choice=" + cli.choice);
                System.err.println("gRPC status: " + e.getStatus());
                if (e.getStatus().getDescription() != null) {
                    System.err.println("description: " + e.getStatus().getDescription());
                }
                throw e;
            }
        } finally {
            channel.shutdownNow();
        }
    }

    private static boolean isVisible(ManagedChannel channel, Cli cli) {
        StateServiceGrpc.StateServiceBlockingStub state = StateServiceGrpc.newBlockingStub(channel);
        TransactionFilterOuterClass.Filters wildcardFilters = TransactionFilterOuterClass.Filters.newBuilder()
                .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                        .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                        .build())
                .build();
        TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                .putFiltersByParty(cli.party, wildcardFilters)
                .build();
        StateServiceOuterClass.GetActiveContractsRequest req = StateServiceOuterClass.GetActiveContractsRequest.newBuilder()
                .setEventFormat(eventFormat)
                .build();
        var it = state.getActiveContracts(req);
        while (it.hasNext()) {
            var resp = it.next();
            if (!resp.hasActiveContract()) continue;
            var created = resp.getActiveContract().getCreatedEvent();
            if (cli.contractId.equals(created.getContractId())) {
                return true;
            }
        }
        return false;
    }

    private static List<CommandsOuterClass.DisclosedContract> loadDisclosed(String path) {
        try {
            String json = Files.readString(Path.of(path));
            // expected minimal shape: [{ "templateId": "pkg:Mod:Ent", "contractId": "cid", "createdEventBlob": "base64" }]
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);
            List<CommandsOuterClass.DisclosedContract> res = new ArrayList<>();
            if (!node.isArray()) {
                System.err.println("disclosedContractsJson must be an array");
                return res;
            }
            for (var el : node) {
                String tmpl = el.path("templateId").asText(null);
                String cid = el.path("contractId").asText(null);
                String blobB64 = el.path("createdEventBlob").asText(null);
                if (cid == null || blobB64 == null) {
                    continue;
                }
                ValueOuterClass.Identifier tid = null;
                if (tmpl != null && tmpl.contains(":")) {
                    String[] parts = tmpl.split(":", 3);
                    if (parts.length == 3) {
                        tid = ValueOuterClass.Identifier.newBuilder()
                                .setPackageId(parts[0])
                                .setModuleName(parts[1])
                                .setEntityName(parts[2])
                                .build();
                    }
                }
                CommandsOuterClass.DisclosedContract.Builder b = CommandsOuterClass.DisclosedContract.newBuilder()
                        .setContractId(cid)
                        .setCreatedEventBlob(ByteString.copyFrom(java.util.Base64.getDecoder().decode(blobB64)));
                if (tid != null) b.setTemplateId(tid);
                res.add(b.build());
            }
            return res;
        } catch (Exception e) {
            System.err.println("Failed to read disclosedContractsJson: " + e.getMessage());
            return List.of();
        }
    }

    private static ValueOuterClass.Value unitValue() {
        return ValueOuterClass.Value.newBuilder()
                .setRecord(ValueOuterClass.Record.newBuilder().build())
                .build();
    }

    private static String fmtId(ValueOuterClass.Identifier id) {
        return id.getPackageId() + ":" + id.getModuleName() + ":" + id.getEntityName();
    }

    private record Cli(String host, int port, String party, String interfacePackageId, String interfaceModule,
                       String interfaceEntity, String contractId, String choice, String argJson,
                       String disclosedContractsJson, String token, boolean verbose, boolean skipVisibility) {
        static Cli parse(String[] args) {
            if (args.length == 0 || !"exercise-by-interface".equals(args[0])) {
                return null;
            }
            String host = "localhost";
            int port = 5001;
            String party = null;
            String contractId = null;
            String interfacePackageId = null;
            String interfaceModule = null;
            String interfaceEntity = null;
            String choice = null;
            String argJson = null;
            String disclosedContractsJson = null;
            String token = "";
            boolean verbose = false;
            boolean skipVisibility = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = next(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(next(args, ++i, "--port"));
                    case "--party" -> party = next(args, ++i, "--party");
                    case "--contractId" -> contractId = next(args, ++i, "--contractId");
                    case "--interfacePackageId" -> interfacePackageId = next(args, ++i, "--interfacePackageId");
                    case "--interfaceModule" -> interfaceModule = next(args, ++i, "--interfaceModule");
                    case "--interfaceEntity" -> interfaceEntity = next(args, ++i, "--interfaceEntity");
                    case "--choice" -> choice = next(args, ++i, "--choice");
                    case "--argJson" -> argJson = next(args, ++i, "--argJson");
                    case "--disclosedContractsJson" -> disclosedContractsJson = next(args, ++i, "--disclosedContractsJson");
                    case "--token" -> token = next(args, ++i, "--token");
                    case "--verbose" -> verbose = true;
                    case "--skipVisibility" -> skipVisibility = true;
                    default -> {
                        System.err.println("Unknown arg: " + args[i]);
                        return null;
                    }
                }
            }
            if (party == null || contractId == null || interfaceModule == null || interfaceEntity == null || interfacePackageId == null || choice == null) {
                return null;
            }
            return new Cli(host, port, party, interfacePackageId, interfaceModule, interfaceEntity, contractId, choice, argJson, disclosedContractsJson, token, verbose, skipVisibility);
        }

        private static String next(String[] args, int idx, String flag) {
            if (idx >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
            return args[idx];
        }
    }

    private static final class AuthInterceptor implements ClientInterceptor {
        private final Metadata.Key<String> AUTHORIZATION_HEADER = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
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

