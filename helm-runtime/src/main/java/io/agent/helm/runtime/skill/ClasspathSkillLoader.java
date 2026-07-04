package io.agent.helm.runtime.skill;

import io.agent.helm.core.error.ValidationException;
import io.agent.helm.core.skill.SkillDefinition;
import io.agent.helm.core.skill.SkillLoader;
import io.agent.helm.core.skill.SkillResource;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads skills from the classpath by convention: {@code <base>/<name>/SKILL.md} plus sibling resource files.
 * {@code SKILL.md} uses YAML-style frontmatter delimited by {@code ---} lines to declare {@code name} and
 * {@code description}; the body is the skill instructions.
 */
public final class ClasspathSkillLoader implements SkillLoader {
    private static final String SKILL_FILE = "SKILL.md";

    private final ClassLoader classLoader;
    private final String base;

    public ClasspathSkillLoader() {
        this(Thread.currentThread().getContextClassLoader(), "helm/skills");
    }

    public ClasspathSkillLoader(ClassLoader classLoader, String base) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        String normalized = base.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("base must not be blank");
        }
        this.base = normalized;
    }

    @Override
    public List<SkillDefinition> loadAll() {
        List<SkillDefinition> skills = new ArrayList<>();
        for (String name : discoverSkillNames()) {
            skills.add(load(name)
                    .orElseThrow(() ->
                            new ValidationException("skill disappeared during load", Map.of("skill", name), Map.of())));
        }
        return List.copyOf(skills);
    }

    @Override
    public Optional<SkillDefinition> load(String name) {
        requireValidName(name);
        URL skillMd = classLoader.getResource(base + "/" + name + "/" + SKILL_FILE);
        if (skillMd == null) {
            return Optional.empty();
        }
        String content = readString(skillMd);
        List<SkillResource> resources = loadResources(name);
        return Optional.of(parse(name, content, resources));
    }

    private Set<String> discoverSkillNames() {
        Set<String> names = new TreeSet<>();
        try {
            Enumeration<URL> roots = classLoader.getResources(base);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if ("file".equals(root.getProtocol())) {
                    listFileSystemSkillNames(root, names);
                } else if ("jar".equals(root.getProtocol())) {
                    listJarSkillNames(root, names);
                }
            }
        } catch (IOException e) {
            throw new ValidationException(
                    "failed to discover skills",
                    Map.of("base", base),
                    Map.of("message", String.valueOf(e.getMessage())));
        }
        return names;
    }

    private void listFileSystemSkillNames(URL root, Set<String> names) {
        try {
            Path dir = Paths.get(root.toURI());
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(name -> classLoader.getResource(base + "/" + name + "/" + SKILL_FILE) != null)
                        .forEach(names::add);
            }
        } catch (Exception ignored) {
            // Skip unreadable roots.
        }
    }

    private void listJarSkillNames(URL root, Set<String> names) {
        try {
            JarURLConnection connection = (JarURLConnection) root.openConnection();
            JarFile jar = connection.getJarFile();
            String entryName = connection.getEntryName();
            String prefix = (entryName == null ? base : entryName) + "/";
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                if (!entry.startsWith(prefix)) {
                    continue;
                }
                String rest = entry.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash <= 0) {
                    continue;
                }
                String candidate = rest.substring(0, slash);
                if (classLoader.getResource(base + "/" + candidate + "/" + SKILL_FILE) != null) {
                    names.add(candidate);
                }
            }
        } catch (IOException ignored) {
            // Skip unreadable jars.
        }
    }

    private List<SkillResource> loadResources(String name) {
        List<SkillResource> resources = new ArrayList<>();
        String skillBase = base + "/" + name;
        try {
            Enumeration<URL> roots = classLoader.getResources(skillBase);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if ("file".equals(root.getProtocol())) {
                    collectFileSystemResources(root, resources);
                } else if ("jar".equals(root.getProtocol())) {
                    collectJarResources(root, skillBase + "/", resources);
                }
            }
        } catch (IOException e) {
            throw new ValidationException("failed to load skill resources", Map.of("skill", name), Map.of());
        }
        return List.copyOf(resources);
    }

    private void collectFileSystemResources(URL root, List<SkillResource> resources) {
        try {
            Path dir = Paths.get(root.toURI());
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().equals(SKILL_FILE))
                        .forEach(p ->
                                resources.add(new SkillResource(p.getFileName().toString(), readStringUnchecked(p))));
            }
        } catch (Exception ignored) {
            // Skip unreadable roots.
        }
    }

    private void collectJarResources(URL root, String prefix, List<SkillResource> resources) {
        try {
            JarURLConnection connection = (JarURLConnection) root.openConnection();
            JarFile jar = connection.getJarFile();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (!entryName.startsWith(prefix) || entry.isDirectory()) {
                    continue;
                }
                String relative = entryName.substring(prefix.length());
                if (relative.isEmpty() || relative.equals(SKILL_FILE) || relative.contains("/")) {
                    continue;
                }
                resources.add(new SkillResource(relative, readString(new URL(root, relative))));
            }
        } catch (IOException ignored) {
            // Skip unreadable jars.
        }
    }

    static SkillDefinition parse(String dirName, String content, List<SkillResource> resources) {
        Map<String, String> frontmatter = new LinkedHashMap<>();
        String body = content;
        String trimmed = content.stripLeading();
        if (trimmed.startsWith("---")) {
            int afterOpening = trimmed.indexOf('\n');
            int closing = afterOpening < 0 ? -1 : trimmed.indexOf("\n---", afterOpening);
            if (closing < 0) {
                throw new ValidationException(
                        "skill SKILL.md has unterminated frontmatter", Map.of("skill", dirName), Map.of());
            }
            String frontBlock = trimmed.substring(afterOpening + 1, closing);
            body = trimmed.substring(closing + 4);
            for (String line : frontBlock.split("\n")) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                frontmatter.put(key, value);
            }
        }
        String name = frontmatter.getOrDefault("name", dirName);
        String description = frontmatter.getOrDefault("description", "");
        if (name.isBlank()) {
            throw new ValidationException("skill name is required", Map.of("skill", dirName), Map.of());
        }
        if (description.isBlank()) {
            throw new ValidationException("skill description is required", Map.of("skill", dirName), Map.of());
        }
        return new SkillDefinition(name, description, body.strip(), resources);
    }

    private static void requireValidName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
            throw new ValidationException("invalid skill name", Map.of("name", String.valueOf(name)), Map.of());
        }
    }

    private static String readString(URL url) {
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ValidationException(
                    "failed to read skill resource",
                    Map.of("url", String.valueOf(url)),
                    Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    private static String readStringUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new ValidationException(
                    "failed to read skill resource",
                    Map.of("path", String.valueOf(path)),
                    Map.of("message", String.valueOf(e.getMessage())));
        }
    }
}
