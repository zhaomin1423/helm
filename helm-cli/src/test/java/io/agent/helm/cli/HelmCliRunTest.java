package io.agent.helm.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** End-to-end test: runs the {@code helm run} subcommand in-process and checks stdout + exit code. */
final class HelmCliRunTest {
    @Test
    void runWorkflowPrintsResult() {
        Integer exit = null;
        String output;
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            exit = new CommandLine(new HelmCommand()).execute(new String[] {
                "run", "upper", "--app", TestHelmApp.class.getName(), "--input", "{\"text\":\"hi\"}"
            });
        } finally {
            System.setOut(original);
        }
        output = captured.toString();

        assertThat(exit).isZero();
        assertThat(output).contains("HI");
    }

    @Test
    void runUnknownWorkflowFailsWithStructuredError() {
        Integer exit = new CommandLine(new HelmCommand())
                .execute(new String[] {"run", "unknown", "--app", TestHelmApp.class.getName(), "--input", "{}"});

        assertThat(exit).isEqualTo(1);
    }
}
