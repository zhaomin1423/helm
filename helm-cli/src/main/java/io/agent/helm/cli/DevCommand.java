package io.agent.helm.cli;

import io.agent.helm.http.core.HelmHttpRouter;
import io.agent.helm.http.core.HelmHttpRoutes;
import io.agent.helm.http.servlet.HelmHttpServlet;
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

    @Override
    public Integer call() throws Exception {
        Server server = startServer(port);
        System.out.println("helm dev listening on http://127.0.0.1:" + port + " (press Ctrl+C to stop)");
        server.join();
        return 0;
    }

    Server startServer(int listenPort) throws Exception {
        HelmApp app = loadApp();
        HelmHttpRouter router = HelmHttpRoutes.router(app.agentRuntime(), app.workflowRuntime());
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(listenPort);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler("/", ServletContextHandler.NO_SESSIONS);
        context.addServlet(new ServletHolder(new HelmHttpServlet(router)), "/*");
        server.setHandler(context);
        server.start();
        return server;
    }
}
