package io.agent.helm.examples.springboot;

import com.fasterxml.jackson.databind.JsonNode;
import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.core.type.TypeDescriptor;
import io.agent.helm.core.workflow.WorkflowConfig;
import io.agent.helm.core.workflow.WorkflowContext;
import io.agent.helm.core.workflow.WorkflowDefinition;
import java.util.concurrent.Flow;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Minimal Helm Spring Boot application: declares an agent, a workflow, and a deterministic provider as beans. The
 * {@code helm-spring-boot-starter} auto-configures the runtime and (because {@code helm.http.enabled=true}) mounts the
 * HTTP routes. Run with {@code mvn spring-boot:run} or build and run the jar.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public AgentDefinition echoAgent() {
        return new EchoAgent();
    }

    @Bean
    public WorkflowDefinition<?, ?> upperWorkflow() {
        return new UpperWorkflow();
    }

    @Bean
    public ModelProvider provider() {
        return new ConstantFakeProvider("fake");
    }

    public static final class EchoAgent implements AgentDefinition {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public AgentConfig configure(AgentContext context) {
            return AgentConfig.builder()
                    .model("fake/test")
                    .instructions("Echo.")
                    .build();
        }
    }

    public static final class UpperWorkflow implements WorkflowDefinition<Object, String> {
        @Override
        public String name() {
            return "upper";
        }

        @Override
        public WorkflowConfig config() {
            return WorkflowConfig.of(new EchoAgent());
        }

        @Override
        public TypeDescriptor<Object> inputType() {
            return new TypeDescriptor<>() {};
        }

        @Override
        public TypeDescriptor<String> outputType() {
            return TypeDescriptor.of(String.class);
        }

        @Override
        public String run(WorkflowContext<Object> context) {
            Object input = context.input();
            if (input instanceof JsonNode node) {
                return node.path("text").asText("").toUpperCase();
            }
            return String.valueOf(input).toUpperCase();
        }
    }

    public static final class ConstantFakeProvider implements ModelProvider {
        private final String providerId;

        public ConstantFakeProvider(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public boolean supports(ModelRef model) {
            return providerId.equals(model.providerId());
        }

        @Override
        public Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public synchronized void request(long n) {
                    if (done || n <= 0) {
                        return;
                    }
                    done = true;
                    subscriber.onNext(new ModelStreamEvent.ContentDelta("ok"));
                    subscriber.onNext(new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    done = true;
                }
            });
        }
    }
}
