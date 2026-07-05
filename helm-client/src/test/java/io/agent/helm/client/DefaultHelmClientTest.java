package io.agent.helm.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.agent.helm.core.agent.PromptStreamEvent;
import io.agent.helm.runtime.WorkflowRunHandle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Integration tests for {@link HelmClient} covering: resource lifecycle (close), unique headers, typed
 * {@code invokeWorkflow}, path-segment encoding, null-text coercion, and incremental SSE streaming.
 */
final class DefaultHelmClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @RegisterExtension
    WireMockExtension wireMock = WireMockExtension.newInstance().build();

    // —— HIGH 1: close() releases resources ——

    @Test
    void closeIsIdempotentAndDoesNotThrow() {
        HelmClient client = HelmClient.builder().baseUrl(wireMock.baseUrl()).build();
        client.close();
        // second close must not throw
        client.close();
    }

    @Test
    void helmClientIsAutoCloseable() {
        HelmClient client = HelmClient.builder().baseUrl(wireMock.baseUrl()).build();
        assertThat(client).isInstanceOf(AutoCloseable.class);
        client.close();
    }

    // —— HIGH 2: exactly one Accept / Content-Type header ——

    @Test
    void requestHasExactlyOneAcceptAndContentTypeHeader() throws Exception {
        // Use com.sun.net.httpserver to capture raw headers (WireMock collapses duplicates into one value).
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        java.util.concurrent.atomic.AtomicReference<List<String>> capturedAccept =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<List<String>> capturedContentType =
                new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/agents/echo/instances/i1/sessions/s1/prompt", exchange -> {
            // Headers.get() is case-insensitive; capture values immediately (Headers is mutable per-exchange).
            capturedAccept.set(
                    new java.util.ArrayList<>(exchange.getRequestHeaders().getOrDefault("Accept", List.of())));
            capturedContentType.set(
                    new java.util.ArrayList<>(exchange.getRequestHeaders().getOrDefault("Content-Type", List.of())));
            String body = "{\"operationId\":\"op_1\",\"text\":\"hi\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length());
            try (var os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });
        server.start();
        try (HelmClient client = HelmClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .build()) {
            client.prompt("echo", "i1", "s1", "hello");
            // Exactly one value for Accept and Content-Type (no duplicates from the builder + injector).
            assertThat(capturedAccept.get()).as("Accept header values").hasSize(1);
            assertThat(capturedContentType.get())
                    .as("Content-Type header values")
                    .hasSize(1);
        } finally {
            server.stop(0);
        }
    }

    // —— HIGH 4: typed invokeWorkflow ——

    @Test
    void invokeWorkflowDeserializesTypedOutputViaClass() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/workflows/echo/invoke"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"runId\":\"run_1\",\"result\":{\"name\":\"Alice\",\"age\":30}}")));

        try (HelmClient client =
                HelmClient.builder().baseUrl(wireMock.baseUrl()).build()) {
            WorkflowRunHandle<Person> handle = client.invokeWorkflow("echo", Map.of("x", 1), Person.class);
            assertThat(handle.runId()).isEqualTo("run_1");
            assertThat(handle.result()).isNotNull();
            assertThat(handle.result().name).isEqualTo("Alice");
            assertThat(handle.result().age).isEqualTo(30);
        }
    }

    @Test
    void invokeWorkflowDeserializesGenericTypeViaTypeReference() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/workflows/list/invoke"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"runId\":\"run_2\",\"result\":[\"a\",\"b\",\"c\"]}")));

        try (HelmClient client =
                HelmClient.builder().baseUrl(wireMock.baseUrl()).build()) {
            WorkflowRunHandle<List<String>> handle =
                    client.invokeWorkflow("list", Map.of(), new TypeReference<List<String>>() {});
            assertThat(handle.runId()).isEqualTo("run_2");
            assertThat(handle.result()).containsExactly("a", "b", "c");
        }
    }

    @Test
    void invokeWorkflowRejectsUntypedCall() {
        // The old untyped invokeWorkflow(workflow, input) is gone — callers must supply a Class or TypeReference.
        assertThatThrownBy(() -> {
                    // Reflectively confirm the method no longer exists with the old 2-arg signature.
                    HelmClient.class.getMethod("invokeWorkflow", String.class, Object.class);
                })
                .isInstanceOf(NoSuchMethodException.class);
    }

    // —— MEDIUM 6: path-segment encoding ——

    @Test
    void pathSegmentWithSpaceIsPercentEncoded() throws Exception {
        // Use com.sun.net.httpserver to inspect the raw path (WireMock's url matching is ambiguous with %20).
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        java.util.concurrent.atomic.AtomicReference<String> rawPath =
                new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/", exchange -> {
            rawPath.set(exchange.getRequestURI().getRawPath());
            String body = "{\"operationId\":\"op_1\",\"status\":\"SUCCEEDED\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length());
            try (var os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });
        server.start();
        try (HelmClient client = HelmClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .build()) {
            client.dispatch("my agent", AgentDispatchRequest.of("i1", "s1", "hi"));
            // Space must be %20, not + (form-encoding).
            assertThat(rawPath.get()).contains("/agents/my%20agent/dispatch");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pathSegmentWithSlashIsPercentEncoded() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        java.util.concurrent.atomic.AtomicReference<String> rawPath =
                new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/", exchange -> {
            rawPath.set(exchange.getRequestURI().getRawPath());
            String body = "{\"operationId\":\"op_1\",\"status\":\"SUCCEEDED\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length());
            try (var os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });
        server.start();
        try (HelmClient client = HelmClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .build()) {
            // "a/b" as a single segment must be encoded as "a%2Fb" so it's not split into two path segments.
            client.dispatch("a/b", AgentDispatchRequest.of("i1", "s1", "hi"));
            assertThat(rawPath.get()).contains("/agents/a%2Fb/dispatch");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pathSegmentWithPlusIsPercentEncoded() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        java.util.concurrent.atomic.AtomicReference<String> rawPath =
                new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/", exchange -> {
            rawPath.set(exchange.getRequestURI().getRawPath());
            String body = "{\"operationId\":\"op_1\",\"text\":\"hi\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length());
            try (var os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });
        server.start();
        try (HelmClient client = HelmClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .build()) {
            // "+" must be encoded as %2B, not left as "+" (which is decoded as space in form-encoding contexts).
            client.prompt("a+b", "i1", "s1", "hi");
            assertThat(rawPath.get()).contains("/agents/a%2Bb/");
        } finally {
            server.stop(0);
        }
    }

    // —— LOW 9: prompt null-text coercion ——

    @Test
    void promptCoercesNullTextToEmptyString() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/agents/echo/instances/i1/sessions/s1/prompt"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"operationId\":\"op_1\",\"text\":\"hi\"}")));

        try (HelmClient client =
                HelmClient.builder().baseUrl(wireMock.baseUrl()).build()) {
            client.prompt("echo", "i1", "s1", null);
            assertThat(wireMock.findAll(postRequestedFor(urlEqualTo("/agents/echo/instances/i1/sessions/s1/prompt"))))
                    .hasSize(1);
            // Body should contain "text":"" not a null literal
            String body = wireMock.findAll(postRequestedFor(urlEqualTo("/agents/echo/instances/i1/sessions/s1/prompt")))
                    .get(0)
                    .getBodyAsString();
            assertThat(body).contains("\"text\":\"\"");
        }
    }

    // —— MEDIUM 5: incremental SSE streaming ——

    @Test
    void promptStreamDeliversEventsIncrementally() throws Exception {
        // Use com.sun.net.httpserver to send SSE frames with an explicit delay so the first event arrives
        // before the second chunk is written.
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/agents/echo/instances/i1/sessions/s1/prompt/stream", exchange -> {
            try {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                var os = exchange.getResponseBody();
                // First frame
                os.write("data: {\"text\":\"first\"}\n\n".getBytes());
                os.flush();
                Thread.sleep(400);
                // Second frame
                os.write("data: {\"text\":\"second\"}\n\n".getBytes());
                os.flush();
                os.close();
            } catch (Exception e) {
                // client closed
            }
        });
        server.start();
        try (HelmClient client = HelmClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .requestTimeout(java.time.Duration.ofSeconds(10))
                .build()) {
            Flow.Publisher<PromptStreamEvent> publisher = client.promptStream("echo", "i1", "s1", "hi");

            CountDownLatch firstEventLatch = new CountDownLatch(1);
            CountDownLatch completeLatch = new CountDownLatch(1);
            AtomicReference<Long> firstEventTime = new AtomicReference<>();
            AtomicReference<Long> completeTime = new AtomicReference<>();
            List<PromptStreamEvent> events = new CopyOnWriteArrayList<>();
            long startTime = System.nanoTime();

            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(PromptStreamEvent event) {
                    events.add(event);
                    if (firstEventTime.get() == null) {
                        firstEventTime.set(System.nanoTime() - startTime);
                        firstEventLatch.countDown();
                    }
                }

                @Override
                public void onError(Throwable t) {
                    completeLatch.countDown();
                }

                @Override
                public void onComplete() {
                    completeTime.set(System.nanoTime() - startTime);
                    completeLatch.countDown();
                }
            });

            // The first event must arrive before the stream completes.
            assertThat(firstEventLatch.await(5, TimeUnit.SECONDS))
                    .as("first event arrives")
                    .isTrue();
            assertThat(events).hasSizeGreaterThanOrEqualTo(1);
            assertThat(events.get(0)).isInstanceOf(PromptStreamEvent.ContentDelta.class);
            assertThat(((PromptStreamEvent.ContentDelta) events.get(0)).text()).isEqualTo("first");

            // Wait for completion and verify all events arrived.
            assertThat(completeLatch.await(5, TimeUnit.SECONDS))
                    .as("stream completes")
                    .isTrue();
            assertThat(events).hasSize(2);
            assertThat(((PromptStreamEvent.ContentDelta) events.get(1)).text()).isEqualTo("second");

            // The first event should arrive measurably before completion (incremental delivery).
            assertThat(firstEventTime.get()).isNotNull();
            assertThat(completeTime.get()).isNotNull();
            assertThat(firstEventTime.get()).isLessThan(completeTime.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void promptStreamMapsErrorResponsesToHelmException() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/agents/echo/instances/i1/sessions/s1/prompt/stream"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"slow down\",\"details\":{}}}")));

        try (HelmClient client =
                HelmClient.builder().baseUrl(wireMock.baseUrl()).build()) {
            Flow.Publisher<PromptStreamEvent> publisher = client.promptStream("echo", "i1", "s1", "hi");

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(PromptStreamEvent event) {}

                @Override
                public void onError(Throwable t) {
                    errorRef.set(t);
                    latch.countDown();
                }

                @Override
                public void onComplete() {
                    latch.countDown();
                }
            });

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(errorRef.get()).isInstanceOf(io.agent.helm.core.error.HelmException.class);
            assertThat(((io.agent.helm.core.error.HelmException) errorRef.get()).code())
                    .isEqualTo("RATE_LIMITED");
        }
    }

    // —— MEDIUM 8: malformed SSE frame in streaming ——

    @Test
    void promptStreamClosesExceptionallyOnMalformedFrame() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/agents/echo/instances/i1/sessions/s1/prompt/stream"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: not-json-at-all\n\n")));

        try (HelmClient client =
                HelmClient.builder().baseUrl(wireMock.baseUrl()).build()) {
            Flow.Publisher<PromptStreamEvent> publisher = client.promptStream("echo", "i1", "s1", "hi");

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(PromptStreamEvent event) {}

                @Override
                public void onError(Throwable t) {
                    errorRef.set(t);
                    latch.countDown();
                }

                @Override
                public void onComplete() {
                    latch.countDown();
                }
            });

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(errorRef.get())
                    .as("publisher should close exceptionally on malformed frame")
                    .isNotNull();
        }
    }

    // —— Test fixture ——

    public static final class Person {
        public String name;
        public int age;
    }
}
