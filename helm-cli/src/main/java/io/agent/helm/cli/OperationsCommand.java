package io.agent.helm.cli;

import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "operations", description = "List operations for a session as JSON.")
final class OperationsCommand extends HelmSubcommand {
    @Parameters(index = "0", description = "Session id.")
    String sessionId;

    @Override
    public Integer call() {
        try {
            HelmApp app = loadApp();
            var ops = app.agentRuntime().listOperations().stream()
                    .filter(op -> sessionId.equals(op.sessionId()))
                    .toList();
            System.out.println(toJson(Map.of("operations", ops)));
            return 0;
        } catch (Throwable e) {
            System.err.println(toJson(HelmApps.errorBody(e)));
            return 1;
        }
    }
}
