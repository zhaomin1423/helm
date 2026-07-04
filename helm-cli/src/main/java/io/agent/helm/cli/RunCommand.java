package io.agent.helm.cli;

import io.agent.helm.runtime.WorkflowInvokeRequest;
import io.agent.helm.runtime.WorkflowRunHandle;
import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "run", description = "Invoke a workflow locally and print the result as JSON.")
final class RunCommand extends HelmSubcommand {
    @Parameters(index = "0", description = "Workflow name.")
    String workflow;

    @Option(names = "--input", defaultValue = "{}", description = "Workflow input JSON.")
    String input;

    @Override
    public Integer call() {
        try {
            HelmApp app = loadApp();
            Object inputObj = MAPPER.readTree(input);
            @SuppressWarnings({"rawtypes", "unchecked"})
            WorkflowRunHandle<?> handle = app.workflowRuntime().invoke(new WorkflowInvokeRequest(workflow, inputObj));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("runId", handle.runId());
            body.put("result", handle.result());
            System.out.println(toJson(body));
            return 0;
        } catch (Throwable e) {
            System.err.println(toJson(HelmApps.errorBody(e)));
            return 1;
        }
    }
}
