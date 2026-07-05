package io.agent.helm.sandbox.local;

import io.agent.helm.core.error.SandboxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

/** Path normalization and containment checks for {@link LocalSandbox}. */
final class SandboxPaths {
    private SandboxPaths() {}

    /**
     * Resolves {@code relativePath} against {@code root}, rejecting absolute paths, traversal sequences, and any
     * symlink component. The returned path is normalized but NOT realized — callers that perform mutating operations
     * must additionally invoke {@link #ensureWithinRealized(Path, Path)} immediately before the operation to close the
     * TOCTOU window between this check and the file mutation.
     */
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
        // Walk down from the real root, rejecting any symlink component. This catches links to
        // non-existent targets (which the previous existing-ancestor check missed) as well as
        // links to existing outside paths.
        Path current = realRoot;
        for (Path component : root.relativize(resolved)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new SandboxException(
                        "symlinks are not permitted inside the sandbox", Map.of("path", relativePath), Map.of());
            }
        }
        return resolved;
    }

    /**
     * Closes the TOCTOU window between {@link #resolveWithin} and a mutating operation: resolves the real path of
     * {@code resolved} (or its nearest existing ancestor when the leaf or an intermediate directory does not yet exist)
     * and asserts it remains within {@code realRoot}. Mutating callers must invoke this immediately before the
     * mutation.
     */
    static void ensureWithinRealized(Path root, Path resolved) {
        Path realRoot = realPath(root);
        // Walk up to the nearest existing component; toRealPath fails on non-existent paths.
        Path toRealize = resolved;
        while (toRealize != null && !Files.exists(toRealize, LinkOption.NOFOLLOW_LINKS)) {
            toRealize = toRealize.getParent();
        }
        if (toRealize == null) {
            return;
        }
        Path realized = realPath(toRealize);
        if (!realized.startsWith(realRoot)) {
            throw new SandboxException(
                    "path escapes sandbox root (symlink race detected)",
                    Map.of("path", String.valueOf(resolved)),
                    Map.of());
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (Exception e) {
            throw new SandboxException("cannot resolve sandbox root", Map.of("path", String.valueOf(path)), Map.of());
        }
    }
}
