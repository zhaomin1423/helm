package io.agent.helm.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Entry point for the {@code helm} CLI. */
public final class HelmCli {
    private HelmCli() {}

    public static void main(String[] args) {
        int exitCode = new CommandLine(new HelmCommand()).execute(args);
        System.exit(exitCode);
    }
}

@Command(
        name = "helm",
        mixinStandardHelpOptions = true,
        version = "helm 0.1.0-SNAPSHOT",
        subcommands = {
            RunCommand.class,
            DevCommand.class,
            OperationsCommand.class,
            RunsCommand.class,
            RunDetailCommand.class,
        },
        description = "Helm Agent Harness CLI.")
class HelmCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("helm: use 'helm --help' to see available commands.");
    }
}
