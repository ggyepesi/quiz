package quiz.transform.pipeline.ui;

import flag.State;
import org.junit.jupiter.api.Test;
import quiz.curation.ScopeFilter;
import domain.DomainField;
import quiz.transform.ui.ReflectionDomain;
import quiz.transform.ui.TransformController;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ViewStepsPanelSelectionTest {

    @Test
    void exhaustiveDecisionValuesCannotBeTypedOutsideTheOfferedSelection() {
        wikidata.explore.extract.WikidataDynamicObject value =
                new wikidata.explore.extract.WikidataDynamicObject("Q1", "One");
        value.type("GraphDiscoveryResult");
        value.put("Decision", "Accepted");
        wikidata.explore.extract.SnapshotFieldGraph graph =
                wikidata.explore.extract.SnapshotFieldGraph.derive(List.of(value));
        graph.declareExhaustiveValues("GraphDiscoveryResult", "Decision",
                List.of("Start", "Accepted", "Review", "Rejected"));
        TransformController controller = new TransformController(
                new quiz.transform.app.SnapshotDomain(List.of(value), graph), null);
        ViewStepsPanel panel = new ViewStepsPanel(
                controller, () -> { }, null, List::of,
                (field, filter) -> { }, () -> { });

        panel.selectField("Decision", ScopeFilter.ALL);

        javax.swing.JComboBox<?> choices =
                findNamed(panel, "filter.value", javax.swing.JComboBox.class);
        assertNotNull(choices);
        assertFalse(choices.isEditable());
        assertEquals(List.of("Start", "Accepted", "Review", "Rejected"),
                java.util.stream.IntStream.range(0, choices.getItemCount())
                        .mapToObj(choices::getItemAt).toList());
    }

    @Test
    void exhaustiveDecisionValuesRemainNonEditableAfterSnapshotSaveAndLoad(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        wikidata.explore.extract.WikidataDynamicObject value =
                new wikidata.explore.extract.WikidataDynamicObject("Q1", "One");
        value.type("GraphDiscoveryResult");
        value.put("Decision", "Accepted");
        wikidata.explore.extract.SnapshotFieldGraph declared =
                wikidata.explore.extract.SnapshotFieldGraph.derive(List.of(value));
        declared.declareExhaustiveValues("GraphDiscoveryResult", "Decision",
                List.of("Start", "Accepted", "Review", "Rejected"));
        var source = new quiz.transform.app.SnapshotDomain(List.of(value), declared);
        java.io.File file = directory.resolve("graph-result.snapshot.json").toFile();
        new wikidata.explore.extract.WikidataDynamicObjectJsonStore()
                .saveWithFieldGraph(List.of(value), file, source);
        var loaded = new wikidata.explore.extract.WikidataDynamicObjectJsonStore()
                .loadAllWithFieldGraph(file);
        TransformController controller = new TransformController(
                new quiz.transform.app.SnapshotDomain(loaded.objects(), loaded.fieldGraph()),
                null);
        ViewStepsPanel panel = new ViewStepsPanel(
                controller, () -> { }, null, List::of,
                (field, filter) -> { }, () -> { });

        panel.selectField("Decision", ScopeFilter.ALL);

        javax.swing.JComboBox<?> choices =
                findNamed(panel, "filter.value", javax.swing.JComboBox.class);
        assertNotNull(choices);
        assertFalse(choices.isEditable());
        assertEquals(List.of("Start", "Accepted", "Review", "Rejected"),
                java.util.stream.IntStream.range(0, choices.getItemCount())
                        .mapToObj(choices::getItemAt).toList());
    }

    @Test
    void addFilterGroupHasItsOwnVisibleRowAtTheTransformLeftPaneWidth() {
        TransformController controller = new TransformController(
                new ReflectionDomain(List.of(new State("France"))), null);
        ViewStepsPanel panel = new ViewStepsPanel(
                controller, () -> { }, (name, condition) -> { }, List::of,
                (field, filter) -> { }, () -> { });
        panel.setSize(560, 760);
        layoutTree(panel);

        javax.swing.JButton add = findNamed(panel, "filter.addGroup", javax.swing.JButton.class);
        assertNotNull(add);
        assertTrue(add.getWidth() > 0 && add.getHeight() > 0,
                "Add filter group must have visible allocated bounds");
        java.awt.Component child = add;
        while (child.getParent() != null && child != panel) {
            java.awt.Container parent = child.getParent();
            assertTrue(child.getX() >= 0 && child.getY() >= 0
                            && child.getX() + child.getWidth() <= parent.getWidth()
                            && child.getY() + child.getHeight() <= parent.getHeight(),
                    "Add filter group is clipped by " + parent.getClass().getSimpleName());
            child = parent;
        }
    }

    @Test
    void manuallyTypedFilterGroupNameIsUsedExactly() {
        TransformController controller = new TransformController(
                new ReflectionDomain(List.of(new State("France"))), null);
        AtomicReference<String> createdName = new AtomicReference<>();
        AtomicReference<FilterCondition> createdCondition = new AtomicReference<>();
        ViewStepsPanel panel = new ViewStepsPanel(
                controller, () -> { }, (name, condition) -> {
                    createdName.set(name);
                    createdCondition.set(condition);
                }, List::of, (field, filter) -> { }, () -> { });
        FilterCondition condition = new FilterCondition(
                new DomainField("State", "capitals", false, false),
                FilterOperator.IS_NOT_EMPTY, null, null);

        panel.createFilterGroup("  My exact group name  ", condition);

        assertEquals("My exact group name", createdName.get());
        assertEquals(condition, createdCondition.get());
    }

    @Test
    void selectingAFieldIsReportedWhileValueScopeRemainsAll() {
        TransformController controller = new TransformController(
                new ReflectionDomain(List.of(new State("France"))), null);
        AtomicReference<DomainField> selected = new AtomicReference<>();
        AtomicReference<ScopeFilter> scope = new AtomicReference<>();
        ViewStepsPanel panel = new ViewStepsPanel(
                controller, () -> { }, null, List::of,
                (field, filter) -> {
                    selected.set(field);
                    scope.set(filter);
                },
                () -> { });

        selected.set(null);
        scope.set(null);
        panel.selectField("capitals", ScopeFilter.ALL);

        assertNotNull(selected.get());
        assertEquals("capitals", selected.get().field());
        assertEquals(ScopeFilter.ALL, scope.get());
    }

    private static void layoutTree(java.awt.Container root) {
        root.doLayout();
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof java.awt.Container nested) layoutTree(nested);
        }
    }

    private static <T extends java.awt.Component> T findNamed(
            java.awt.Container root, String name, Class<T> type) {
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) {
                return type.cast(child);
            }
            if (child instanceof java.awt.Container nested) {
                T found = findNamed(nested, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
