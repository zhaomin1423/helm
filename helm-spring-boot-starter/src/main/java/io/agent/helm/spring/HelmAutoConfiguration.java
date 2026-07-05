package io.agent.helm.spring;

import io.agent.helm.core.admission.RateLimiter;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.memory.MemoryStore;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.security.HelmAuthorizer;
import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.workflow.WorkflowDefinition;
import io.agent.helm.http.core.HelmHttpRouter;
import io.agent.helm.http.core.HelmHttpRoutes;
import io.agent.helm.http.core.SecurityContextExtractor;
import io.agent.helm.http.servlet.HelmHttpServlet;
import io.agent.helm.runtime.AgentRuntime;
import io.agent.helm.runtime.InMemoryRuntimeStore;
import io.agent.helm.runtime.WorkflowRuntime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures Helm runtime beans from application beans and optionally mounts the HTTP routes. Wires optional
 * {@link MemoryStore}, {@link HelmAuthorizer}, {@link RateLimiter}, and {@code maxSessionMessages} when the application
 * supplies them. The starter only assembles; core Helm modules stay Spring-free.
 */
@AutoConfiguration
@EnableConfigurationProperties(HelmProperties.class)
public class HelmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RuntimeStore.class)
    public RuntimeStore helmRuntimeStore() {
        return new InMemoryRuntimeStore();
    }

    @Bean
    public AgentRuntime helmAgentRuntime(
            List<AgentDefinition> agents,
            List<ModelProvider> providers,
            RuntimeStore store,
            Optional<MemoryStore> memoryStore,
            Optional<HelmAuthorizer> authorizer,
            Optional<RateLimiter> rateLimiter,
            HelmProperties properties) {
        validateUniqueNames(agents, AgentDefinition::name, "agent");
        AgentRuntime.Builder builder = AgentRuntime.builder().store(store);
        agents.forEach(builder::agent);
        providers.forEach(builder::provider);
        memoryStore.ifPresent(builder::memoryStore);
        authorizer.ifPresent(builder::authorizer);
        rateLimiter.ifPresent(builder::rateLimiter);
        if (properties.getMaxSessionMessages() > 0) {
            builder.maxSessionMessages(properties.getMaxSessionMessages());
        }
        return builder.build();
    }

    @Bean
    public WorkflowRuntime helmWorkflowRuntime(
            List<WorkflowDefinition<?, ?>> workflows, List<ModelProvider> providers, RuntimeStore store) {
        validateUniqueNames(workflows, WorkflowDefinition::name, "workflow");
        WorkflowRuntime.Builder builder = WorkflowRuntime.builder().store(store);
        workflows.forEach(builder::workflow);
        providers.forEach(builder::provider);
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "helm.http", name = "enabled", havingValue = "true")
    public ServletRegistrationBean<HelmHttpServlet> helmHttpServlet(
            AgentRuntime agentRuntime,
            WorkflowRuntime workflowRuntime,
            HelmProperties properties,
            Optional<HelmAuthorizer> authorizer) {
        SecurityContextExtractor extractor = authorizer.isPresent() ? SecurityContextExtractor.header() : null;
        HelmHttpRouter router =
                HelmHttpRoutes.router(agentRuntime, workflowRuntime, authorizer.orElse(null), extractor);
        HelmHttpServlet servlet = new HelmHttpServlet(router);
        String prefix = properties.getHttp().getRoutePrefix();
        String mapping = (prefix == null || prefix.isBlank() ? "" : prefix) + "/*";
        return new ServletRegistrationBean<>(servlet, mapping);
    }

    private static <T> void validateUniqueNames(List<T> items, Function<T, String> nameFunction, String kind) {
        Set<String> seen = new HashSet<>();
        for (T item : items) {
            String name = nameFunction.apply(item);
            if (name != null && !seen.add(name)) {
                throw new IllegalStateException("duplicate " + kind + " name: " + name);
            }
        }
    }
}
