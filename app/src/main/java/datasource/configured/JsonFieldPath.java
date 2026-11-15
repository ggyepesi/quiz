package datasource.configured;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** The deliberately small JSON field-path grammar used by configured REST sources. */
final class JsonFieldPath {
    private JsonFieldPath() { }

    static String require(String value, String what) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(what + " is required");
        for (String segment : clean.split("\\.", -1)) {
            String name = segment.endsWith("[]")
                    ? segment.substring(0, segment.length() - 2) : segment;
            if (name.isBlank() || name.contains("[") || name.contains("]")) {
                throw new IllegalArgumentException(
                        what + " has an invalid segment '" + segment + "': " + clean);
            }
        }
        return clean;
    }

    static List<JsonNode> nodes(JsonNode root, String path) {
        List<JsonNode> current = root == null ? List.of() : List.of(root);
        for (String segment : path.split("\\.")) {
            boolean array = segment.endsWith("[]");
            String name = array ? segment.substring(0, segment.length() - 2) : segment;
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : current) {
                JsonNode child = node == null ? null : node.get(name);
                if (child == null || child.isNull() || child.isMissingNode()) continue;
                if (array) {
                    if (child.isArray()) child.forEach(next::add);
                } else {
                    next.add(child);
                }
            }
            current = next;
        }
        return List.copyOf(current);
    }

    static List<String> strings(JsonNode root, String path) {
        return nodes(root, path).stream()
                .filter(node -> node.isValueNode())
                .map(JsonNode::asText).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    static String first(JsonNode root, String path) {
        return strings(root, path).stream().findFirst().orElse("");
    }
}
