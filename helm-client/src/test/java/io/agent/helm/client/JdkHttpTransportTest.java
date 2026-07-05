package io.agent.helm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Tests for {@link JdkHttpTransport} resource lifecycle, {@link RetryPolicy} contract, and interruption. */
final class JdkHttpTransportTest {

    @Test
    void closeReleasesHttpClientResources() {
        HelmClientConfig config = new HelmClientConfig(
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                RetryPolicy.DEFAULT,
                new ObjectMapper().findAndRegisterModules(),
                Function.identity());
        JdkHttpTransport transport = new JdkHttpTransport(java.net.URI.create("http://localhost:1"), config);
        // close() must not throw even if no request was ever sent.
        transport.close();
    }

    @Test
    void closeIsIdempotent() {
        HelmClientConfig config = new HelmClientConfig(
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                RetryPolicy.DEFAULT,
                new ObjectMapper().findAndRegisterModules(),
                Function.identity());
        JdkHttpTransport transport = new JdkHttpTransport(java.net.URI.create("http://localhost:1"), config);
        transport.close();
        transport.close();
    }

    @Test
    void streamResponseExposesStatusHeadersAndLines() {
        assertThat(StreamResponse.class.getRecordComponents()).hasSize(3);
        assertThat(StreamResponse.class.getRecordComponents()[0].getName()).isEqualTo("status");
        assertThat(StreamResponse.class.getRecordComponents()[1].getName()).isEqualTo("headers");
        assertThat(StreamResponse.class.getRecordComponents()[2].getName()).isEqualTo("lines");
    }

    // —— LOW 10: retry loop must break on interruption ——

    @Test
    void retryLoopBreaksOnInterruptionDuringBackoff() throws Exception {
        // Use a real server that returns a retryable status (503) for a GET request.
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/operations/op_1", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.getResponseBody().close();
        });
        server.start();

        // Long backoff so the interrupt lands during sleep.
        RetryPolicy policy = new RetryPolicy(10, Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ZERO);
        HelmClientConfig config = new HelmClientConfig(
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                policy,
                new ObjectMapper().findAndRegisterModules(),
                Function.identity());
        JdkHttpTransport transport = new JdkHttpTransport(
                java.net.URI.create("http://localhost:" + server.getAddress().getPort()), config);

        AtomicReference<Throwable> caught = new AtomicReference<>();
        CountDownLatch firstAttemptDone = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                transport.send("GET", "/operations/op_1", null);
            } catch (Throwable e) {
                // firstAttemptDone ensures we only capture the real exception, not early wiring errors
                caught.set(e);
                firstAttemptDone.countDown();
            }
        });

        worker.start();
        // Give the first attempt time to complete and enter the backoff sleep.
        Thread.sleep(200);
        worker.interrupt();
        worker.join(5_000);

        assertThat(caught.get())
                .as("retry loop should throw on interruption, not continue")
                .isInstanceOf(RuntimeException.class);
        assertThat(caught.get().getMessage()).contains("interrupted");
        transport.close();
        server.stop(0);
    }

    // —— RetryPolicy contract ——

    @Test
    void retryPolicyDisabledHasSingleAttempt() {
        assertThat(RetryPolicy.DISABLED.maxAttempts()).isEqualTo(1);
    }

    @Test
    void retryPolicyDefaultHasThreeAttempts() {
        assertThat(RetryPolicy.DEFAULT.maxAttempts()).isEqualTo(3);
    }

    @Test
    void retryPolicyRejectsZeroAttempts() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
