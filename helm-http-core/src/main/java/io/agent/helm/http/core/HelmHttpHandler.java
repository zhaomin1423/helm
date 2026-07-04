package io.agent.helm.http.core;

/** Handles one {@link HelmHttpRequest} and returns a {@link HelmHttpResponse}. */
@FunctionalInterface
public interface HelmHttpHandler {
    HelmHttpResponse handle(HelmHttpRequest request) throws Exception;
}
