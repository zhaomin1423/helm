package io.agent.helm.http.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Matches a path against a pattern with {@code {param}} segments and extracts parameter values. */
final class PathPattern {
    private final Pattern regex;
    private final List<String> paramNames;

    PathPattern(String pattern) {
        List<String> names = new ArrayList<>();
        StringBuilder regex = new StringBuilder("^");
        for (String segment : pattern.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.startsWith("{") && segment.endsWith("}")) {
                names.add(segment.substring(1, segment.length() - 1));
                regex.append("/([^/]+)");
            } else {
                regex.append("/").append(Pattern.quote(segment));
            }
        }
        if (regex.length() == 1) {
            regex.append("/?");
        }
        regex.append("$");
        this.regex = Pattern.compile(regex.toString());
        this.paramNames = List.copyOf(names);
    }

    Optional<Map<String, String>> match(String path) {
        Matcher matcher = regex.matcher(path);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < paramNames.size(); i++) {
            params.put(paramNames.get(i), matcher.group(i + 1));
        }
        return Optional.of(params);
    }
}
