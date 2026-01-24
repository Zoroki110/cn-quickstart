package com.digitalasset.quickstart.tools;

/**
 * Convenience wrapper for exercising TransferInstruction_Accept via interface.
 *
 * Usage:
 *   ./gradlew backend:run --args="accept-transfer-instruction --host localhost --port 5001 --party <PARTY> --contractId <CID> [--interfacePackageId <PKG>] [--token <JWT>] [--verbose]"
 *
 * Defaults:
 *   interfaceModule = Splice.Api.Token.TransferInstructionV1
 *   interfaceEntity = TransferInstruction
 *   choice = TransferInstruction_Accept
 */
public final class AcceptTransferInstructionTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: accept-transfer-instruction --party <PARTY> --contractId <CID> [--host <HOST>] [--port <PORT>] [--token <JWT>] [--interfacePackageId <PKG>] [--verbose]");
            System.exit(1);
        }

        String[] forwarded = buildForwardArgs(cli);
        ExerciseByInterfaceTool.runCli(forwarded);
    }

    private static String[] buildForwardArgs(Cli cli) {
        String pkg = cli.interfacePackageId != null ? cli.interfacePackageId : cli.defaultPackageId;
        java.util.List<String> list = new java.util.ArrayList<>();
        list.add("exercise-by-interface");
        list.add("--host"); list.add(cli.host);
        list.add("--port"); list.add(Integer.toString(cli.port));
        list.add("--party"); list.add(cli.party);
        list.add("--contractId"); list.add(cli.contractId);
        list.add("--interfacePackageId"); list.add(pkg);
        list.add("--interfaceModule"); list.add("Splice.Api.Token.TransferInstructionV1");
        list.add("--interfaceEntity"); list.add("TransferInstruction");
        list.add("--choice"); list.add("TransferInstruction_Accept");
        list.add("--token"); list.add(cli.token);
        if (cli.verbose) list.add("--verbose");
        return list.toArray(new String[0]);
    }

    private record Cli(String host, int port, String party, String contractId, String interfacePackageId,
                       String defaultPackageId, String token, boolean verbose) {
        static Cli parse(String[] args) {
            if (args.length == 0 || !"accept-transfer-instruction".equals(args[0])) {
                return null;
            }
            String host = "localhost";
            int port = 5001;
            String party = null;
            String contractId = null;
            String interfacePackageId = null;
            String defaultPackageId = null;
            String token = "";
            boolean verbose = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = next(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(next(args, ++i, "--port"));
                    case "--party" -> party = next(args, ++i, "--party");
                    case "--contractId" -> contractId = next(args, ++i, "--contractId");
                    case "--interfacePackageId" -> interfacePackageId = next(args, ++i, "--interfacePackageId");
                    case "--defaultPackageId" -> defaultPackageId = next(args, ++i, "--defaultPackageId");
                    case "--token" -> token = next(args, ++i, "--token");
                    case "--verbose" -> verbose = true;
                    default -> {
                        System.err.println("Unknown arg: " + args[i]);
                        return null;
                    }
                }
            }
            if (party == null || contractId == null) return null;
            return new Cli(host, port, party, contractId, interfacePackageId, defaultPackageId, token, verbose);
        }

        private static String next(String[] args, int idx, String flag) {
            if (idx >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
            return args[idx];
        }
    }
}

