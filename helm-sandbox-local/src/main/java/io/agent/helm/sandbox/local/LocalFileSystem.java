package io.agent.helm.sandbox.local;

import io.agent.helm.core.error.SandboxException;
import io.agent.helm.core.sandbox.SandboxFileSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** A {@link SandboxFileSystem} backed by the host filesystem, rooted at a fixed directory. */
final class LocalFileSystem implements SandboxFileSystem {
    private final Path root;

    LocalFileSystem(Path root) {
        this.root = root;
    }

    @Override
    public String readText(String path) {
        Path resolved = SandboxPaths.resolveWithin(root, path);
        if (!Files.isRegularFile(resolved)) {
            throw new SandboxException("file not found", Map.of("path", path), Map.of());
        }
        try {
            return Files.readString(resolved);
        } catch (IOException e) {
            throw new SandboxException(
                    "failed to read file", Map.of("path", path), Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    @Override
    public void writeText(String path, String content) {
        Path resolved = SandboxPaths.resolveWithin(root, path);
        try {
            Path parent = resolved.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(resolved, content);
        } catch (IOException e) {
            throw new SandboxException(
                    "failed to write file", Map.of("path", path), Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    @Override
    public List<String> listFiles(String path) {
        Path dir = path == null || path.isBlank() ? root : SandboxPaths.resolveWithin(root, path);
        if (!Files.isDirectory(dir)) {
            throw new SandboxException("not a directory", Map.of("path", String.valueOf(path)), Map.of());
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new SandboxException(
                    "failed to list directory",
                    Map.of("path", String.valueOf(path)),
                    Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    @Override
    public boolean exists(String path) {
        Path resolved = SandboxPaths.resolveWithin(root, path);
        return Files.exists(resolved);
    }

    @Override
    public void delete(String path) {
        Path resolved = SandboxPaths.resolveWithin(root, path);
        try {
            Files.deleteIfExists(resolved);
        } catch (IOException e) {
            throw new SandboxException(
                    "failed to delete file", Map.of("path", path), Map.of("message", String.valueOf(e.getMessage())));
        }
    }
}
