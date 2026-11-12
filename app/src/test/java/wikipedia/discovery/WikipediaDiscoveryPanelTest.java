package wikipedia.discovery;

import domain.DomainModel;
import objectview.Viewable;
import objectview.render.GroupMembersView;
import objectview.view.SearchableView;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Selection must immediately make the action that consumes it available. */
class WikipediaDiscoveryPanelTest {

    @Test void transformSelectionOpensInTheSameWorkbenchWithoutFetchingOrResampling()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = new WikidataDynamicObject("Q170471", "Maat");
            maat.type("EgyptianDeity");
            maat.enwikiTitle("Maat");
            WikidataDynamicObject ra = new WikidataDynamicObject("Q2831", "Ra");
            ra.type("EgyptianDeity");
            ra.enwikiTitle("Ra");
            var initial = new WikipediaDiscoveryPanel.InitialSelection(
                    "EgyptianDeity", List.of(maat, ra),
                    "TransformApp — symbol missing");
            WikipediaDiscoveryPanel panel = new WikipediaDiscoveryPanel(
                    domain(List.of(maat, ra), List.of("EgyptianDeity")), initial);
            try {
                JTabbedPane sources = field(field(panel, "selection", Object.class), "tabs", JTabbedPane.class);
                SearchableView selected = field(field(panel, "selection", Object.class), "selectedView", SearchableView.class);
                JButton fetch = field(panel, "fetch", JButton.class);
                JLabel status = field(panel, "sourceStatus", JLabel.class);

                assertEquals(1, sources.getSelectedIndex(),
                        "the handed-over selection, not the whole class, is in front");
                assertEquals(List.of(maat, ra),
                        selected.cardList().getVirtualList().items());
                assertEquals("Fetch 2 selected articles", fetch.getText());
                assertTrue(fetch.isEnabled());
                assertTrue(status.getText().contains("Received exactly 2 selected"));
                assertTrue(status.getText().contains("TransformApp — symbol missing"));
                assertTrue(status.getText().contains("No article has been fetched"));
            } finally {
                panel.close();
            }
        });
    }

    @Test void infoboxDiscoveryIsVisibleBeforeTheFirstFetch() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = new WikidataDynamicObject("Q170471", "Maat");
            maat.type("EgyptianDeity");
            maat.enwikiTitle("Maat");
            WikipediaDiscoveryPanel panel = new WikipediaDiscoveryPanel(domain(maat));
            try {
                JTabbedPane results = field(panel, "resultTabs", JTabbedPane.class);
                assertEquals(List.of("Structural groups — 0", "Articles — 0", "Sections — 0",
                                "Section contents — 0", "List items — 0",
                                "Table rows — 0", "Table headers — 0",
                                "Infobox values — 0",
                                "Categories — 0", "Links by section — 0", "HTML elements — 0"),
                        java.util.stream.IntStream.range(0, results.getTabCount())
                                .mapToObj(results::getTitleAt).toList());
            } finally {
                panel.close();
            }
        });
    }

    @Test void selectingAnInstanceEnablesAddingItAndBuildsTheFirstSelectedCard()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = new WikidataDynamicObject("Q170471", "Maat");
            maat.type("EgyptianDeity");
            maat.enwikiTitle("Maat");
            WikipediaDiscoveryPanel panel = new WikipediaDiscoveryPanel(domain(maat));
            try {
                SearchableView instances = field(field(panel, "selection", Object.class), "instancesView", SearchableView.class);
                JButton add = field(field(panel, "selection", Object.class), "addSelection", JButton.class);

                assertFalse(add.isEnabled());
                instances.renderContext().select(maat);
                assertTrue(add.isEnabled(),
                        "the action consuming the selected card must become available");

                add.doClick();

                SearchableView selected = field(field(panel, "selection", Object.class), "selectedView", SearchableView.class);
                assertNotNull(selected.cardList(),
                        "the first addition must create the initially empty cards panel");
                assertEquals(List.of(maat),
                        selected.cardList().getVirtualList().items());

                JButton remove = field(field(panel, "selection", Object.class), "removeSelection", JButton.class);
                assertFalse(remove.isEnabled());
                selected.renderContext().select(maat);
                assertTrue(remove.isEnabled(),
                        "the action consuming a selected sample card must become available");
            } finally {
                panel.close();
            }
        });
    }

    @Test void selectingOneInfoboxResultEnablesExplicitConfiguration() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = new WikidataDynamicObject("Q170471", "Maat");
            maat.type("EgyptianDeity");
            maat.enwikiTitle("Maat");
            BackedDomain domain = new BackedDomain(maat);
            WikipediaDiscoveryPanel panel = new WikipediaDiscoveryPanel(domain);
            try {
                var observed = new WikipediaDiscoveryRows.InfoboxPattern(
                        "Rendered.Parents", 8, 12, List.of());
                var result = new WikipediaStructureAnalysis.Result(
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(observed), List.of(), List.of(), List.of(),
                        false, List.of(), 12, 12);
                invoke(panel, "showAnalysis",
                        new Class<?>[]{WikipediaStructureAnalysis.Result.class, String.class},
                        result, "test");

                JTabbedPane tabs = field(panel, "resultTabs", JTabbedPane.class);
                tabs.setSelectedIndex(7);
                SearchableView infoboxes = (SearchableView) tabs.getComponentAt(7);
                JButton configure = field(panel, "configureInfobox", JButton.class);
                assertFalse(configure.isEnabled());

                infoboxes.renderContext().select(observed);

                assertTrue(configure.isEnabled(),
                        "selecting one observed value must enable the action that configures it");
            } finally {
                panel.close();
            }
        });
    }

    @Test void configurationPreviewNamesTheRuleCoverageSlotAndSaveFile() {
        var observed = new WikipediaDiscoveryRows.InfoboxPattern(
                "Rendered.Parents", 8, 12, List.of());
        var field = new wikidata.explore.model.GeneratedFieldModel(
                "parents", datasource.schema.FieldType.ENTITY,
                wikidata.explore.model.FieldCardinality.COLLECTION);
        var target = new WikipediaDiscoveryPanel.FieldChoice("EgyptianDeity", field);
        java.io.File model = new java.io.File("data/wikidata/mythology/mythology.model.json");
        var promotion = new quiz.curation.FieldRulePromoter.PromotionPreview(
                true, "Add fallback source", model.getPath(), "EgyptianDeity", "parents",
                "List<Object>", "Wikipedia infobox fallback", "", "Rendered.Parents",
                false, "");

        String text = WikipediaDiscoveryPanel.configurationDescription(
                observed, target, WikipediaDiscoveryPanel.SourcePlace.FALLBACK,
                promotion, model);

        assertTrue(text.contains("EgyptianDeity.parents"), text);
        assertTrue(text.contains("Rendered.Parents"), text);
        assertTrue(text.contains("Fallback source"), text);
        assertTrue(text.contains("Add fallback source"), text);
        assertTrue(text.contains("8 of 12"), text);
        assertTrue(text.contains(model.getPath()), text);
        assertTrue(text.contains("does not write a file"), text);
    }

    @Test void targetFieldsKeepTheClassThatActuallyOwnsAnInheritedDeclaration() {
        var model = new wikidata.explore.model.GeneratedProjectModel();
        var entity = new wikidata.explore.model.GeneratedClassModel("MythologicalEntity");
        entity.addField("parents", datasource.schema.FieldType.ENTITY,
                wikidata.explore.model.FieldCardinality.COLLECTION);
        var deity = new wikidata.explore.model.GeneratedClassModel("EgyptianDeity");
        deity.baseClassName("MythologicalEntity");
        deity.addField("symbol", datasource.schema.FieldType.STRING,
                wikidata.explore.model.FieldCardinality.SINGLE);
        model.rootClass(entity);
        model.addClass(deity);

        List<WikipediaDiscoveryPanel.FieldChoice> choices =
                WikipediaDiscoveryPanel.declaredFieldChoices(model, "EgyptianDeity");

        assertEquals(List.of("EgyptianDeity.symbol — String",
                        "MythologicalEntity.parents — List<Object>"),
                choices.stream().map(Object::toString).toList());
    }

    @Test void importedFieldConfigurationIsNotOfferedToTheImporter() {
        var model = new wikidata.explore.model.GeneratedProjectModel();
        var imported = new wikidata.explore.model.GeneratedClassModel("CelestialBody");
        imported.importedFrom("Astronomy");
        imported.addField("image", datasource.schema.FieldType.IMAGE,
                wikidata.explore.model.FieldCardinality.SINGLE);
        var planet = new wikidata.explore.model.GeneratedClassModel("Planet");
        planet.baseClassName("CelestialBody");
        planet.addField("parentBody", datasource.schema.FieldType.ENTITY,
                wikidata.explore.model.FieldCardinality.SINGLE);
        model.rootClass(planet);
        model.addClass(imported);

        List<WikipediaDiscoveryPanel.FieldChoice> choices =
                WikipediaDiscoveryPanel.declaredFieldChoices(model, "Planet");

        assertEquals(List.of("Planet.parentBody — Object"),
                choices.stream().map(Object::toString).toList(),
                "an imported class stays editable only in its owning model");
    }

    @Test void targetChooserUsesThePromotersEligibilityDecision() {
        var model = new wikidata.explore.model.GeneratedProjectModel();
        var deity = new wikidata.explore.model.GeneratedClassModel("EgyptianDeity");
        deity.addField("summary", datasource.schema.FieldType.TEXT,
                wikidata.explore.model.FieldCardinality.SINGLE);
        deity.addField("parents", datasource.schema.FieldType.ENTITY,
                wikidata.explore.model.FieldCardinality.COLLECTION);
        model.rootClass(deity);
        var observed = new WikipediaDiscoveryRows.InfoboxPattern(
                "Rendered.Symbol", 8, 12, List.of());
        quiz.curation.FieldRulePromoter promoter = promoterForField("summary");

        List<WikipediaDiscoveryPanel.FieldChoice> choices =
                WikipediaDiscoveryPanel.eligibleFieldChoices(
                        model, "EgyptianDeity", observed, promoter);

        assertEquals(List.of("EgyptianDeity.summary — Text"),
                choices.stream().map(Object::toString).toList(),
                "the chooser must not maintain a broader eligibility rule than promotion");
    }

    @Test void changingClassInvalidatesResultsProducedForThePreviousClass()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = new WikidataDynamicObject("Q170471", "Maat");
            maat.type("EgyptianDeity");
            maat.enwikiTitle("Maat");
            WikidataDynamicObject achilles = new WikidataDynamicObject("Q41746", "Achilles");
            achilles.type("Character");
            achilles.enwikiTitle("Achilles");
            WikipediaDiscoveryPanel panel = new WikipediaDiscoveryPanel(
                    domain(List.of(maat, achilles), List.of("EgyptianDeity", "Character")));
            try {
                var observed = new WikipediaDiscoveryRows.InfoboxPattern(
                        "Rendered.Parents", 8, 12, List.of());
                var result = new WikipediaStructureAnalysis.Result(
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(observed), List.of(), List.of(), List.of(),
                        false, List.of(), 12, 12);
                invoke(panel, "showAnalysis",
                        new Class<?>[]{WikipediaStructureAnalysis.Result.class, String.class},
                        result, "test");
                JTabbedPane tabs = field(panel, "resultTabs", JTabbedPane.class);
                tabs.setSelectedIndex(7);
                ((SearchableView) tabs.getComponentAt(7)).renderContext().select(observed);
                assertNotNull(field(panel, "selectedInfobox", Object.class));

                JComboBox<?> types = field(field(panel, "selection", Object.class), "type", JComboBox.class);
                types.setSelectedItem("Character");

                assertEquals(null, field(panel, "selectedInfobox", Object.class));
                assertEquals("Infobox values — 0", tabs.getTitleAt(7));
            } finally {
                panel.close();
            }
        });
    }

    @Test void selectingAStructuralPatternShowsItsOriginalInstancesAsAReadOnlyGroup()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = new WikidataDynamicObject("Q170471", "Maat");
            maat.type("EgyptianDeity");
            maat.enwikiTitle("Maat");
            WikidataDynamicObject ra = new WikidataDynamicObject("Q2831", "Ra");
            ra.type("EgyptianDeity");
            ra.enwikiTitle("Ra");
            WikipediaDiscoveryPanel panel = new WikipediaDiscoveryPanel(
                    domain(List.of(maat, ra), List.of("EgyptianDeity")));
            try {
                var animals = new WikipediaDiscoveryRows.SectionPattern(
                        "Animals", 2, 2, 2, "3",
                        List.of(new WikipediaDiscoveryRows.SectionExample(
                                "Maat", "Iconography › Animals", 3, List.of(), 1)),
                        List.of(maat, ra));
                var cow = new WikipediaDiscoveryRows.ListItemContent(
                        "Maat", "Iconography › Animals", "Ordered list", 1,
                        new datasource.evidence.WikipediaArticle.ListItem(
                                "Item 1", "Cow", List.of(), List.of(), 2, 5), maat);
                var result = new WikipediaStructureAnalysis.Result(
                        List.of(), List.of(animals), List.of(), List.of(cow), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        false, List.of(), 2, 2);
                invoke(panel, "showAnalysis",
                        new Class<?>[]{WikipediaStructureAnalysis.Result.class, String.class},
                        result, "test");

                JTabbedPane tabs = field(panel, "resultTabs", JTabbedPane.class);
                SearchableView sections = (SearchableView) tabs.getComponentAt(2);
                sections.renderContext().select(animals);

                GroupMembersView groups = field(panel, "structuralGroupsView",
                        GroupMembersView.class);
                var matching = groups.groups().selectedGroup();
                assertEquals(List.of(maat, ra),
                        List.copyOf(matching.getMembers()));
                assertEquals("Animals", matching.getDisplayName());
                assertEquals("Iconography", matching.getParent().getDisplayName(),
                        "full section paths must become an actual group hierarchy");
                assertEquals(groups, tabs.getComponentAt(0),
                        "structural groups must be a visible result tab");
                JLabel status = field(panel, "resultStatus", JLabel.class);
                assertTrue(status.getText().contains("Open Structural groups"));
                assertTrue(status.getText().contains("read-only group is not saved"));

                SearchableView items = (SearchableView) tabs.getComponentAt(4);
                items.renderContext().select(cow);
                var itemGroup = groups.groups().selectedGroup();
                assertEquals("Cow", itemGroup.getDisplayName());
                assertEquals("Ordered list — 1 item (Maat)",
                        itemGroup.getParent().getDisplayName());
                assertEquals("Animals",
                        itemGroup.getParent().getParent().getDisplayName(),
                        "recognized list structure must remain under its section");
            } finally {
                panel.close();
            }
        });
    }

    /** Section paths are shared across articles, but a list belongs to one article: two
     *  equally long lists under the same path once merged into one node claiming that
     *  length. A nested item sits under the item the retained hierarchy names, not under
     *  one recovered by reparsing its "Item 1.1" label. */
    @Test void listsOfDifferentArticlesStaySeparateAndNestedItemsFollowTheirParent()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = new WikidataDynamicObject("Q170471", "Maat");
            maat.type("EgyptianDeity");
            maat.enwikiTitle("Maat");
            WikidataDynamicObject ra = new WikidataDynamicObject("Q2831", "Ra");
            ra.type("EgyptianDeity");
            ra.enwikiTitle("Ra");
            WikipediaDiscoveryPanel panel = new WikipediaDiscoveryPanel(
                    domain(List.of(maat, ra), List.of("EgyptianDeity")));
            try {
                String path = "Iconography › Animals";
                var cow = new WikipediaDiscoveryRows.ListItemContent(
                        "Maat", path, "Ordered list", 1,
                        new datasource.evidence.WikipediaArticle.ListItem(
                                "Item 1", "Cow", List.of(), List.of(), 2, 5), maat);
                // Labelled as though it were item 1.1 of Ra's list; the stored parent
                // says it is nested under Maat's Cow.
                var calf = new WikipediaDiscoveryRows.ListItemContent(
                        "Maat", path, "Ordered list at item 1", 2,
                        new datasource.evidence.WikipediaArticle.ListItem(
                                "Item 9.9", "Calf", List.of(), List.of(), 3, 4), maat, cow);
                var falcon = new WikipediaDiscoveryRows.ListItemContent(
                        "Ra", path, "Ordered list", 1,
                        new datasource.evidence.WikipediaArticle.ListItem(
                                "Item 1", "Falcon", List.of(), List.of(), 2, 8), ra);
                var result = new WikipediaStructureAnalysis.Result(
                        List.of(), List.of(), List.of(), List.of(cow, calf, falcon),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        false, List.of(), 2, 2);
                invoke(panel, "showAnalysis",
                        new Class<?>[]{WikipediaStructureAnalysis.Result.class, String.class},
                        result, "test");

                JTabbedPane tabs = field(panel, "resultTabs", JTabbedPane.class);
                GroupMembersView groups = field(panel, "structuralGroupsView",
                        GroupMembersView.class);
                SearchableView items = (SearchableView) tabs.getComponentAt(4);

                items.renderContext().select(falcon);
                var falconList = groups.groups().selectedGroup().getParent();
                assertEquals("Ordered list — 1 item (Ra)", falconList.getDisplayName());
                assertEquals(List.of(ra), List.copyOf(falconList.getMembers()));

                items.renderContext().select(cow);
                var cowGroup = groups.groups().selectedGroup();
                assertEquals("Ordered list — 1 item (Maat)",
                        cowGroup.getParent().getDisplayName());
                assertEquals(List.of(maat), List.copyOf(cowGroup.getParent().getMembers()));

                items.renderContext().select(calf);
                var calfGroup = groups.groups().selectedGroup();
                assertEquals(cowGroup, calfGroup.getParent().getParent(),
                        "the nested list hangs under the item the hierarchy names");
            } finally {
                panel.close();
            }
        });
    }

    private static DomainModel domain(WikidataDynamicObject instance) {
        return domain(List.of(instance), List.of("EgyptianDeity"));
    }

    private static DomainModel domain(
            List<WikidataDynamicObject> instances, List<String> types) {
        return new DomainModel() {
            @Override public List<String> types() { return types; }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                WikidataDynamicObject sample = instances.stream()
                        .filter(value -> value.directClassNames().contains(type)).findFirst()
                        .orElse(instances.getFirst());
                return () -> objectview.field.FieldSet.of(sample).fields();
            }
            @Override public Collection<? extends Viewable> instances() {
                return instances;
            }
            @Override public Class<? extends Viewable> universe() {
                return WikidataDynamicObject.class;
            }
        };
    }

    private static quiz.curation.FieldRulePromoter promoterForField(String acceptedField) {
        return new quiz.curation.FieldRulePromoter() {
            @Override public PromotionPreview previewPromotion(
                    quiz.curation.FieldSourceRecipe recipe) {
                return acceptedField.equals(recipe.field())
                        ? new PromotionPreview(true, "Add source", "model.json",
                                recipe.type(), recipe.field(), "Text",
                                "Wikipedia infobox", "", recipe.parameter("property"),
                                false, "")
                        : PromotionPreview.ineligible("Unsupported target field");
            }
            @Override public PromotionPreview promote(
                    quiz.curation.FieldSourceRecipe recipe) {
                return previewPromotion(recipe);
            }
            @Override public PromotionPreview previewPromotion(
                    quiz.curation.Correction correction) {
                return PromotionPreview.ineligible("not used");
            }
            @Override public PromotionPreview promote(quiz.curation.Correction correction) {
                return PromotionPreview.ineligible("not used");
            }
        };
    }

    private static <T> T field(Object owner, String name, Class<T> type) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(owner));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object invoke(Object owner, String name, Class<?>[] types,
            Object... arguments) {
        try {
            Method method = owner.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(owner, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class BackedDomain implements DomainModel,
            quiz.transform.ui.ProjectBacking, quiz.curation.FieldRulePromoter {
        private final WikidataDynamicObject instance;
        private final wikidata.explore.model.GeneratedProjectModel project =
                new wikidata.explore.model.GeneratedProjectModel();

        private BackedDomain(WikidataDynamicObject instance) {
            this.instance = instance;
            var deity = new wikidata.explore.model.GeneratedClassModel("EgyptianDeity");
            deity.addField("parents", datasource.schema.FieldType.ENTITY,
                    wikidata.explore.model.FieldCardinality.COLLECTION);
            project.rootClass(deity);
            project.name("Mythology");
        }

        @Override public List<String> types() { return List.of("EgyptianDeity"); }
        @Override public objectview.field.FieldSchema fieldSchema(String type) {
            return () -> objectview.field.FieldSet.of(instance).fields();
        }
        @Override public Collection<? extends Viewable> instances() {
            return List.of(instance);
        }
        @Override public Class<? extends Viewable> universe() {
            return WikidataDynamicObject.class;
        }
        @Override public String projectName() { return "Mythology"; }
        @Override public wikidata.explore.model.GeneratedProjectModel.ProjectKind projectKind() {
            return wikidata.explore.model.GeneratedProjectModel.ProjectKind.DOMAIN;
        }
        @Override public java.io.File modelFile() {
            return new java.io.File("mythology.model.json");
        }
        @Override public java.io.File snapshotFile() {
            return new java.io.File("mythology.snapshot.json");
        }
        @Override public wikidata.explore.model.GeneratedProjectModel projectModel() {
            return project;
        }
        @Override public List<String> unsavedModelChanges() { return List.of(); }
        @Override public String modelWriteConflict() { return ""; }
        @Override public void writeProjectModel() { }
        @Override public PromotionPreview previewPromotion(quiz.curation.Correction correction) {
            return PromotionPreview.ineligible("not used");
        }
        @Override public PromotionPreview promote(quiz.curation.Correction correction) {
            return PromotionPreview.ineligible("not used");
        }
    }
}
