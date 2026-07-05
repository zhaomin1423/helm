package io.agent.helm.spring;

import io.agent.helm.core.admission.RateLimiter;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.error.ValidationException;
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
import java.util.Map;
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
 *
 * <p>Runtime beans ({@code AgentRuntime}/{@code WorkflowRuntime}) are declared with {@link ConditionalOnMissingBean} so
 * applications can override them. The auto-configured {@link AgentRuntime} bean is registered with {@code destroyMethod
 * = "close"} so Spring invokes {@code close()} on shutdown, stopping the internal LeaseManager scheduler.
 *
 * <p>By default, no {@link SecurityContextExtractor} is auto-wired: trusting the spoofable {@code X-Helm-Principal}
 * header is unsafe. Applications must provide their own {@link SecurityContextExtractor} bean (e.g. validating a signed
 * JWT). The dev-only {@link SecurityContextExtractor#header()} convenience is wired only when
 * {@code helm.http.dev-header-auth=true}; it MUST NOT be enabled in production.
 */
@AutoConfiguration
@EnableConfigurationProperties(HelmProperties.class)
public class HelmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RuntimeStore.class)
    public RuntimeStore helmRuntimeStore() {
        return new InMemoryRuntimeStore();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AgentRuntime.class)
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
    @ConditionalOnMissingBean(WorkflowRuntime.class)
    public WorkflowRuntime helmWorkflowRuntime(
            List<WorkflowDefinition<?, ?>> workflows, List<ModelProvider> providers, RuntimeStore store) {
        validateUniqueNames(workflows, WorkflowDefinition::name, "workflow");
        WorkflowRuntime.Builder builder = WorkflowRuntime.builder().store(store);
        workflows.forEach(builder::workflow);
        providers.forEach(builder::provider);
        return builder.build();
    }

    /**
     * Dev-only: wires {@link SecurityContextExtractor#header()} which trusts {@code X-Helm-Principal}. Never use in
     * production — any client can impersonate any principal by setting the header.
     */
    @Bean
    @ConditionalOnProperty(prefix = "helm.http", name = "dev-header-auth", havingValue = "true")
    @ConditionalOnMissingBean(SecurityContextExtractor.class)
    public SecurityContextExtractor helmDevHeaderSecurityContextExtractor() {
        return SecurityContextExtractor.header();
    }

    @Bean
    @ConditionalOnProperty(prefix = "helm.http", name = "enabled", havingValue = "true")
    public ServletRegistrationBean<HelmHttpServlet> helmHttpServlet(
            AgentRuntime agentRuntime,
            WorkflowRuntime workflowRuntime,
            HelmProperties properties,
            Optional<HelmAuthorizer> authorizer,
            Optional<SecurityContextExtractor> extractor) {
        HelmHttpRouter router =
                HelmHttpRoutes.router(agentRuntime, workflowRuntime, authorizer.orElse(null), extractor.orElse(null));
        int maxBodyBytes = properties.getHttp().getMaxBodyBytes() > 0
                ? properties.getHttp().getMaxBodyBytes()
                : HelmHttpServlet.DEFAULT_MAX_BODY_BYTES;
        HelmHttpServlet servlet = new HelmHttpServlet(router, maxBodyBytes);
        String mapping = normalizeRoutePrefix(properties.getHttp().getRoutePrefix()) + "/*";
        ServletRegistrationBean<HelmHttpServlet> registration = new ServletRegistrationBean<>(servlet, mapping);
        registration.setLoadOnStartup(1);
        return registration;
    }

    /** Normalizes {@code routePrefix}: requires a leading {@code /}, rejects {@code *}, strips trailing {@code /}. */
    static String normalizeRoutePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String trimmed = prefix.trim();
        if (trimmed.indexOf('*') >= 0) {
            throw new ValidationException(
                    "invalid routePrefix: must not contain '*'", Map.of("routePrefix", trimmed), Map.of());
        }
        if (!trimmed.startsWith("/")) {
            throw new ValidationException(
                    "invalid routePrefix: must start with '/'", Map.of("routePrefix", trimmed), Map.of());
        }
        if (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
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
