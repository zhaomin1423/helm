package io.agent.helm.core.sandbox;

import java.util.List;

/**
 * A controlled, path-normalized file system view. All paths are relative to the sandbox root; implementations must
 * reject absolute paths and traversal sequences that would escape the root.
 */
public interface SandboxFileSystem {
    String readText(String path);

    void writeText(String path, String content);

    /** Immediate child paths (relative to {@code path}) of the directory at {@code path}. */
    List<String> listFiles(String path);

    boolean exists(String path);

    /**
     * Deletes the file or directory at {@code path}. When {@code path} denotes a directory, deletion is
     * <b>recursive</b> — every entry beneath it is removed as well. Implementations must reject paths that escape the
     * sandbox root and must not follow symlinks outside the root.
     */
    void delete(String path);
}
