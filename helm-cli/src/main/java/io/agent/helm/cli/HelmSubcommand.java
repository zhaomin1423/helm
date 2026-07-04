package io.agent.helm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Callable;
import picocli.CommandLine.Option;

/** Base for CLI subcommands: carries the {@code --app} option and shared JSON helpers. */
abstract class HelmSubcommand implements Callable<Integer> {
    protected static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Option(names = "--app", required = true, description = "FQCN of a HelmApp assembly class.")
    String appClass;

    protected HelmApp loadApp() {
        return HelmApps.load(appClass);
    }

    protected String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":{\"code\":\"INTERNAL_ERROR\"}}";
        }
    }
}
