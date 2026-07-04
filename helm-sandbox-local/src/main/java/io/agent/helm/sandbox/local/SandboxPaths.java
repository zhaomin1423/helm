package io.agent.helm.sandbox.local;

import io.agent.helm.core.error.SandboxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Path normalization and containment checks for {@link LocalSandbox}. */
final class SandboxPaths {
    private SandboxPaths() {}

    static Path resolveWithin(Path root, String relativePath) {
        if (relativePath == null) {
            throw new SandboxException("path must not be null", Map.of(), Map.of());
        }
        Path input = Path.of(relativePath.replace('\\', '/'));
        if (input.isAbsolute()) {
            throw new SandboxException("absolute paths are not allowed", Map.of("path", relativePath), Map.of());
        }
        Path realRoot = realPath(root);
        Path resolved = root.resolve(input).normalize();
        if (!resolved.startsWith(root)) {
            throw new SandboxException("path escapes sandbox root", Map.of("path", relativePath), Map.of());
        }
        Path realAncestor = realPathOfExistingAncestor(resolved);
        if (!realAncestor.startsWith(realRoot)) {
            throw new SandboxException("path escapes sandbox root via symlink", Map.of("path", relativePath), Map.of());
        }
        return resolved;
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (Exception e) {
            throw new SandboxException("cannot resolve sandbox root", Map.of("path", String.valueOf(path)), Map.of());
        }
    }

    private static Path realPathOfExistingAncestor(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return path;
        }
        try {
            return existing.toRealPath();
        } catch (Exception e) {
            return existing;
        }
    }
}
