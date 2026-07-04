package io.agent.helm.sandbox.local;

import io.agent.helm.core.error.SandboxException;
import io.agent.helm.core.sandbox.Sandbox;
import io.agent.helm.core.sandbox.SandboxFileSystem;
import io.agent.helm.core.sandbox.SandboxShell;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A pure in-memory {@link Sandbox}: an isolated virtual file system with no host access and no shell capability. Useful
 * for tests and for agents that should not touch the host filesystem.
 */
public final class InMemorySandbox implements Sandbox {
    private final Map<String, String> files = new ConcurrentHashMap<>();
    private final InMemoryFileSystem fileSystem = new InMemoryFileSystem(files);
    private final SandboxShell shell = new DisabledShell("shell not supported by InMemorySandbox");

    public InMemorySandbox() {}

    @Override
    public SandboxFileSystem fs() {
        return fileSystem;
    }

    @Override
    public SandboxShell shell() {
        return shell;
    }

    /** Visible for inspection in tests. */
    Map<String, String> files() {
        return files;
    }

    private static final class InMemoryFileSystem implements SandboxFileSystem {
        private final Map<String, String> files;

        InMemoryFileSystem(Map<String, String> files) {
            this.files = files;
        }

        @Override
        public String readText(String path) {
            String normalized = normalize(path);
            String content = files.get(normalized);
            if (content == null) {
                throw new SandboxException("file not found", Map.of("path", path), Map.of());
            }
            return content;
        }

        @Override
        public void writeText(String path, String content) {
            files.put(normalize(path), content);
        }

        @Override
        public List<String> listFiles(String path) {
            String dir = normalize(path);
            Set<String> children = new java.util.TreeSet<>();
            for (String key : files.keySet()) {
                if (dir.isEmpty()) {
                    int slash = key.indexOf('/');
                    children.add(slash < 0 ? key : key.substring(0, slash));
                } else if (key.startsWith(dir + "/")) {
                    String rest = key.substring(dir.length() + 1);
                    int slash = rest.indexOf('/');
                    children.add(slash < 0 ? rest : rest.substring(0, slash));
                }
            }
            return List.copyOf(children);
        }

        @Override
        public boolean exists(String path) {
            String normalized = normalize(path);
            if (normalized.isEmpty()) {
                return true;
            }
            if (files.containsKey(normalized)) {
                return true;
            }
            String prefix = normalized + "/";
            for (String key : files.keySet()) {
                if (key.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void delete(String path) {
            String normalized = normalize(path);
            files.remove(normalized);
            String prefix = normalized + "/";
            files.keySet().removeIf(key -> key.startsWith(prefix));
        }

        private static String normalize(String path) {
            if (path == null) {
                throw new SandboxException("path must not be null", Map.of(), Map.of());
            }
            String cleaned = path.replace('\\', '/');
            if (Path.of(cleaned).isAbsolute()) {
                throw new SandboxException("absolute paths are not allowed", Map.of("path", path), Map.of());
            }
            Deque<String> parts = new ArrayDeque<>();
            for (String segment : cleaned.split("/")) {
                if (segment.isEmpty() || segment.equals(".")) {
                    continue;
                }
                if (segment.equals("..")) {
                    if (parts.isEmpty()) {
                        throw new SandboxException("path escapes sandbox root", Map.of("path", path), Map.of());
                    }
                    parts.removeLast();
                } else {
                    parts.addLast(segment);
                }
            }
            return String.join("/", parts);
        }
    }
}
