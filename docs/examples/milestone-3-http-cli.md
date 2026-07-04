# Milestone 3 HTTP and CLI

Milestone 3 exposes the Helm runtime over HTTP and a local CLI while keeping the core runtime
free of any web framework. `helm-http-core` defines framework-neutral routes and DTOs,
`helm-http-servlet` is the only Servlet-aware module, and `helm-cli` adds Picocli commands and an
embedded Jetty dev server.

## HTTP routes

`HelmHttpRoutes.router(agentRuntime, workflowRuntime)` builds the standard router. Routes are
opt-in: nothing is exposed until the router is mounted by a transport.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/agents/{agent}/instances/{instance}/sessions/{session}/prompt` | Run a prompt synchronously |
| POST | `/agents/{agent}/dispatch` | Admit an operation (`{instance,session,text}`) |
| POST | `/workflows/{workflow}/invoke` | Invoke a workflow (`{input}`) |
| GET | `/operations/{id}` | Inspect an operation |
| GET | `/operations/{id}/events` | Ordered operation events |
| GET | `/sessions/{id}/operations` | List operations for a session |
| GET | `/workflow-runs/{id}` | Inspect a workflow run |
| GET | `/workflows/{workflow}/runs` | List runs for a workflow |

Errors use a unified body `{"error":{"code","message","details"}}`. `HelmException` codes map to
stable HTTP statuses (e.g. `AGENT_NOT_FOUND`→404, `VALIDATION_FAILED`→400, `SESSION_BUSY`→409,
`PROVIDER_RATE_LIMITED`→429, `PROVIDER_ERROR`→502). The reusable `HttpErrorContractTest` asserts
this contract; the servlet adapter runs it over real HTTP via embedded Jetty.

## Servlet adapter

`HelmHttpServlet` mounts the router on any Jakarta Servlet container:

```java
HelmHttpRouter router = HelmHttpRoutes.router(agentRuntime, workflowRuntime);
context.addServlet(new ServletHolder(new HelmHttpServlet(router)), "/*");
```

`helm-http-core` has no Servlet dependency.

## CLI

`helm` is a Picocli binary with `run`, `dev`, `operations`, `runs`, and `run-detail` subcommands.
Components are supplied explicitly via a `HelmApp` assembly class pointed to by `--app <FQCN>`; no
classpath scanning is performed.

```bash
# Invoke a workflow locally
helm run upper --app com.example.MyApp --input '{"text":"hi"}'

# Inspect persisted state
helm operations "upper:instance-1:default" --app com.example.MyApp
helm runs upper --app com.example.MyApp
helm run-detail run_123 --app com.example.MyApp

# Start a local HTTP server (127.0.0.1 only)
helm dev --app com.example.MyApp --port 8080
```

```java
public final class MyApp implements HelmApp {
    private final AgentRuntime agentRuntime = /* ... */;
    private final WorkflowRuntime workflowRuntime = /* ... */;
    @Override public AgentRuntime agentRuntime() { return agentRuntime; }
    @Override public WorkflowRuntime workflowRuntime() { return workflowRuntime; }
}
```

`helm dev` binds `127.0.0.1` by default. CLI failures print the same structured error body and
return a non-zero exit code.

## Verification

- `HttpErrorContractTest` runs directly against the router and over HTTP against the servlet
  adapter (Jetty).
- `HelmCliRunTest` runs `helm run` in-process and asserts the result and exit code.
- `HelmCliDevTest` starts `helm dev` and exercises a route over HTTP.
