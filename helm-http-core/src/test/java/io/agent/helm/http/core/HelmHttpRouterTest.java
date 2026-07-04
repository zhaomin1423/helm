package io.agent.helm.http.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class HelmHttpRouterTest {
    @Test
    void matchesPathAndExtractsParams() {
        HelmHttpRouter router = HelmHttpRouter.builder()
                .route(
                        "GET",
                        "/agents/{agent}/instances/{instance}",
                        request -> HelmHttpResponse.ok(
                                "\"" + request.pathParam("agent") + ":" + request.pathParam("instance") + "\""))
                .build();

        HelmHttpResponse response =
                router.handle(new HelmHttpRequest("GET", "/agents/echo/instances/i", Map.of(), Map.of(), ""));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("\"echo:i\"");
    }

    @Test
    void returns404WhenNoRouteMatches() {
        HelmHttpRouter router = HelmHttpRouter.builder().build();

        HelmHttpResponse response = router.handle(new HelmHttpRequest("GET", "/nope", Map.of(), Map.of(), ""));

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void methodMustMatch() {
        HelmHttpRouter router = HelmHttpRouter.builder()
                .route("POST", "/x", request -> HelmHttpResponse.ok("posted"))
                .build();

        HelmHttpResponse response = router.handle(new HelmHttpRequest("GET", "/x", Map.of(), Map.of(), ""));

        assertThat(response.status()).isEqualTo(404);
    }
}
