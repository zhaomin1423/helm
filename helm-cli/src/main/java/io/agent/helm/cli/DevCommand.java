package io.agent.helm.cli;

import io.agent.helm.http.core.HelmHttpRouter;
import io.agent.helm.http.core.HelmHttpRoutes;
import io.agent.helm.http.servlet.HelmHttpServlet;
import io.agent.helm.runtime.AgentRuntime;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "dev", description = "Start a local HTTP server exposing the registered Helm routes (127.0.0.1).")
final class DevCommand extends HelmSubcommand {
    @Option(names = "--port", defaultValue = "8080", description = "Listen port (default 8080).")
    int port;

    /** Set to {@code true} once the JVM shutdown hook has been registered; visible for tests. */
    volatile boolean shutdownHookRegistered;

    @Override
    public Integer call() throws Exception {
        HelmApp app = loadApp();
        Server server = startServer(port, app);
        System.out.println("helm dev listening on http://127.0.0.1:" + port + " (press Ctrl+C to stop)");
        server.join();
        return 0;
    }

    /** Backwards-compatible entry point used by tests; loads the {@link HelmApp} then delegates. */
    Server startServer(int listenPort) throws Exception {
        return startServer(listenPort, loadApp());
    }

    Server startServer(int listenPort, HelmApp app) throws Exception {
        HelmHttpRouter router = HelmHttpRoutes.router(app.agentRuntime(), app.workflowRuntime());
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(listenPort);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler("/", ServletContextHandler.NO_SESSIONS);
        context.addServlet(new ServletHolder(new HelmHttpServlet(router)), "/*");
        server.setHandler(context);
        // Stop the Jetty server on JVM shutdown so the listening socket is released cleanly.
        server.setStopAtShutdown(true);
        // Also close the AgentRuntime (its internal LeaseManager scheduler) on JVM shutdown.
        Thread hook = buildShutdownHook(server, app);
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // Shutdown already in progress; cannot register a hook. Skip — the JVM is exiting anyway.
        }
        shutdownHookRegistered = true;
        server.start();
        return server;
    }

    /** Builds the JVM shutdown hook: stops Jetty and closes the {@link AgentRuntime}. Package-private for tests. */
    Thread buildShutdownHook(Server server, HelmApp app) {
        return new Thread(
                () -> {
                    try {
                        server.stop();
                    } catch (Exception ignored) {
                        // Best-effort cleanup on JVM shutdown.
                    }
                    closeQuietly(app.agentRuntime());
                },
                "helm-dev-shutdown");
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best-effort cleanup on JVM shutdown.
        }
    }
}
