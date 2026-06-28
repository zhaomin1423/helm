package io.agent.helm.core.sandbox;

public interface SandboxFileSystem {
    String readText(String path);

    void writeText(String path, String content);
}
