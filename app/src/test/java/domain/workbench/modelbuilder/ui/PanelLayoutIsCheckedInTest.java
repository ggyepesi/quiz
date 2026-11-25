package domain.workbench.modelbuilder.ui;

import org.junit.jupiter.api.Test;
import domain.DomainCapability;
import domain.DelegatingDomainModel;
import domain.config.AggregateClassSource;
import domain.config.ClassConfigurationProjection;
import domain.config.EntityBound;
import domain.config.FieldCardinality;
import domain.config.FieldConfigurationProjection;
import domain.config.FieldProductionKind;
import domain.datasource.schema.FieldType;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.GeneratedProjectModel;
import domain.config.StatementClassSource;
import domain.config.RuleDirection;
import domain.transform.ui.WorkbenchPurpose;
import domain.transform.ui.ProjectBacking;
import domain.workbench.configuration.ui.ProjectConfigurationPanel;
import domain.workbench.unified.ui.UnifiedWorkbenchPanel;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the four class editors put on screen, in order, checked in.
 *
 * <p>A change to a panel is a diff of {@code docs/panel-layout.txt} before it is
 * anything else. Nothing else in this suite can see the properties these editors are
 * designed around — which rows come first, whether a control is offered twice, whether
 * four kinds are laid out the same way — so a change to them used to be reported as done
 * on the strength of a green suite that could not perceive it. The panels drifted for a
 * day that way: the triple ended up seventh on one editor and last on another while
 * every test passed.
 *
 * <p>This is to the editors what {@code counts.tsv} is to generation: the artifact that
 * can see the change. Regenerate it by running with {@code -Dpanel.layout.write=true}
 * and READ the diff — the file is the agreement about what these panels look like.
 */
class PanelLayoutIsCheckedInTest {

    private static final Path GOLDEN = Path.of("../docs/panel-layout.txt");

    @Test void theEditorsLookLikeTheCheckedInLayout() throws Exception {
        StringBuilder actual = new StringBuilder();
        describeEditors(actual);
        Path golden = Files.isRegularFile(GOLDEN) ? GOLDEN : Path.of("docs/panel-layout.txt");
        if (Boolean.getBoolean("panel.layout.write")) {
            Files.writeString(golden, actual.toString().stripTrailing() + "\n");
        }
        // The artifact describes components and order; the number of blank lines after
        // its final component is not panel structure.
        String checkedIn = Files.readString(golden).stripTrailing();
        String rendered = actual.toString().stripTrailing();
        if (!checkedIn.equals(rendered)) {
            // The diff is the point, and an assertion message is not where anyone can
            // read a 300-line one.
            Path seen = golden.resolveSibling("panel-layout.actual.txt");
            Files.writeString(seen, actual.toString().stripTrailing() + "\n");
            assertEquals(checkedIn, rendered,
                    "the editors no longer look like " + golden + "; diff it against "
                            + seen + ", then re-run with -Dpanel.layout.write=true to "
                            + "accept the new layout");
        }
    }

    private static void describeEditors(StringBuilder actual) throws Exception {
        GeneratedProjectModel project = project();

        DomainOverviewPanel overview = new DomainOverviewPanel(project);
        describe(actual, "Project - DomainOverviewPanel", overview);

        ClassSourcePanel source = new ClassSourcePanel();
        source.setProjectModel(project);
        source.edit(project.findClass("Person"));
        describeClass(actual, "Source class", source, source.configurationEditor());

        StatementSourcePanel statement = new StatementSourcePanel();
        statement.setProjectModel(project);
        statement.edit(project.findClass("Award"));
        describeClass(actual, "Statement class", statement, statement.configurationEditor());

        OwnedClassPanel owned = new OwnedClassPanel(project);
        owned.edit(project.findClass("Name"));
        describeClass(actual, "Owned class", owned, owned.configurationEditor());

        AggregateClassPanel aggregate = new AggregateClassPanel(project);
        aggregate.edit(project.findClass("Prize"));
        describeClass(actual, "Aggregate class", aggregate, aggregate.configurationEditor());

        FieldSourcePanel field = new FieldSourcePanel();
        field.setProjectModel(project);
        field.edit(project.findClass("Person").fields().get(0));
        describe(actual, "Field - FieldSourcePanel", field);
        // The editor's rows live in one detail section per configuration node and only
        // the selected section is visible, so each section is rendered on its own.
        for (String section : List.of(
                domain.config.FieldConfigurationProjection.DEFINITION,
                domain.config.FieldConfigurationProjection.PRIMARY,
                domain.config.FieldConfigurationProjection.FALLBACK,
                domain.config.FieldConfigurationProjection.CATEGORY,
                domain.config.FieldConfigurationProjection.EVIDENCE,
                domain.config.FieldConfigurationProjection.REFINE)) {
            if (!field.selectConfigurationDetail(section)) {
                actual.append("== Field section ").append(section)
                        .append(" - no node offers it ==\n\n");
                continue;
            }
            describe(actual, "Field section " + section, field);
        }

        GeneratedProjectModel graphProject = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        GeneratedFieldModel jurisdiction = position.addField(
                "jurisdiction", FieldType.ENTITY, FieldCardinality.COLLECTION);
        jurisdiction.mapping().propertyPid("P1001");
        jurisdiction.mapping().propertyLabel("jurisdiction");
        jurisdiction.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        graphProject.rootClass(position);
        GeneratedClassModel relevance = new GeneratedClassModel("PositionRelevance");
        relevance.classKind(domain.config.ClassKind.GRAPH);
        graphProject.addClass(relevance);
        GraphConstraintsPanel graph = new GraphConstraintsPanel(graphProject);
        graph.edit(relevance);
        for (String section : List.of(
                domain.config.GraphConfigurationProjection.DEFINITION,
                domain.config.GraphConfigurationProjection.START,
                domain.config.GraphConfigurationProjection.PATH,
                domain.config.GraphConfigurationProjection.EDGE,
                domain.config.GraphConfigurationProjection.NODE,
                domain.config.GraphConfigurationProjection.EVIDENCE,
                domain.config.GraphConfigurationProjection.IDENTITY,
                domain.config.GraphConfigurationProjection.DISPLAY_NAME)) {
            if (!graph.selectConfigurationDetail(section)) {
                actual.append("== Graph section ").append(section)
                        .append(" - no node offers it ==\n\n");
                continue;
            }
            describe(actual, "Graph section " + section, graph);
        }

        var backing = ClassConfigurationTreeTest.transformationProject();
        var output = backing.projectModel().findClass("SelectedPosition");
        var transform = new domain.transform.ui.TransformController(
                new domain.runtime.SnapshotDomain(List.of()), null);
        describeConfigurationHost(actual, "TransformApp", backing, output, transform,
                WorkbenchPurpose.TRANSFORM);
        describeConfigurationHost(actual, "DataSourceApp", backing, output, transform,
                WorkbenchPurpose.DATASOURCE);
        backing.projectModel().rootClass().fields().getFirst().mapping().propertyPid("P1082");
        domain.config.FieldSourceBindings.synchronizeForSave(backing.projectModel());
        var opened = new DelegatingDomainModel(transform.domain()) {
            @Override public <T extends DomainCapability> T capability(Class<T> type) {
                return type == ProjectBacking.class ? type.cast(backing) : super.capability(type);
            }
        };
        SwingUtilities.invokeAndWait(() -> {
            try (var unified = new UnifiedWorkbenchPanel(opened)) {
                find(unified, SingleRootClassModelPanel.class)
                        .selectField(backing.projectModel().rootClass().fields().getFirst());
                var editor = find(unified, FieldSourcePanel.class);
                editor.selectConfigurationDetail(FieldConfigurationProjection.PRIMARY);
                describe(actual, "Unified Workbench / Field source and acquisition plan", unified);
                var tree = find(unified, domain.workbench.ui.WorkbenchActionResultNavigator.class).tree();
                var root = (javax.swing.tree.DefaultMutableTreeNode) tree.getModel().getRoot();
                for (var nodes = root.depthFirstEnumeration(); nodes.hasMoreElements();) {
                    var node = (javax.swing.tree.DefaultMutableTreeNode) nodes.nextElement();
                    if ("Load field values".equals(node.toString())) {
                        tree.setSelectionPath(new javax.swing.tree.TreePath(node.getPath()));
                        break;
                    }
                }
                describe(actual, "Unified Workbench / Load field values", unified);
                find(unified, SingleRootClassModelPanel.class).selectClass(output);
                find(unified, ClassSourcePanel.class).configurationEditor().tree()
                        .selectDetail(ClassConfigurationProjection.TRANSFORMATION);
                for (var nodes = root.depthFirstEnumeration(); nodes.hasMoreElements();) {
                    var node = (javax.swing.tree.DefaultMutableTreeNode) nodes.nextElement();
                    if ("Preview population".equals(node.toString())) {
                        tree.setSelectionPath(new javax.swing.tree.TreePath(node.getPath()));
                        break;
                    }
                }
                describe(actual, "Unified Workbench / Edit transformation and preview population", unified);
                for (var nodes = root.depthFirstEnumeration(); nodes.hasMoreElements();) {
                    var node = (javax.swing.tree.DefaultMutableTreeNode) nodes.nextElement();
                    if ("Build project".equals(node.toString())) {
                        tree.setSelectionPath(new javax.swing.tree.TreePath(node.getPath()));
                        break;
                    }
                }
                describe(actual, "Unified Workbench / Build project", unified);
                find(unified, SingleRootClassModelPanel.class).selectConstruct(backing.projectModel());
                describe(actual, "Unified Workbench / Edit build steps", unified);
                for (var nodes = root.depthFirstEnumeration(); nodes.hasMoreElements();) {
                    var node = (javax.swing.tree.DefaultMutableTreeNode) nodes.nextElement();
                    if ("Review graph decisions".equals(node.toString())) {
                        tree.setSelectionPath(new javax.swing.tree.TreePath(node.getPath()));
                        break;
                    }
                }
                describe(actual, "Unified Workbench / Review graph decisions", unified);
                for (var nodes = root.depthFirstEnumeration(); nodes.hasMoreElements();) {
                    var node = (javax.swing.tree.DefaultMutableTreeNode) nodes.nextElement();
                    if ("Run graph".equals(node.toString())) {
                        tree.setSelectionPath(new javax.swing.tree.TreePath(node.getPath()));
                        break;
                    }
                }
                describe(actual, "Unified Workbench / Run graph", unified);
            }
        });
    }

    // --- the walk ---

    private static void describeConfigurationHost(StringBuilder out, String host,
            ClassConfigurationTreeTest.FixtureProject backing, GeneratedClassModel output,
            domain.transform.ui.TransformController controller, WorkbenchPurpose purpose) {
        try (var configuration = new ProjectConfigurationPanel(
                backing, controller, purpose, List.of(), output, () -> { })) {
            var source = find(configuration, ClassSourcePanel.class);
            source.configurationEditor().tree().selectDetail(
                    domain.config.ClassConfigurationProjection.TRANSFORMATION);
            describe(out, host + " configuration / Population transformation", configuration);
            var navigator = find(configuration, SingleRootClassModelPanel.class);
            navigator.selectField(backing.projectModel().rootClass().fields().getFirst());
            describe(out, host + " configuration / Field definition", configuration);
        }
    }

    private static void describeClass(StringBuilder out, String title, Container panel,
            ClassConfigurationEditor editor) {
        var tree = editor.tree();
        for (var section : tree.nodes().getFirst().children()) {
            tree.select(section.id());
            describe(out, title + " / " + section.caption(), panel);
        }
    }

    private static void describe(StringBuilder out, String title, Container panel) {
        out.append("== ").append(title).append(" ==\n");
        walk(out, panel, 1);
        out.append('\n');
    }

    private static void walk(StringBuilder out, Container container, int depth) {
        for (Component child : container.getComponents()) {
            if (!child.isVisible()) continue;
            String line = describe(child);
            int next = depth;
            if (line != null) {
                out.append("  ".repeat(depth)).append(line).append('\n');
                next = depth + 1;
            }
            if (child instanceof Container nested) walk(out, nested, next);
        }
    }

    /** The assembled pieces, named — the thing the four editors are made OF. */
    private static final List<String> PIECES = List.of(
            "ClassHeaderEditor", "TripleEditor", "EntityEndEditor",
            "ClassIdentityEditor", "DisplayNameEditor", "OrderedChoiceList");

    private static String describe(Component component) {
        String piece = component.getClass().getSimpleName();
        if (PIECES.contains(piece)) {
            String box = titledBorder(component);
            return "piece: " + piece + (box == null ? "" : "  [" + box + "]");
        }
        String box = titledBorder(component);
        if (box != null) return "box: " + box;
        // An id editor paints its ids as rows; what it holds is its storage (#404).
        if (component instanceof TextBackedEditor backed) {
            return "ids: [" + plain(backed.storage().getText()) + "]"
                    + (backed.storage().isEditable() ? "" : "  (read-only)");
        }
        // Ids render as links reading "label (Q5)" (#404); the dump shows what a reader
        // sees. Offline there are no labels, so it shows the ids themselves.
        if (component instanceof wikidata.ui.WikidataIdText links) {
            String text = links.shownText().strip();
            return text.isBlank() ? null
                    : (links.followsField() ? "links: " : "label: ") + text;
        }
        if (component instanceof JLabel label) {
            String text = plain(label.getText());
            return text.isBlank() ? null : "label: " + text;
        }
        if (component instanceof AbstractButton button) {
            // A combo's own arrow is not a control anyone configures.
            String text = plain(button.getText());
            if (text.isBlank()) return null;
            return (button.getClass().getSimpleName().equals("JCheckBox")
                    ? "check: " : "button: ") + text
                    + (button.isEnabled() ? "" : "  (disabled)");
        }
        if (component instanceof JComboBox<?> combo) {
            return "combo: " + combo.getItemCount() + " item(s)"
                    + (combo.isEnabled() ? "" : "  (disabled)");
        }
        if (component instanceof JTextField field) {
            return "text: [" + plain(field.getText()) + "]"
                    + (field.isEnabled() ? "" : "  (disabled)");
        }
        if (component instanceof JTextArea area) {
            return "field.acquisition.plan".equals(area.getName())
                    ? "plan: " + plain(area.getText()).replaceAll("\\s+", " ")
                    : "textarea";
        }
        if (component instanceof JList<?>) return "list";
        return null;
    }

    private static String titledBorder(Component component) {
        if (!(component instanceof javax.swing.JComponent widget)) return null;
        if (widget.getBorder() instanceof TitledBorder titled) {
            String title = plain(titled.getTitle());
            return title.isBlank() ? null : title;
        }
        return null;
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found = find(nested, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Swing labels carry HTML; the layout is about the words, not the markup. */
    private static String plain(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]*>", " ").replaceAll("&mdash;", "-")
                .replaceAll("&rarr;", "->").replaceAll("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    // --- one class of every kind ---

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();

        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.membership(EntityBound.relation("P31", List.of("Q5"), false));
        GeneratedFieldModel birthDate = person.addField(
                "birthDate", FieldType.DATE, FieldCardinality.SINGLE);
        birthDate.mapping().propertyPid("P569");
        birthDate.mapping().propertyLabel("date of birth");
        birthDate.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        project.addClass(person);

        GeneratedClassModel award = new GeneratedClassModel("Award");
        StatementClassSource statement = new StatementClassSource("Person", "P166");
        statement.objectBound(EntityBound.explicit(List.of("Q35637")));
        award.statementSource(statement);
        award.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().productionKind(FieldProductionKind.STATEMENT_OBJECT);
        project.addClass(award);

        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        name.addField("given", FieldType.STRING, FieldCardinality.SINGLE);
        project.addClass(name);
        GeneratedFieldModel site = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        site.entityClassName("Name");
        site.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);

        GeneratedClassModel prize = new GeneratedClassModel("Prize");
        prize.aggregateSource(new AggregateClassSource("Award", "awards"));
        prize.addField("awards", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("Award");
        project.addClass(prize);

        project.rootClass(person);
        return project;
    }
}
