package wikidata.explore.query.log;

import java.util.List;

/**
 * Serializes a query-log tree to plain, fully-expanded text — for saving a run's
 * log to a file so two runs can be diffed offline (independent of what's collapsed
 * in the UI). Each node prints its title + status + summary; its request text and
 * error (if any) are indented beneath it, and every child is recursed.
 */
public final class LogText {

    private LogText() {
    }

    /** The whole log (every root and its full subtree). */
    public static String toText(List<LogNode> roots) {
        StringBuilder sb = new StringBuilder();
        if (roots != null) {
            for (LogNode root : roots) {
                append(sb, root, 0);
            }
        }
        return sb.toString();
    }

    /** One entry (a single root and its subtree). */
    public static String toText(LogNode root) {
        StringBuilder sb = new StringBuilder();
        if (root != null) {
            append(sb, root, 0);
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, LogNode node, int depth) {
        if (node == null) {
            return;
        }
        String indent = "  ".repeat(depth);

        sb.append(indent).append(blankTo(node.title(), "(untitled)"));
        if (node.status() != null) {
            sb.append("  [").append(node.status()).append("]");
        }
        String summary = node.summary();
        if (notBlank(summary)) {
            sb.append("  ").append(summary.strip());
        }
        sb.append('\n');

        appendBlock(sb, indent + "    | ", node.request());
        if (node.messages() != null && !node.messages().isEmpty()) {
            appendBlock(sb, indent + "    | ", String.join("\n", node.messages()));
        }
        if (notBlank(node.error())) {
            sb.append(indent).append("    ! ").append(node.error().strip()).append('\n');
        }

        for (LogNode child : node.steps()) {
            append(sb, child, depth + 1);
        }
    }

    // Indent every line of a multi-line block (e.g. a SPARQL request) under a prefix.
    private static void appendBlock(StringBuilder sb, String linePrefix, String text) {
        if (!notBlank(text)) {
            return;
        }
        for (String line : text.strip().split("\n", -1)) {
            sb.append(linePrefix).append(line).append('\n');
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankTo(String s, String fallback) {
        return notBlank(s) ? s : fallback;
    }
}
