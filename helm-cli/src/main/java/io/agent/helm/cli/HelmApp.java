package io.agent.helm.cli;

import io.agent.helm.runtime.AgentRuntime;
import io.agent.helm.runtime.WorkflowRuntime;

/**
 * User-provided assembly entry point. An application supplies a concrete class implementing this interface with a
 * no-argument constructor and points the CLI at it via {@code --app <FQCN>}. The CLI reflectively instantiates it to
 * obtain the configured runtimes; no classpath scanning is performed.
 */
public interface HelmApp {
    AgentRuntime agentRuntime();

    WorkflowRuntime workflowRuntime();
}
