package domain.workbench.unified.ui;

import dataset.DomainStorage;
import domain.build.ProjectSave;
import domain.config.FieldCardinality;
import domain.config.FieldConfigurationProjection;
import domain.config.FieldSourceType;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.GeneratedProjectModel;
import domain.datasource.schema.FieldType;
import domain.runtime.WikidataDynamicObject;
import domain.transform.app.DomainCatalog;
import domain.transform.ui.ProjectBacking;
import domain.workbench.modelbuilder.ui.SingleRootClassModelPanel;
import domain.workbench.ui.ConfigurationTreePanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedWorkbenchPanelTest {
    @TempDir Path root;

    @Test void applyUpdatesThePlanInTheSameWindowBeforeSaveAndReopen() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = new GeneratedProjectModel();
        model.name("Egyptian Gods");
        var deity = new GeneratedClassModel("EgyptianDeity");
        deity.seedQids().add("Q47534");
        var field = deity.addField("symbol", FieldType.TEXT, FieldCardinality.SINGLE);
        field.mapping().sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        field.mapping().propertyPid("Rendered.Symbol");
        model.rootClass(deity);
        var anubis = new WikidataDynamicObject("Q47534", "Anubis");
        anubis.type("EgyptianDeity");
        anubis.enwikiTitle("Anubis");
        var run = new ProjectSave.Run(List.of(anubis), List.of(), null, model);
        ProjectSave.plan(new ProjectSave.Input(model, run, null, List.of()), storage).write();

        Path modelPath = storage.modelFile(model.name()).toPath();
        Path snapshotPath = storage.snapshotFile(model.name()).toPath();
        String savedModel = Files.readString(modelPath);
        String savedSnapshot = Files.readString(snapshotPath);
        var choice = DomainCatalog.modelBacked(storage).getFirst();
        var opened = choice.opener().open();
        var working = opened.capability(ProjectBacking.class).projectModel();
        assertSame(working.findClass("EgyptianDeity"), working.rootClass());
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(opened);
            panel.set(workbench);
            find(workbench, SingleRootClassModelPanel.class, unused -> true).selectField(symbol(working));
            var plan = find(workbench, JTextArea.class, area -> "field.acquisition.plan".equals(area.getName()));
            var save = find(workbench, JButton.class, button -> "Save domain…".equals(button.getText()));
            assertTrue(save.isEnabled(), "Save is a complete action and is offered whenever idle");
            assertTrue(plan.getText().contains("Rendered.Symbol"), plan.getText());

            var tree = find(workbench, ConfigurationTreePanel.class,
                    candidate -> candidate.nodes().stream().anyMatch(
                            node -> FieldConfigurationProjection.PRIMARY.equals(node.detailKey())));
            assertTrue(tree.selectDetail(FieldConfigurationProjection.PRIMARY));
            find(workbench, JTextField.class, input -> "field.source.property".equals(input.getName()))
                    .setText("Rendered.Consort");
            assertTrue(plan.getText().contains("Rendered.Symbol"), plan.getText());
            assertTrue(workbench.hasUnappliedEdits());
            var apply = find(workbench, JButton.class, button -> "Apply field source".equals(button.getText()));
            assertTrue(apply.isEnabled());
            apply.doClick();
            assertTrue(plan.getText().contains("Rendered.Consort"), plan.getText());
            assertFalse(plan.getText().contains("Rendered.Symbol"), plan.getText());
            assertTrue(save.isEnabled());
        });
        try {
            assertEquals(savedModel, Files.readString(modelPath));
            panel.get().saveProject();
            assertTrue(Files.readString(modelPath).contains("Rendered.Consort"));
            assertEquals(savedSnapshot, Files.readString(snapshotPath));

            var reopened = choice.opener().open();
            assertEquals(1, reopened.memberRoots().size());
            var persisted = symbol(reopened.capability(ProjectBacking.class).projectModel());
            assertEquals("Rendered.Consort", persisted.mapping().propertyPid());
            SwingUtilities.invokeAndWait(() -> {
                try (var workbench = new UnifiedWorkbenchPanel(reopened)) {
                    find(workbench, SingleRootClassModelPanel.class, unused -> true).selectField(persisted);
                    var plan = find(workbench, JTextArea.class,
                            area -> "field.acquisition.plan".equals(area.getName()));
                    assertTrue(plan.getText().contains("Rendered.Consort"), plan.getText());
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(panel.get()::close);
        }
    }

    /** An opened two-field project, with its saved model text for comparison. */
    private record Opened(domain.DomainModel domain, GeneratedProjectModel working,
            Path modelPath, String savedModel) { }

    private Opened open() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = new GeneratedProjectModel();
        model.name("Egyptian Gods");
        var deity = new GeneratedClassModel("EgyptianDeity");
        deity.seedQids().add("Q47534");
        var symbol = deity.addField("symbol", FieldType.TEXT, FieldCardinality.SINGLE);
        symbol.mapping().sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        symbol.mapping().propertyPid("Rendered.Symbol");
        var consort = deity.addField("consort", FieldType.TEXT, FieldCardinality.SINGLE);
        consort.mapping().sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        consort.mapping().propertyPid("Rendered.Consort");
        model.rootClass(deity);
        var anubis = new WikidataDynamicObject("Q47534", "Anubis");
        anubis.type("EgyptianDeity");
        var run = new ProjectSave.Run(List.of(anubis), List.of(), null, model);
        ProjectSave.plan(new ProjectSave.Input(model, run, null, List.of()), storage).write();
        Path modelPath = storage.modelFile(model.name()).toPath();
        var opened = DomainCatalog.modelBacked(storage).getFirst().opener().open();
        return new Opened(opened, opened.capability(ProjectBacking.class).projectModel(),
                modelPath, Files.readString(modelPath));
    }

    private static GeneratedFieldModel named(GeneratedProjectModel model, String name) {
        return model.rootClass().fields().stream().filter(field -> field.name().equals(name))
                .findFirst().orElseThrow();
    }

    /** Selects the field and types a new property into its primary source. */
    private static void edit(UnifiedWorkbenchPanel workbench, GeneratedFieldModel field, String pid) {
        find(workbench, SingleRootClassModelPanel.class, unused -> true).selectField(field);
        find(workbench, JTextField.class, input -> "field.source.property".equals(input.getName()))
                .setText(pid);
    }

    private static String property(UnifiedWorkbenchPanel workbench) {
        return find(workbench, JTextField.class,
                input -> "field.source.property".equals(input.getName())).getText();
    }

    @Test void leavingAFieldWithUnappliedEditsAsksApplyDiscardOrStay() throws Exception {
        for (var answer : process.swing.Dialogs.UnappliedEdits.values()) {
            var opened = open();
            var symbol = named(opened.working(), "symbol");
            var consort = named(opened.working(), "consort");
            SwingUtilities.invokeAndWait(() -> {
                try (var workbench = new UnifiedWorkbenchPanel(opened.domain())) {
                    List<String> asked = new java.util.ArrayList<>();
                    workbench.unappliedDecision((construct, action) -> {
                        asked.add(construct + " / " + action);
                        return answer;
                    });
                    edit(workbench, symbol, "Rendered.Parents");
                    find(workbench, SingleRootClassModelPanel.class, unused -> true).selectField(consort);

                    assertEquals(List.of("Field EgyptianDeity.symbol / opening another construct"),
                            asked, answer.name());
                    switch (answer) {
                        case STAY -> {
                            assertEquals("Rendered.Parents", property(workbench), "Stay keeps the edit");
                            assertEquals("Rendered.Symbol", symbol.mapping().propertyPid());
                        }
                        case DISCARD -> {
                            assertEquals("Rendered.Consort", property(workbench));
                            assertEquals("Rendered.Symbol", symbol.mapping().propertyPid());
                        }
                        case APPLY -> {
                            assertEquals("Rendered.Consort", property(workbench));
                            assertEquals("Rendered.Parents", symbol.mapping().propertyPid());
                        }
                    }
                }
            });
        }
    }

    @Test void saveWithOnlyUnappliedEditsResolvesTheDraftOnlyAfterTheFileIsConfirmed()
            throws Exception {
        var opened = open();
        var symbol = named(opened.working(), "symbol");
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(opened.domain());
            panel.set(workbench);
            workbench.showSaveSuccess(false);
            edit(workbench, symbol, "Rendered.Parents");
            var save = find(workbench, JButton.class, button -> "Save domain…".equals(button.getText()));
            assertTrue(save.isEnabled(), "a draft alone is a change Save can resolve");

            workbench.choice((title, message, options, defaultOption) -> {
                assertEquals(List.of("Apply and save", "Discard draft and save applied changes",
                        "Stay"), List.of(options));
                assertEquals(2, defaultOption);
                return defaultOption;
            });
            save.doClick();
            assertEquals("Rendered.Parents", property(workbench), "Stay keeps the draft");
            assertEquals("Rendered.Symbol", symbol.mapping().propertyPid());

            workbench.choice((title, message, options, defaultOption) -> 0);
            workbench.persistenceConfirmation(message -> false);
            save.doClick();
            assertEquals("Rendered.Parents", property(workbench),
                    "cancelling the file confirmation keeps the draft");
            assertEquals("Rendered.Symbol", symbol.mapping().propertyPid(), "and applies nothing");

            workbench.persistenceConfirmation(message -> {
                assertTrue(message.contains(opened.modelPath().toString()), message);
                return true;
            });
            save.doClick();
            assertEquals("Rendered.Parents", symbol.mapping().propertyPid());
        });
        awaitSaved(panel.get());
        assertTrue(Files.readString(opened.modelPath()).contains("Rendered.Parents"));
        SwingUtilities.invokeAndWait(panel.get()::close);

        var reopened = DomainCatalog.modelBacked(DomainStorage.in(root.toFile())).getFirst()
                .opener().open();
        assertEquals("Rendered.Parents", named(reopened.capability(ProjectBacking.class)
                .projectModel(), "symbol").mapping().propertyPid(), "reopening shows the edit");
    }

    @Test void discardingTheDraftSavesOnlyTheAppliedChanges() throws Exception {
        var opened = open();
        var symbol = named(opened.working(), "symbol");
        var consort = named(opened.working(), "consort");
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(opened.domain());
            panel.set(workbench);
            workbench.showSaveSuccess(false);
            workbench.persistenceConfirmation(message -> true);
            edit(workbench, consort, "Rendered.Spouse");
            find(workbench, JButton.class, button -> "Apply field source".equals(button.getText()))
                    .doClick();
            workbench.unappliedDecision((construct, action) -> process.swing.Dialogs.UnappliedEdits.STAY);
            edit(workbench, consort, "Rendered.Children");
            workbench.choice((title, message, options, defaultOption) -> 1);
            find(workbench, JButton.class, button -> "Save domain…".equals(button.getText())).doClick();
            assertEquals("Rendered.Spouse", property(workbench), "the draft is discarded");
        });
        awaitSaved(panel.get());
        String saved = Files.readString(opened.modelPath());
        assertTrue(saved.contains("Rendered.Spouse"), "the applied change is saved");
        assertFalse(saved.contains("Rendered.Children"));
        assertEquals("Rendered.Symbol", symbol.mapping().propertyPid());
        SwingUtilities.invokeAndWait(panel.get()::close);
    }

    @Test void closeOffersCompleteActionsAndStayIsTheDefault() throws Exception {
        // Stay: nothing applied, nothing closed.
        var stay = open();
        SwingUtilities.invokeAndWait(() -> {
            try (var workbench = new UnifiedWorkbenchPanel(stay.domain())) {
                edit(workbench, named(stay.working(), "symbol"), "Rendered.Parents");
                boolean[] closed = {false};
                workbench.choice((title, message, options, defaultOption) -> {
                    assertEquals(List.of("Apply, save and close", "Discard changes and close", "Stay"),
                            List.of(options));
                    assertEquals(2, defaultOption);
                    return defaultOption;
                });
                workbench.requestClose(() -> closed[0] = true);
                assertFalse(closed[0]);
                assertEquals("Rendered.Parents", property(workbench));
            }
        });
        assertEquals(stay.savedModel(), Files.readString(stay.modelPath()));

        // Discard: closes once, writes nothing.
        var discard = open();
        SwingUtilities.invokeAndWait(() -> {
            try (var workbench = new UnifiedWorkbenchPanel(discard.domain())) {
                edit(workbench, named(discard.working(), "symbol"), "Rendered.Parents");
                int[] asked = {0};
                boolean[] closed = {false};
                workbench.choice((title, message, options, defaultOption) -> {
                    asked[0]++;
                    return 1;
                });
                workbench.requestClose(() -> closed[0] = true);
                assertTrue(closed[0]);
                assertEquals(1, asked[0], "one decision, not Apply and then discard");
            }
        });
        assertEquals(discard.savedModel(), Files.readString(discard.modelPath()));

        // Apply, save and close.
        var apply = open();
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        boolean[] closed = {false};
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(apply.domain());
            panel.set(workbench);
            edit(workbench, named(apply.working(), "symbol"), "Rendered.Parents");
            workbench.choice((title, message, options, defaultOption) -> 0);
            workbench.requestClose(() -> closed[0] = true);
        });
        awaitSaved(panel.get());
        assertTrue(closed[0]);
        assertTrue(Files.readString(apply.modelPath()).contains("Rendered.Parents"));
        SwingUtilities.invokeAndWait(panel.get()::close);

        // Only applied, unsaved changes: the same shape of question.
        var applied = open();
        SwingUtilities.invokeAndWait(() -> {
            try (var workbench = new UnifiedWorkbenchPanel(applied.domain())) {
                edit(workbench, named(applied.working(), "symbol"), "Rendered.Parents");
                find(workbench, JButton.class, button -> "Apply field source".equals(button.getText()))
                        .doClick();
                workbench.choice((title, message, options, defaultOption) -> {
                    assertEquals(List.of("Save and close", "Discard changes and close", "Stay"),
                            List.of(options));
                    return 2;
                });
                workbench.requestClose(() -> { throw new AssertionError("Stay closed the window"); });
            }
        });
    }

    /** Close resolves a draft and applied unsaved changes together; when the write
     *  fails (here: another writer changed the file), the workbench stays open and the
     *  applied changes stay in memory. */
    @Test void aFailedSaveDuringCloseKeepsTheWorkbenchOpen() throws Exception {
        var opened = open();
        var symbol = named(opened.working(), "symbol");
        var consort = named(opened.working(), "consort");
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        List<String> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        boolean[] closed = {false};
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(opened.domain());
            panel.set(workbench);
            workbench.saveFailure(failures::add);
            edit(workbench, consort, "Rendered.Spouse");
            find(workbench, JButton.class, button -> "Apply field source".equals(button.getText()))
                    .doClick();
            workbench.unappliedDecision((construct, action) -> process.swing.Dialogs.UnappliedEdits.STAY);
            edit(workbench, consort, "Rendered.Children");
        });
        Files.writeString(opened.modelPath(), opened.savedModel() + " ");
        SwingUtilities.invokeAndWait(() -> {
            panel.get().choice((title, message, options, defaultOption) -> {
                assertTrue(message.contains("applied changes are not saved"), message);
                return 0;
            });
            panel.get().requestClose(() -> closed[0] = true);
        });
        awaitSaved(panel.get());
        assertFalse(closed[0], "a failed save keeps the workbench open");
        assertEquals(1, failures.size(), failures.toString());
        assertEquals("Rendered.Children", consort.mapping().propertyPid(),
                "the draft was applied and stays in memory");
        assertEquals("Rendered.Symbol", symbol.mapping().propertyPid());
        SwingUtilities.invokeAndWait(panel.get()::close);
    }

    @Test void aRefusedApplyKeepsTheWindowAndTheEdits() throws Exception {
        var opened = open();
        var symbol = named(opened.working(), "symbol");
        SwingUtilities.invokeAndWait(() -> {
            try (var workbench = new UnifiedWorkbenchPanel(opened.domain())) {
                find(workbench, SingleRootClassModelPanel.class, unused -> true).selectField(symbol);
                // A datasource operation that is not chosen cannot be applied.
                @SuppressWarnings("unchecked")
                JComboBox<Object> from = find(workbench, JComboBox.class, combo -> {
                    for (int i = 0; i < combo.getItemCount(); i++) {
                        if (combo.getItemAt(i) == FieldSourceType.DATASOURCE) return true;
                    }
                    return false;
                });
                from.setSelectedItem(FieldSourceType.DATASOURCE);
                assertTrue(workbench.hasUnappliedEdits());
                boolean[] closed = {false};
                workbench.unappliedDecision((construct, action) ->
                        process.swing.Dialogs.UnappliedEdits.APPLY);
                find(workbench, SingleRootClassModelPanel.class, unused -> true)
                        .selectField(named(opened.working(), "consort"));
                assertEquals(FieldSourceType.DATASOURCE, from.getSelectedItem(),
                        "a refused Apply keeps the selection and the draft");

                workbench.choice((title, message, options, defaultOption) -> 0);
                workbench.persistenceConfirmation(message -> true);
                find(workbench, JButton.class, button -> "Save domain…".equals(button.getText()))
                        .doClick();
                assertFalse(workbench.saving(), "a refused Apply saves nothing");
                assertEquals(FieldSourceType.DATASOURCE, from.getSelectedItem());

                workbench.requestClose(() -> closed[0] = true);
                assertFalse(closed[0], "a refused Apply keeps the window open");
                assertEquals(FieldSourceType.DATASOURCE, from.getSelectedItem(), "and the edit");
                assertEquals(FieldSourceType.WIKIPEDIA_INFOBOX, symbol.mapping().sourceType());
            }
        });
        assertEquals(opened.savedModel(), Files.readString(opened.modelPath()));
    }

    private static void awaitSaved(UnifiedWorkbenchPanel workbench) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (true) {
            boolean[] busy = {true};
            SwingUtilities.invokeAndWait(() -> busy[0] = workbench.saving());
            if (!busy[0]) return;
            if (System.currentTimeMillis() > deadline) throw new AssertionError("save did not finish");
            Thread.sleep(50);
        }
    }

    private static GeneratedFieldModel symbol(GeneratedProjectModel model) {
        return model.rootClass().fields().stream().filter(field -> field.name().equals("symbol"))
                .findFirst().orElseThrow();
    }

    private static <T extends Component> T find(Component root, Class<T> type, Predicate<T> match) {
        return descendants(root).filter(type::isInstance).map(type::cast).filter(match)
                .findFirst().orElseThrow(() -> new AssertionError("Missing " + type.getSimpleName()));
    }

    private static Stream<Component> descendants(Component component) {
        return component instanceof Container container
                ? Stream.concat(Stream.of(component),
                        Arrays.stream(container.getComponents()).flatMap(UnifiedWorkbenchPanelTest::descendants))
                : Stream.of(component);
    }
}
