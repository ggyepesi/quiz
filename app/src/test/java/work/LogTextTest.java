package work;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Serializing a log tree to fully-expanded text (same package: addStep/complete
 *  /appendRequest are package-private). */
class LogTextTest {

    @Test
    void serializesTitlesSummariesRequestsAndNestingFullyExpanded() {
        LogNode root = new LogNode(LogKind.WORKFLOW, "Generate domain");
        LogNode backbone = new LogNode(LogKind.QUERY, "Root membership (backbone 1/6)");
        backbone.appendRequest("SELECT ?value ?root WHERE {\n  VALUES ?root { wd:Q1 }\n}");
        backbone.complete(LogStatus.OK, "2000 objects", null);
        LogNode failed = new LogNode(LogKind.QUERY, "wbgetentities 3/224");
        failed.complete(LogStatus.FAILED, null, "timed out");
        root.addStep(backbone);
        root.addStep(failed);
        root.complete(LogStatus.OK, "11176 members across 58 roots", null);

        String text = LogText.toText(List.of(root));

        // Titles + summaries + status.
        assertTrue(text.contains("Generate domain  [OK]  11176 members across 58 roots"), text);
        assertTrue(text.contains("Root membership (backbone 1/6)  [OK]  2000 objects"), text);
        // Request is included (fully expanded) and indented under its node.
        assertTrue(text.contains("    | SELECT ?value ?root WHERE {"), text);
        assertTrue(text.contains("    |   VALUES ?root { wd:Q1 }"), text);
        // Error surfaced.
        assertTrue(text.contains("timed out"), text);
        // Nesting: children are indented deeper than the root.
        assertTrue(text.contains("\n  Root membership"), "child indented under root:\n" + text);
    }
}
