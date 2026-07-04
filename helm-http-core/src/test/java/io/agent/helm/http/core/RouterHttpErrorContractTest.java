package io.agent.helm.http.core;

/** Runs {@link HttpErrorContractTest} directly against the {@link HelmHttpRouter}. */
final class RouterHttpErrorContractTest extends HttpErrorContractTest {
    @Override
    protected HelmHttpResponse send(HelmHttpRequest request) {
        return router.handle(request);
    }
}
