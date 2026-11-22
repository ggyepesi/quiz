package domain.workbench.modelbuilder.ui;

import org.junit.jupiter.api.Test;
import domain.config.EntityBound;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.ClassSourceBindings;
import objectview.view.SearchableView;
import wikidata.explore.query.result.MembershipTypeCandidate;
import wikidata.explore.query.result.MembershipTypePreview;
import wikidata.explore.query.result.WikidataTextSearchResult;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubclassDiscoveryPanelTest {

    @Test void inspectionIsSeparateFromExplicitlyAddingAMembershipTarget()
            throws Exception {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.membership(EntityBound.relation("P31", List.of("Q4164871"), true));
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        List<String> added = new ArrayList<>();
        onEdt(() -> {
            panel.onAddMembershipTarget(added::add);
            panel.showClass(project(position), position);
        });

        panel.acceptSubtypes(List.of(candidate("Q189290", "historical position")));
        flushEdt();

        assertEquals(2, panel.resultCount(),
                "the broad type and its discovered subtype use the same candidate view");
        assertTrue(panel.statusText().contains("1 direct subtype(s) found"));
        assertTrue(added.isEmpty(), "finding subclasses must not edit the model");
        assertInstanceOf(SearchableView.class, panel.candidateView(),
                "membership candidates use ObjectView rather than a bespoke table");

        onEdt(() -> {
            panel.selectRows(1);
            panel.addSelectedRows();
        });
        assertEquals(List.of("Q189290"), added);

        flushEdt();
        render(panel, "target/ui-artifacts/subclass-explorer.png");
    }

    @Test void aClassWithNoMembershipRelationSaysWhyNothingCanBeAdded()
            throws Exception {
        // Add writes into a membership RELATION. Before a class has one the action
        // could only have answered a click with silence, which directive 10 forbids:
        // a command produces a visible result or explains why it had none.
        GeneratedClassModel unbounded = new GeneratedClassModel("Position");
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        List<String> added = new ArrayList<>();
        onEdt(() -> {
            panel.onAddMembershipTarget(added::add);
            panel.showClass(project(unbounded), unbounded);
        });
        assertTrue(panel.statusText().contains("has no membership relation yet"),
                "selecting the class already says what it is missing: "
                        + panel.statusText());

        panel.acceptSubtypes(List.of(candidate("Q294414", "public office")));
        flushEdt();
        onEdt(() -> panel.selectRows(0));

        assertFalse(panel.addEnabled(),
                "a class with no membership relation has nothing to add a target to");
        assertTrue(panel.addRefusal().contains("no membership relation yet"),
                "the disabled action says what is missing: " + panel.addRefusal());
        assertTrue(added.isEmpty());
    }

    @Test void examplesProduceTheSameCandidatesAndOnlyUseMutatesConfiguration()
            throws Exception {
        GeneratedClassModel episode = new GeneratedClassModel("Episode");
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        List<String> used = new ArrayList<>();
        onEdt(() -> {
            panel.showClass(project(episode), episode);
            panel.onUseMembershipType(value ->
                    {
                        used.add(value.qid() + "|" + value.label());
                        return "";
                    });
            panel.setExamplesForTest(List.of(
                    new domain.workbench.ui.WorkbenchSelections.Entity(
                            "Q3088950", "Cleaning the Augean stables"),
                    new domain.workbench.ui.WorkbenchSelections.Entity(
                            "Q2937680", "Capture of Cerberus")));
        });
        assertEquals(2, panel.exampleCount());

        panel.acceptCommonTypes(List.of(new MembershipTypeCandidate(
                "Q63143903", "episode in Greek mythology",
                MembershipTypeCandidate.Origin.COMMON_TYPE, "",
                2, 2, 2, -1, List.of())));
        flushEdt();

        assertTrue(used.isEmpty(), "inspection and selecting examples change no model");
        onEdt(() -> {
            panel.selectRows(0);
            panel.useSelectedRow();
        });
        assertEquals(List.of("Q63143903|episode in Greek mythology"), used);
    }

    @Test void textMatchesOnlyBecomeExamplesAfterTheExplicitAddAction()
            throws Exception {
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        GeneratedClassModel episode = new GeneratedClassModel("Episode");
        onEdt(() -> panel.showClass(project(episode), episode));

        panel.acceptTextResults(new WikidataTextSearchResult(5_000, List.of(
                new MembershipTypeCandidate.Member("Q105821782", "Fall of Troy",
                        "mythical event, part of Trojan War"),
                new MembershipTypeCandidate.Member("Q28019644", "Diana and Callisto",
                        "mythical event in Greek mythology"))));
        flushEdt();
        onEdt(() -> panel.startingPointTabs().setSelectedIndex(2));
        render(panel, "target/ui-artifacts/membership-text-candidates.png");

        assertEquals(2, panel.textResultCount());
        assertTrue(panel.statusText().contains("first 2 of 5000"));
        assertEquals(0, panel.exampleCount(),
                "finding text must not silently change examples or class membership");
        assertFalse(panel.addTextExamplesEnabled());

        onEdt(() -> {
            panel.selectTextRows(0, 1);
            panel.addSelectedTextRowsAsExamples();
        });

        assertEquals(2, panel.exampleCount());
        assertEquals(1, panel.startingPointTabs().getSelectedIndex(),
                "the explicit hand-off reveals the examples it added");
        assertTrue(panel.statusText().contains("nothing is configured yet"));
    }

    @Test void textCandidatesAlreadyPresentAreNotCountedAsNewExamples()
            throws Exception {
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        domain.workbench.ui.WorkbenchSelections.Entity fallOfTroy =
                new domain.workbench.ui.WorkbenchSelections.Entity(
                "Q105821782", "Fall of Troy");
        onEdt(() -> panel.setExamplesForTest(List.of(fallOfTroy)));
        panel.acceptTextResults(new WikidataTextSearchResult(1, List.of(
                new MembershipTypeCandidate.Member("Q105821782", "Fall of Troy"))));
        flushEdt();

        onEdt(() -> {
            panel.selectTextRows(0);
            panel.addSelectedTextRowsAsExamples();
        });

        assertEquals(1, panel.exampleCount());
        assertTrue(panel.statusText().contains("Added 0 new"));
        assertTrue(panel.statusText().contains("already examples"));
    }

    @Test void textCandidatesCannotPushExamplesPastTheTwentyExampleLimit()
            throws Exception {
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        List<domain.workbench.ui.WorkbenchSelections.Entity> existing = java.util.stream.IntStream
                .rangeClosed(1, 20)
                .mapToObj(index -> new domain.workbench.ui.WorkbenchSelections.Entity(
                        "Q" + index, "Example " + index)).toList();
        onEdt(() -> panel.setExamplesForTest(existing));
        panel.acceptTextResults(new WikidataTextSearchResult(1, List.of(
                new MembershipTypeCandidate.Member("Q21", "Example 21"))));
        flushEdt();
        onEdt(() -> panel.selectTextRows(0));

        assertFalse(panel.addTextExamplesEnabled());
        onEdt(panel::addSelectedTextRowsAsExamples);
        assertEquals(20, panel.exampleCount());
        assertTrue(panel.statusText().contains("Could not add 1 new example"));
    }

    @Test void startingPointsAndCandidatesUseTheWholeWindowOneStageAtATime()
            throws Exception {
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();

        assertSame(panel.workflowStages(),
                ((BorderLayout) panel.getLayout()).getLayoutComponent(BorderLayout.CENTER));
        assertEquals(0, panel.workflowStages().getSelectedIndex());
        assertFalse(panel.workflowStages().isEnabledAt(1),
                "the empty candidate stage must not compete with example entry");

        onEdt(() -> panel.setExamplesForTest(List.of(
                new domain.workbench.ui.WorkbenchSelections.Entity(
                        "Q3088950", "Cleaning the Augean stables"),
                new domain.workbench.ui.WorkbenchSelections.Entity(
                        "Q2937680", "Capture of Cerberus"))));
        onEdt(() -> panel.startingPointTabs().setSelectedIndex(1));
        assertInstanceOf(SearchableView.class, panel.exampleView(),
                "examples keep ObjectView search and scrolling in the full start stage");
        render(panel, "target/ui-artifacts/membership-examples.png");

        panel.acceptCommonTypes(List.of(new MembershipTypeCandidate(
                "Q63143903", "episode in Greek mythology",
                MembershipTypeCandidate.Origin.COMMON_TYPE, "",
                2, 2, 2, -1, List.of())));
        flushEdt();

        assertTrue(panel.workflowStages().isEnabledAt(1));
        assertEquals(1, panel.workflowStages().getSelectedIndex(),
                "finished discovery reveals its result instead of stacking two search views");
        render(panel, "target/ui-artifacts/membership-candidates.png");
    }

    @Test void explicitAndSelectionMembershipCannotBeReplacedByUse() throws Exception {
        for (GeneratedClassModel configured : List.of(
                withMembership(EntityBound.explicit(List.of("Q3088950"))),
                withMembership(EntityBound.vocabulary("CuratedEpisodes")))) {
            SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
            onEdt(() -> panel.showClass(project(configured), configured));
            panel.acceptCommonTypes(List.of(new MembershipTypeCandidate(
                    "Q63143903", "episode in Greek mythology",
                    MembershipTypeCandidate.Origin.COMMON_TYPE, "",
                    1, 1, 1, -1, List.of())));
            flushEdt();
            onEdt(() -> panel.selectRows(0));

            assertFalse(panel.useEnabled());
            assertTrue(panel.useRefusal().contains(configured.membership().kind()
                    == EntityBound.Kind.EXPLICIT ? "explicit seed" : "named selection"));
        }
    }

    @Test void aTypePreviewCannotReplaceAnotherKindOfRelation() throws Exception {
        GeneratedClassModel configured = withMembership(
                EntityBound.relation("P279", List.of("Q24336466"), false));
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        onEdt(() -> panel.showClass(project(configured), configured));
        panel.acceptCommonTypes(List.of(candidate("Q63143903",
                "episode in Greek mythology")));
        flushEdt();
        onEdt(() -> panel.selectRows(0));

        assertFalse(panel.useEnabled());
        assertTrue(panel.useRefusal().contains("plain P31"));
        assertFalse(panel.addEnabled());
        assertTrue(panel.addRefusal().contains("plain P31"));
    }

    @Test void anExternalPopulationDisablesWikidataMembershipActionsUpFront()
            throws Exception {
        GeneratedClassModel group = new GeneratedClassModel("DeityGroup");
        ClassSourceBindings.alternativePopulationSource(group,
                new domain.datasource.api.SourceRecipe("wikipedia", "category-members",
                        java.util.Map.of("category",
                                "Category:Groups of Egyptian deities", "limit", "100")));
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        onEdt(() -> panel.showClass(project(group), group));
        assertTrue(panel.statusText().contains("wikipedia — category-members"));
        panel.acceptCommonTypes(List.of(candidate("Q20830276",
                "group of mythical characters")));
        flushEdt();
        onEdt(() -> panel.selectRows(0));

        assertFalse(panel.useEnabled());
        assertFalse(panel.addEnabled());
        assertTrue(panel.useRefusal().contains("wikipedia — category-members"));
        assertEquals(panel.useRefusal(), panel.addRefusal());
    }

    @Test void previewSubclassClosureIsTheReadOnlyClassSetting() throws Exception {
        GeneratedClassModel direct = withMembership(
                EntityBound.relation("P31", List.of("Q63143903"), false));
        GeneratedClassModel recursive = withMembership(
                EntityBound.relation("P31", List.of("Q63143903"), true));
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();

        onEdt(() -> panel.showClass(project(direct), direct));
        assertFalse(panel.previewIncludesSubclasses());
        onEdt(() -> panel.showClass(project(recursive), recursive));
        assertTrue(panel.previewIncludesSubclasses());
        assertFalse(panel.previewSubclassSettingEditable());
    }

    @Test void membershipPreviewCompilesInheritedPopulationFiltersInProjectContext()
            throws Exception {
        GeneratedClassModel base = new GeneratedClassModel("BaseEpisode");
        base.instanceMapping().requireSitelink(true);
        domain.config.GeneratedFieldModel sourceText = base.addField(
                "sourceText", domain.datasource.schema.FieldType.STRING,
                domain.config.FieldCardinality.SINGLE);
        sourceText.required(true);
        sourceText.mapping().propertyPid("P1476");

        GeneratedClassModel episode = new GeneratedClassModel("Episode");
        episode.baseClassName("BaseEpisode");
        GeneratedProjectModel project = project(base);
        project.addClass(episode);
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();

        onEdt(() -> panel.showClass(project, episode));

        domain.compile.rule.RuleNode rule = panel.populationRule();
        assertTrue(rule.requireSitelink(),
                "preview must use the inherited instance mapping generation uses");
        assertEquals(List.of("sourceText"), rule.includedFields().stream()
                .filter(field -> !field.optional())
                .map(domain.compile.rule.RuleIncludedField::fieldName).toList(),
                "preview must use inherited required fields generation uses");
    }

    @Test void moreThanTwentyExamplesAreRefusedRatherThanSilentlyDropped()
            throws Exception {
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        List<domain.workbench.ui.WorkbenchSelections.Entity> examples = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            examples.add(new domain.workbench.ui.WorkbenchSelections.Entity("Q" + i, "Example " + i));
        }
        onEdt(() -> panel.setExamplesForTest(examples));

        assertTrue(panel.statusText().contains("Choose at most 20"));
        assertFalse(panel.findCommonTypesEnabled());
    }

    @Test void membershipPreviewUsesObjectViewAndStatesItsBound() {
        MembershipTypePreview result = new MembershipTypePreview(
                "Q63143903", "episode in Greek mythology", true, 1000, true,
                List.of(new MembershipTypeCandidate.Member(
                        "Q3088950", "Cleaning the Augean stables")));

        JComponent view = SubclassDiscoveryPanel.previewView(result);

        assertTrue(contains(view, SearchableView.class));
        assertTrue(SubclassDiscoveryPanel.previewSummary(result).contains("1000+"));
        assertTrue(SubclassDiscoveryPanel.previewSummary(result)
                .contains("including subclasses"));
        assertTrue(SubclassDiscoveryPanel.previewSummary(result)
                .contains("episode in Greek mythology (Q63143903)"));
        assertTrue(SubclassDiscoveryPanel.previewSummary(result)
                .contains("preview limit 200"));
    }

    @Test void previewSummaryNamesWhatTheClassItselfWouldKeepAndWhichMembersGo() {
        List<MembershipTypeCandidate.Member> members = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            members.add(new MembershipTypeCandidate.Member("Q" + i, "Episode " + i));
        }
        java.util.Set<String> passing = new java.util.HashSet<>();
        for (int i = 1; i <= 16; i++) passing.add("Q" + i);
        MembershipTypePreview result = new MembershipTypePreview(
                "Q63143903", "episode in Greek mythology", false, 60, false, members,
                new MembershipTypePreview.ClassFilters("Episode",
                        List.of("English Wikipedia article required"), 200, 16, false,
                        passing));

        String summary = SubclassDiscoveryPanel.previewSummary(result);

        assertTrue(summary.contains("60 member(s) by direct P31; showing all 60."), summary);
        assertTrue(summary.contains("16 pass Episode's population filters "
                + "(English Wikipedia article required)."), summary);
        assertEquals(PreviewMemberViewable.KEPT, new PreviewMemberViewable(
                members.getFirst(), result.classFilters()).inClass());
        assertEquals(PreviewMemberViewable.REMOVED, new PreviewMemberViewable(
                members.getLast(), result.classFilters()).inClass());
    }

    @Test void previewSummaryReportsWhenTheClassLimitWouldCutTheFilteredPopulation() {
        MembershipTypePreview result = new MembershipTypePreview(
                "Q5", "human", false, 1000, true, List.of(),
                new MembershipTypePreview.ClassFilters("Person",
                        List.of("label required (en)"), 200, 1000, true,
                        java.util.Set.of()));

        assertTrue(SubclassDiscoveryPanel.previewSummary(result)
                .contains("1000+ pass Person's population filters (label required (en)); "
                        + "its class limit keeps 200."));
    }

    private static MembershipTypeCandidate candidate(String qid, String label) {
        return new MembershipTypeCandidate(qid, label,
                MembershipTypeCandidate.Origin.DIRECT_SUBTYPE, "Q4164871",
                0, 0, 0, 1, List.of());
    }

    private static GeneratedClassModel withMembership(EntityBound membership) {
        GeneratedClassModel episode = new GeneratedClassModel("Episode");
        episode.membership(membership);
        return episode;
    }

    private static GeneratedProjectModel project(GeneratedClassModel root) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(root);
        return project;
    }

    private static boolean contains(Container root, Class<?> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return true;
            if (child instanceof Container nested && contains(nested, type)) return true;
        }
        return false;
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static void render(Component component, String path) throws IOException {
        component.setSize(940, 500);
        layout(component);
        File artifact = new File(path);
        assertTrue(artifact.getParentFile().mkdirs() || artifact.getParentFile().isDirectory());
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        component.printAll(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", artifact);
    }

    private static void layout(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) layout(child);
        }
    }
}
