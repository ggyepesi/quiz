package domain.workbench.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The neutral shell's first visible structure is checked in before construct editors
 * adopt it. The first section exercises the four configuration-node meanings without
 * teaching them to the shell; the second is the existing action/result consumer.
 */
class TreeAndDetailLayoutIsCheckedInTest {

    private static final Path GOLDEN = Path.of("../docs/tree-and-detail-layout.txt");

    @Test void theNeutralTreeAndDetailShapeIsCheckedIn() throws Exception {
        StringBuilder actual = new StringBuilder();
        SwingUtilities.invokeAndWait(() -> {
            TreeAndDetailPanel<FixtureId> configuration = configurationFixture();
            actual.append("== Configuration-shaped neutral tree ==\n");
            describeFixture(actual, configuration.tree());
            configuration.select(new FixtureId(Kind.AVAILABLE_SLOT, "fallback"));
            actual.append("selected detail: ")
                    .append(visibleDetailText(configuration)).append("\n\n");

            WorkbenchActionResultNavigator actions = actionResultFixture();
            actual.append("== Existing action/result consumer ==\n");
            describeCaptions(actual, actions.tree());
        });

        String rendered = actual.toString().stripTrailing() + "\n";
        if (Boolean.getBoolean("tree.detail.layout.write")) {
            Files.writeString(GOLDEN, rendered);
        }
        String checkedIn = Files.isRegularFile(GOLDEN) ? Files.readString(GOLDEN) : "";
        if (!checkedIn.equals(rendered)) {
            Path seen = GOLDEN.resolveSibling("tree-and-detail-layout.actual.txt");
            Files.writeString(seen, rendered);
            assertEquals(checkedIn, rendered, "the neutral tree-and-detail rendering no "
                    + "longer matches " + GOLDEN + "; diff it against " + seen
                    + ", then re-run with -Dtree.detail.layout.write=true to accept");
        }
    }

    private static TreeAndDetailPanel<FixtureId> configurationFixture() {
        TreeAndDetailPanel<FixtureId> shell =
                new TreeAndDetailPanel<>("workbench.configuration", "Configuration");
        FixtureId field = new FixtureId(Kind.STORED_CONFIGURATION, "field");
        FixtureId produced = new FixtureId(Kind.STORED_CONFIGURATION, "produced");
        shell.addRoot(field, "DeityGroup.members", detail("Field editor"));
        shell.addChild(field, new FixtureId(Kind.STORED_CONFIGURATION, "definition"),
                "Definition", detail("Field definition editor"));
        shell.addChild(field, produced, "Produced by", detail("Source summary"));
        shell.addChild(produced, new FixtureId(Kind.STORED_CONFIGURATION, "primary"),
                "Primary: Wikidata relation routes (3)", detail("Primary source editor"));
        shell.addChild(produced, new FixtureId(Kind.AVAILABLE_SLOT, "fallback"),
                "+ Add fallback source", detail("Compatible fallback choices"));
        shell.addChild(field, new FixtureId(Kind.REFERENCE, "needs"),
                "Needs: DeityGroupMember", detail("Open DeityGroupMember"));
        shell.addChild(field, new FixtureId(Kind.INVENTORY_SUMMARY, "curation"),
                "Curation: 42 decisions", detail("Open curation inventory"));
        shell.expand(field);
        shell.expand(produced);
        return shell;
    }

    private static WorkbenchActionResultNavigator actionResultFixture() {
        WorkbenchActionResultNavigator navigator = new WorkbenchActionResultNavigator();
        var wikipedia = navigator.addSource("wikipedia", "Wikipedia", null);
        JPanel operation = detail("Fetch operation");
        var fetch = navigator.addAction(wikipedia, "fetch", "Fetch selected articles",
                operation, null);
        JTabbedPane results = new JTabbedPane();
        results.addTab("Articles — 2", new JLabel("articles"));
        results.addTab("Sections — 9", new JLabel("sections"));
        navigator.publishResults(fetch, results, 0);
        navigator.expandSources();
        return navigator;
    }

    private static void describeFixture(StringBuilder out, JTree tree) {
        walk(tree, (outLine, item, depth) -> {
            FixtureId id = (FixtureId) item.id();
            outLine.append("  ".repeat(depth)).append(id.kind().caption)
                    .append(": ").append(item.caption()).append('\n');
        }, out);
    }

    private static void describeCaptions(StringBuilder out, JTree tree) {
        walk(tree, (outLine, item, depth) -> outLine.append("  ".repeat(depth))
                .append(item.caption()).append('\n'), out);
    }

    private static void walk(JTree tree, Line line, StringBuilder out) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        for (int index = 0; index < root.getChildCount(); index++) {
            walk((DefaultMutableTreeNode) root.getChildAt(index), 0, line, out);
        }
    }

    private static void walk(DefaultMutableTreeNode node, int depth,
            Line line, StringBuilder out) {
        TreeAndDetailPanel.Item<?> item =
                (TreeAndDetailPanel.Item<?>) node.getUserObject();
        line.append(out, item, depth);
        for (int index = 0; index < node.getChildCount(); index++) {
            walk((DefaultMutableTreeNode) node.getChildAt(index), depth + 1, line, out);
        }
    }

    private static JPanel detail(String text) {
        JPanel panel = new JPanel();
        panel.add(new JLabel(text));
        return panel;
    }

    private static String visibleDetailText(TreeAndDetailPanel<?> shell) {
        JPanel visible = java.util.Arrays.stream(shell.detailHost().getComponents())
                .filter(java.awt.Component::isVisible).map(JPanel.class::cast)
                .findFirst().orElseThrow();
        return ((JLabel) visible.getComponent(0)).getText();
    }

    private enum Kind {
        STORED_CONFIGURATION("stored configuration"),
        AVAILABLE_SLOT("available slot"),
        REFERENCE("reference"),
        INVENTORY_SUMMARY("inventory summary");

        private final String caption;
        Kind(String caption) { this.caption = caption; }
    }

    private record FixtureId(Kind kind, String id) { }

    @FunctionalInterface
    private interface Line {
        void append(StringBuilder out, TreeAndDetailPanel.Item<?> item, int depth);
    }
}
