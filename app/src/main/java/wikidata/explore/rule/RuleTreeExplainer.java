package wikidata.explore.rule;

import wikidata.explore.filter.WikidataValueFilter;
import wikidata.explore.model.RuleDirection;

import java.util.List;

/**
 * Renders a compiled {@link RuleNode} tree as a plain-language extraction
 * plan — the readable counterpart to the raw {@code RuleTreeConfig} JSON.
 *
 * It explains, top to bottom, exactly what a generation run will fetch:
 * the membership (which entities), each included field (property, shape,
 * required/optional), and each child edge (direction, per-parent limit,
 * class membership, value filters), recursing into nested children. The
 * point is to let a model be sanity-checked before paying for a slow run —
 * the things people get wrong (edge direction, inline-vs-child, a missing
 * required flag, a value filter) are all surfaced in words.
 */
public final class RuleTreeExplainer {

    private RuleTreeExplainer() {}

    public static String explain(RuleNode root) {
        StringBuilder sb = new StringBuilder();
        explainRoot(root, sb);
        sb.append('\n').append(legend());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Root
    // ------------------------------------------------------------------

    private static void explainRoot(RuleNode node, StringBuilder sb) {
        String name = nodeName(node);

        sb.append(name).append('\n');
        sb.append("  Members: ").append(membershipPhrase(node)).append('\n');
        sb.append("  Take up to ").append(node.limit()).append(" of them");
        if (node.labelConfig().requireLabel()
                && "en".equalsIgnoreCase(node.labelConfig().language())) {
            sb.append(", each with an English label");
        }
        sb.append(".\n");

        explainBody(node, sb, "  ", "each " + lower(name));
    }

    // ------------------------------------------------------------------
    // Shared body: constraints, value filters, fields, child edges
    // ------------------------------------------------------------------

    private static void explainBody(
            RuleNode node,
            StringBuilder sb,
            String indent,
            String subjectNoun) {

        if (!node.includedQids().isEmpty()) {
            sb.append(indent).append("• restricted to ")
              .append(node.includedQids().size())
              .append(" specific item(s)\n");
        }
        if (!node.excludedQids().isEmpty()) {
            sb.append(indent).append("• excludes ")
              .append(node.excludedQids().size())
              .append(" item(s)\n");
        }
        for (WikidataValueFilter vf : node.valueFilters()) {
            sb.append(indent).append("• keep only where ")
              .append(filterPhrase(vf)).append('\n');
        }

        List<RuleIncludedField> fields = node.includedFields();
        if (!fields.isEmpty()) {
            sb.append(indent).append("Fields (on ").append(subjectNoun)
              .append("):\n");

            int nameW = fields.stream()
                    .mapToInt(f -> safe(f.fieldName()).length())
                    .max().orElse(0);

            for (RuleIncludedField f : fields) {
                sb.append(indent).append("  ")
                  .append(pad(safe(f.fieldName()), nameW)).append("  ")
                  .append(pad(safe(f.propertyPid()), 6)).append("  ")
                  .append(fieldDescription(f)).append('\n');
            }
        }

        List<RuleEdge> edges = node.edges();
        if (!edges.isEmpty()) {
            sb.append(indent).append("Child objects:\n");
            for (RuleEdge edge : edges) {
                explainEdge(edge, sb, indent + "  ");
            }
        }
    }

    // ------------------------------------------------------------------
    // Child edge
    // ------------------------------------------------------------------

    private static void explainEdge(
            RuleEdge edge,
            StringBuilder sb,
            String indent) {

        RuleNode child = edge.childNode();
        String childName = nodeName(child);
        String shape = edge.collection() ? "list" : "one";

        sb.append(indent)
          .append(safe(edge.fieldName()))
          .append("  (").append(shape).append(" of ")
          .append(lower(childName)).append(")  ")
          .append(directionPhrase(child))
          .append('\n');

        String inner = indent + "    ";

        if (child.hasMembershipFilter()) {
            sb.append(inner).append("• only where ")
              .append(child.membershipPid()).append(" = ")
              .append(child.membershipQid())
              .append("  (must be a ").append(lower(childName)).append(")\n");
        }

        sb.append(inner).append("• up to ").append(child.limit())
          .append(" per parent\n");

        explainBody(child, sb, inner, "each " + lower(childName));
    }

    // ------------------------------------------------------------------
    // Phrasing helpers
    // ------------------------------------------------------------------

    private static String membershipPhrase(RuleNode node) {
        String prop = labelPid(node.propertyLabel(), node.propertyPid());
        String src  = labelQid(node.sourceLabel(), node.sourceQid());
        if (prop.isBlank() && src.isBlank()) {
            return "(unspecified)";
        }
        return prop + " = " + src;
    }

    private static String directionPhrase(RuleNode child) {
        String prop = labelPid(child.propertyLabel(), child.propertyPid());
        String noun = lower(nodeName(child));

        return child.direction() == RuleDirection.ITEM_TO_ROOT
                ? "←  " + prop + "  (incoming: each " + noun
                        + " points back to the parent)"
                : "→  " + prop + "  (outgoing: the parent points to each "
                        + noun + ")";
    }

    private static String fieldDescription(RuleIncludedField f) {
        String base = switch (f.kind()) {
            case MEDIA  -> "image";
            case ENTITY -> "reference";
            case DATE   -> "date";
            case AUTO   -> "value";
        };
        String type = f.collection() ? "list of " + base + "s" : base;
        return type + ", " + (f.optional() ? "optional" : "required");
    }

    private static String filterPhrase(WikidataValueFilter vf) {
        String field = safe(vf.fieldName());
        String pid = safe(vf.propertyPid());
        String where = field.isBlank()
                ? pid
                : field + (pid.isBlank() ? "" : " (" + pid + ")");
        String op = vf.operator() == null ? "?" : vf.operator().sparql();
        String value = trimNumber(vf.numericValue());
        return where + " " + op + " " + value
                + (vf.required() ? "" : " (when present)");
    }

    // ------------------------------------------------------------------
    // Small utilities
    // ------------------------------------------------------------------

    private static String legend() {
        return """
               Legend:
                 Pxx = Wikidata property,  Qxx = Wikidata item
                 reference = a linked entity, not expanded here
                 ← incoming: the other entity holds the property
                 → outgoing: this entity holds the property
               """;
    }

    private static String nodeName(RuleNode node) {
        return node == null || node.name() == null || node.name().isBlank()
                ? "Node" : node.name();
    }

    private static String labelPid(String label, String pid) {
        return labelToken(label, pid);
    }

    private static String labelQid(String label, String qid) {
        return labelToken(label, qid);
    }

    private static String labelToken(String label, String id) {
        String l = safe(label);
        String i = safe(id);
        if (l.isBlank()) return i;
        if (i.isBlank()) return l;
        return l + " (" + i + ")";
    }

    private static String trimNumber(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d)
                ? Long.toString((long) d)
                : Double.toString(d);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String pad(String s, int width) {
        if (s == null) s = "";
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
