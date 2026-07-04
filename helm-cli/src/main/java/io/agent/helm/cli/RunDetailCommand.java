package io.agent.helm.cli;

import io.agent.helm.core.error.HelmException;
import io.agent.helm.core.error.ValidationException;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "run-detail", description = "Show a workflow run record as JSON.")
final class RunDetailCommand extends HelmSubcommand {
    @Parameters(index = "0", description = "Run id.")
    String runId;

    @Override
    public Integer call() {
        try {
            HelmApp app = loadApp();
            var run = app.workflowRuntime().getRun(runId);
            if (run.isEmpty()) {
                throw new ValidationException("workflow run not found", Map.of("runId", runId), Map.of());
            }
            System.out.println(toJson(run.get()));
            return 0;
        } catch (HelmException e) {
            System.err.println(toJson(HelmApps.errorBody(e)));
            return 1;
        } catch (Throwable e) {
            System.err.println(toJson(HelmApps.errorBody(e)));
            return 1;
        }
    }
}
