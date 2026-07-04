package io.agent.helm.cli;

import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "runs", description = "List workflow runs for a workflow as JSON.")
final class RunsCommand extends HelmSubcommand {
    @Parameters(index = "0", description = "Workflow name.")
    String workflow;

    @Override
    public Integer call() {
        try {
            HelmApp app = loadApp();
            var runs = app.workflowRuntime().listRuns().stream()
                    .filter(r -> workflow.equals(r.workflowName()))
                    .toList();
            System.out.println(toJson(Map.of("runs", runs)));
            return 0;
        } catch (Throwable e) {
            System.err.println(toJson(HelmApps.errorBody(e)));
            return 1;
        }
    }
}
