package domain.transform.ui;

import dataset.DomainStorage;
import domain.DomainModel;
import domain.build.ProjectSave;
import domain.config.FieldCardinality;
import domain.config.FieldSourceBindings;
import domain.config.FieldSourceType;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.RuleDirection;
import domain.curation.Curatable;
import domain.curation.CorrectionPolicy;
import domain.curation.ManualCuration;
import domain.datasource.schema.FieldType;
import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.api.SourceBindingTarget;
import domain.datasource.api.SourceRecipe;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;
import domain.datasource.wikipedia.WikipediaEvidenceRecipes;
import domain.datasource.evidence.ArticleIntroduction;
import domain.datasource.evidence.ExtractedClaim;
import domain.datasource.evidence.SourceDocument;
import domain.enrichment.EnrichmentDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domain.runtime.WikidataDynamicObject;
import domain.transform.app.DomainCatalog;
import domain.workbench.configuration.ui.ProjectConfigurationPanel;
import domain.workbench.execution.ui.SwingQuerySession;
import domain.workbench.modelbuilder.ui.SingleRootClassModelPanel;
import domain.workbench.ui.ConfigurationTreePanel;
import domain.workbench.ui.WorkbenchActionResultNavigator;
import domain.workbench.unified.ui.UnifiedWorkbenchPanel;
import objectview.field.FieldAccess;
import objectview.Viewable;
import objectview.view.SearchableView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import process.swing.Dialogs.UnappliedEdits;
import wikipedia.WikipediaArticleClient;
import wikidata.api.WikidataApiClient;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.query.core.Datasource;
import wikidata.query.core.WikidataAccess;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The unified action runs the shared value lookup and review on its live field mapping. */
class UnifiedFieldAcquisitionTest {
    @TempDir Path directory;

    @Test void changingInstanceScopeReopensTheRecipeOnTheNewScope() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var target = SourceBindingTarget.fieldValue("DeityGroup", "members",
                    SourceBindingSlot.CANDIDATE_EVIDENCE);
            var binding = new SourceBinding(target, new SourceRecipe(WikipediaDatasourceProvider.ID,
                    WikipediaDatasourceProvider.ARTICLE_INTRODUCTION_MENTIONS, Map.of()));
            var navigator = new WorkbenchActionResultNavigator();
            var selected = new AtomicReference<>("Assessors of Maat");
            var actions = new ConfiguredFieldEvidenceActions(navigator,
                    navigator.addSource("wikipedia", "Wikipedia", null), "evidence",
                    ignored -> new JLabel(selected.get()));
            actions.show(selected.get(), "DeityGroup.members", List.of(binding), "");
            selectAction(navigator, WikipediaEvidenceRecipes.describe(binding));
            find(navigator, JLabel.class, label -> "Assessors of Maat".equals(label.getText()));
            selected.set("Ennead");
            actions.show(selected.get(), "DeityGroup.members", List.of(binding), "");
            selectAction(navigator, WikipediaEvidenceRecipes.describe(binding));
            find(navigator, JLabel.class, label -> "Ennead".equals(label.getText()));
        });
    }

    private DomainStorage saveProject() throws Exception {
        return saveProject(FieldSourceType.SPARQL, "P1559");
    }

    private DomainStorage saveProject(FieldSourceType source, String property) throws Exception {
        return saveProject(source, property, FieldCardinality.SINGLE);
    }

    private DomainStorage saveProject(FieldSourceType source, String property, FieldCardinality cardinality)
            throws Exception {
        return saveProject(source, property, cardinality, null);
    }

    private DomainStorage saveProject(FieldSourceType source, String property, FieldCardinality cardinality,
            String generatedValue) throws Exception {
        DomainStorage storage = DomainStorage.in(directory.toFile());
        var project = new GeneratedProjectModel();
        project.name("Egyptian Gods");
        var deity = new GeneratedClassModel("EgyptianDeity");
        deity.seedQids().add("Q47534");
        var mapping = deity.addField("givenLabel", FieldType.TEXT, cardinality).mapping();
        mapping.sourceType(source);
        mapping.propertyPid(property);
        mapping.direction(RuleDirection.ROOT_TO_ITEM);
        project.rootClass(deity);
        var member = new WikidataDynamicObject("Q47534", "Anubis");
        member.type("EgyptianDeity");
        if (generatedValue != null) member.put("givenLabel", generatedValue);
        if (source == FieldSourceType.WIKIPEDIA_ARTICLE_LEAD_SENTENCE) {
            member.articleIntroductionEvidence(new ArticleIntroduction(
                    "Anubis guards the dead. It has another sentence.", new SourceDocument(
                    "Wikipedia", "1257", "Anubis", "https://en.wikipedia.org/wiki/Anubis", "23",
                    null, "2026-11-25T12:00:00Z")), true);
        }
        var run = new ProjectSave.Run(List.of(member), List.of(), null, project);
        ProjectSave.plan(new ProjectSave.Input(project, run, null, List.of()), storage).write();
        return storage;
    }

    @Test void dbpediaCollectionReviewPersistsTheRecordForEachValue() throws Exception {
        var storage = saveProject(FieldSourceType.DBPEDIA, "name", FieldCardinality.COLLECTION);
        var open = DomainCatalog.modelBacked(storage).getFirst().opener();
        var opened = open.open();
        var backing = opened.capability(ProjectBacking.class);
        String first = "http://dbpedia.org/resource/Anubis";
        String second = "http://dbpedia.org/resource/Anpu";
        var expected = Map.of("Anubis", first, "Anpu", second);
        var completed = new CountDownLatch(1);
        var reviewError = new AtomicReference<Throwable>();
        var workbench = new AtomicReference<UnifiedWorkbenchPanel>();
        try (var dbpedia = new WikidataSparqlClient("test", 1, "https://example.test/sparql") {
            @Override public List<WikidataBinding> query(String sparql) {
                return List.of(
                        new WikidataBinding(Map.of("wd", "http://www.wikidata.org/entity/Q47534",
                                "val", "Anubis", "dbr", first)),
                        new WikidataBinding(Map.of("wd", "http://www.wikidata.org/entity/Q47534",
                                "val", "Anpu", "dbr", second)));
            }
        }) {
            var queries = WikidataAccess.of(null, new WikidataApiClient("test"))
                    .with(Datasource.DBPEDIA, dbpedia).bind();
            SwingUtilities.invokeAndWait(() -> {
                var panel = new UnifiedWorkbenchPanel(opened, new SwingQuerySession(queries));
                workbench.set(panel);
                var values = openLoadAction(panel, backing);
                values.acquisitionConfirmation((title, plan) -> true);
                values.acquisitionReview((title, prompt, proposals, report, accept) -> {
                    try {
                        assertNull(value(opened));
                        var decision = EnrichmentDecision.acceptDefault(proposals.getFirst());
                        var candidate = decision.fields().getFirst().candidate();
                        assertEquals(List.of("Anubis", "Anpu"), candidate.proposedValue());
                        assertEquals(expected, recordUrls(candidate.evidence()));
                        assertTrue(accept.test(List.of(decision)));
                    } catch (Throwable failure) {
                        reviewError.set(failure);
                    } finally {
                        completed.countDown();
                    }
                });
                loadButton(values).doClick();
            });
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            assertNull(reviewError.get(), () -> String.valueOf(reviewError.get()));
            var reopened = open.open();
            assertEquals(List.of("Anubis", "Anpu"), value(reopened));
            assertEquals(expected, recordUrls(reopened.capability(Curatable.class).curation()
                    .corrections().getFirst().source().evidence()));
        } finally {
            if (workbench.get() != null) SwingUtilities.invokeAndWait(workbench.get()::close);
        }
    }

    private static Map<Object, String> recordUrls(List<ExtractedClaim> claims) {
        return claims.stream().collect(java.util.stream.Collectors.toMap(
                ExtractedClaim::proposedValue, claim -> claim.evidence().getFirst().document().url()));
    }

    @Test void loadExecutesTheConfiguredWikipediaLeadProjectionAndKeepsTheArticleVersion()
            throws Exception {
        var storage = saveProject(FieldSourceType.WIKIPEDIA_ARTICLE_LEAD_SENTENCE, "");
        var open = DomainCatalog.modelBacked(storage).getFirst().opener();
        var opened = open.open();
        var backing = opened.capability(ProjectBacking.class);
        var completed = new CountDownLatch(1);
        var reviewError = new AtomicReference<Throwable>();
        var workbench = new AtomicReference<UnifiedWorkbenchPanel>();
        var noNetwork = WikidataAccess.of(null, new WikidataApiClient("test") {
            @Override protected JsonNode getEntitiesBatchWithRetry(
                    List<String> ids, boolean claims, List<String> pids) {
                throw new AssertionError("The retained introduction needs no remote acquisition");
            }
        }).bind();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new UnifiedWorkbenchPanel(opened, new SwingQuerySession(noNetwork));
                workbench.set(panel);
                var values = openLoadAction(panel, backing);
                values.acquisitionConfirmation((title, plan) -> true);
                values.acquisitionReview((title, prompt, proposals, report, accept) -> {
                    try {
                        assertNull(value(opened));
                        var decision = EnrichmentDecision.acceptDefault(proposals.getFirst());
                        assertNotNull(decision);
                        assertEquals("Anubis guards the dead.",
                                decision.fields().getFirst().candidate().proposedValue());
                        assertTrue(accept.test(List.of(decision)));
                    } catch (Throwable failure) {
                        reviewError.set(failure);
                    } finally {
                        completed.countDown();
                    }
                });
                loadButton(values).doClick();
            });
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            assertNull(reviewError.get(), () -> String.valueOf(reviewError.get()));
            var reopened = open.open();
            assertEquals("Anubis guards the dead.", value(reopened));
            var source = reopened.capability(Curatable.class).curation().corrections().getFirst().source();
            assertEquals("https://en.wikipedia.org/wiki/Anubis", source.recordUrl());
            assertEquals("23", source.evidence().getFirst().evidence().getFirst().document().revision());
        } finally {
            if (workbench.get() != null) SwingUtilities.invokeAndWait(workbench.get()::close);
        }
    }

    /** Applying a draft that changes another recipe rebuilds the recipe list. The recipe
     *  being searched is unchanged, so its search continues on the same panel instead of
     *  asking the reader to select it again. */
    @Test void applyingAnotherRecipesDraftBeforeFindKeepsTheChosenRecipesSearch() throws Exception {
        var storage = DomainStorage.in(directory.toFile());
        var project = new GeneratedProjectModel();
        project.name("Egyptian Gods");
        var deity = project.getOrCreateClass("EgyptianDeity");
        deity.seedQids().addAll(List.of("Q47534", "Q2831"));
        project.rootClass(deity);
        var field = deity.addField("ally", FieldType.ENTITY, FieldCardinality.COLLECTION);
        field.entityClassName(deity.className());
        field.mapping().sourceType(FieldSourceType.MANUAL);
        var target = SourceBindingTarget.fieldValue(deity.className(), field.name(),
                SourceBindingSlot.CANDIDATE_EVIDENCE);
        var infobox = new SourceBinding(target, new SourceRecipe(WikipediaDatasourceProvider.ID,
                WikipediaDatasourceProvider.INFOBOX_ROW_LINKS, Map.of("property", "Rendered.Allies")));
        var mentions = new SourceBinding(target, new SourceRecipe(WikipediaDatasourceProvider.ID,
                WikipediaDatasourceProvider.ARTICLE_INTRODUCTION_MENTIONS, Map.of()));
        FieldSourceBindings.put(field, infobox);
        FieldSourceBindings.put(field, mentions);
        var anubis = new WikidataDynamicObject("Q47534", "Anubis");
        anubis.type(deity.className());
        anubis.enwikiTitle("Anubis");
        String text = "Associated with Ra.";
        anubis.articleIntroductionEvidence(new ArticleIntroduction(text,
                WikipediaArticleClient.sourceDocument("Anubis", "23", text, List.of(), List.of())), true);
        var ra = new WikidataDynamicObject("Q2831", "Ra");
        ra.type(deity.className());
        ra.enwikiTitle("Ra");
        var run = new ProjectSave.Run(List.of(anubis, ra), List.of(), null, project);
        ProjectSave.plan(new ProjectSave.Input(project, run, null, List.of()), storage).write();
        var opened = DomainCatalog.modelBacked(storage).getFirst().opener().open();
        var workbench = new AtomicReference<UnifiedWorkbenchPanel>();
        var reviewed = new CountDownLatch(1);
        var prompts = new AtomicInteger();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new UnifiedWorkbenchPanel(opened);
                workbench.set(panel);
                var model = opened.capability(ProjectBacking.class).projectModel();
                find(panel, SingleRootClassModelPanel.class, item -> true)
                        .selectField(model.rootClass().fields().getFirst());
                var actions = find(panel, WorkbenchActionResultNavigator.class, item -> true);
                selectAction(actions, WikipediaEvidenceRecipes.describe(mentions));
                var evidence = find(panel, ValidationPanel.class, item -> true);
                evidence.acquisitionConfirmation((title, plan) -> {
                    prompts.incrementAndGet();
                    return true;
                });
                evidence.acquisitionReview((title, prompt, proposals, report, accept) -> reviewed.countDown());

                // An unapplied edit to the OTHER recipe.
                var outline = find(panel, ConfigurationTreePanel.class, tree -> tree.nodes().stream()
                        .anyMatch(node -> node.bindingSite() != null
                                && node.bindingSite().binding().recipe().equals(infobox.recipe())));
                outline.select(outline.nodes().stream().filter(node -> node.bindingSite() != null
                        && node.bindingSite().binding().recipe().equals(infobox.recipe()))
                        .findFirst().orElseThrow().id());
                find(panel, JTextField.class, input -> "Rendered.Allies".equals(input.getText()))
                        .setText("Rendered.Consort");
                find(panel, ProjectConfigurationPanel.class, item -> true)
                        .unappliedDecision((construct, activity) -> UnappliedEdits.APPLY);

                find(evidence, JButton.class, button -> "Find text evidence…".equals(button.getText())).doClick();
                assertEquals(1, prompts.get(), "the unchanged recipe's search continues after Apply");
                assertEquals("Rendered.Consort", FieldSourceBindings.bindings(model.rootClass().fields().getFirst(),
                        SourceBindingSlot.CANDIDATE_EVIDENCE).getFirst().recipe().parameter("property"));
                assertTrue(javax.swing.SwingUtilities.isDescendingFrom(evidence, panel),
                        "the search runs on the panel the reader sees");
            });
            assertTrue(reviewed.await(10, TimeUnit.SECONDS), "the search reached review");
        } finally {
            if (workbench.get() != null) SwingUtilities.invokeAndWait(workbench.get()::close);
        }
    }

    @Test void openingARecipeOnlyInspectsItAndAcceptingItsEvidenceSavesTheReference()
            throws Exception {
        var storage = DomainStorage.in(directory.toFile());
        var project = new GeneratedProjectModel();
        project.name("Egyptian Gods");
        var deity = project.getOrCreateClass("EgyptianDeity");
        deity.seedQids().addAll(List.of("Q47534", "Q2831"));
        project.rootClass(deity);
        var field = deity.addField("ally", FieldType.ENTITY, FieldCardinality.COLLECTION);
        field.entityClassName(deity.className());
        field.mapping().sourceType(FieldSourceType.MANUAL);
        var target = SourceBindingTarget.fieldValue(deity.className(), field.name(),
                SourceBindingSlot.CANDIDATE_EVIDENCE);
        var infobox = new SourceBinding(target, new SourceRecipe(WikipediaDatasourceProvider.ID,
                WikipediaDatasourceProvider.INFOBOX_ROW_LINKS, Map.of("property", "Rendered.Allies")));
        var mentions = new SourceBinding(target, new SourceRecipe(WikipediaDatasourceProvider.ID,
                WikipediaDatasourceProvider.ARTICLE_INTRODUCTION_MENTIONS, Map.of()));
        FieldSourceBindings.put(field, infobox);
        FieldSourceBindings.put(field, mentions);
        var anubis = new WikidataDynamicObject("Q47534", "Anubis");
        anubis.type(deity.className());
        anubis.enwikiTitle("Anubis");
        String text = "Associated with Ra.";
        anubis.articleIntroductionEvidence(new ArticleIntroduction(text,
                WikipediaArticleClient.sourceDocument("Anubis", "23", text, List.of(), List.of())), true);
        var ra = new WikidataDynamicObject("Q2831", "Ra");
        ra.type(deity.className());
        ra.enwikiTitle("Ra");
        var run = new ProjectSave.Run(List.of(anubis, ra), List.of(), null, project);
        ProjectSave.plan(new ProjectSave.Input(project, run, null, List.of()), storage).write();
        var open = DomainCatalog.modelBacked(storage).getFirst().opener();
        var opened = open.open();
        var workbench = new AtomicReference<UnifiedWorkbenchPanel>();
        var reviewed = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var prompts = new AtomicInteger();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new UnifiedWorkbenchPanel(opened);
                workbench.set(panel);
                var model = opened.capability(ProjectBacking.class).projectModel();
                find(panel, SingleRootClassModelPanel.class, item -> true)
                        .selectField(model.rootClass().fields().getFirst());
                var actions = find(panel, WorkbenchActionResultNavigator.class, item -> true);
                var outline = find(panel, ConfigurationTreePanel.class, tree -> tree.nodes().stream()
                        .anyMatch(node -> node.bindingSite() != null
                                && node.bindingSite().binding().recipe().equals(infobox.recipe())));
                var infoboxNode = outline.nodes().stream().filter(node -> node.bindingSite() != null
                        && node.bindingSite().binding().recipe().equals(infobox.recipe())).findFirst().orElseThrow();
                outline.select(infoboxNode.id());
                find(panel, JTextField.class, input -> "Rendered.Allies".equals(input.getText()))
                        .setText("Rendered.Consort");
                find(panel, JButton.class, button -> "Apply field source".equals(button.getText())).doClick();
                selectAction(actions, WikipediaEvidenceRecipes.describe(mentions));
                var action = actions.tree().getSelectionPath();
                assertEquals("Configured field evidence", action.getParentPath().getLastPathComponent().toString());
                var group = (DefaultMutableTreeNode) action.getParentPath().getLastPathComponent();
                assertEquals(2, group.getChildCount());
                assertEquals("Infobox row links — Rendered.Consort", group.getFirstChild().toString());
                var evidence = find(panel, ValidationPanel.class, item -> true);
                evidence.acquisitionConfirmation((title, plan) -> {
                    prompts.incrementAndGet();
                    assertTrue(plan.contains(WikipediaEvidenceRecipes.describe(mentions)), plan);
                    assertTrue(plan.contains("Eligible members: <b>1</b>"), plan);
                    return true;
                });
                evidence.acquisitionReview((title, prompt, proposals, report, accept) -> {
                    try {
                        var decision = EnrichmentDecision.acceptDefault(proposals.getFirst());
                        assertNotNull(decision);
                        assertEquals("Q47534", proposals.getFirst().subject().id());
                        assertTrue(accept.test(List.of(decision)));
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        reviewed.countDown();
                    }
                });
                assertEquals(0, prompts.get(), "selection only opens the recipe");
                var findEvidence = find(evidence, JButton.class, button -> "Find text evidence…".equals(button.getText()));
                var required = find(panel, JCheckBox.class, button -> button.getText().startsWith("Required (membership"));
                required.doClick();
                var configuration = find(panel, ProjectConfigurationPanel.class, item -> true);
                configuration.unappliedDecision((construct, activity) -> UnappliedEdits.STAY);
                findEvidence.doClick();
                assertEquals(0, prompts.get(), "Stay stops before confirmation");
                assertTrue(required.isSelected(), "Stay preserves the draft");
                configuration.unappliedDecision((construct, activity) -> UnappliedEdits.DISCARD);
                findEvidence.doClick();
            });
            assertTrue(reviewed.await(10, TimeUnit.SECONDS), "the configured evidence reached review");
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(1, prompts.get());
            var reopened = open.open();
            var savedField = reopened.capability(ProjectBacking.class).projectModel().rootClass().fields().getFirst();
            assertEquals("Rendered.Allies", FieldSourceBindings.bindings(savedField,
                    SourceBindingSlot.CANDIDATE_EVIDENCE).getFirst().recipe().parameter("property"));
            var member = reopened.instances().stream()
                    .filter(value -> value.getIdentifier().equals("Q47534")).findFirst().orElseThrow();
            var allies = (List<?>) FieldAccess.getPath(member, "ally");
            assertEquals(List.of("Q2831"), allies.stream().map(Viewable.class::cast)
                    .map(Viewable::getIdentifier).toList());
        } finally {
            if (workbench.get() != null) SwingUtilities.invokeAndWait(workbench.get()::close);
        }
    }

    @ParameterizedTest
    @EnumSource(UnappliedEdits.class)
    void loadAsksHowToHandleAnUnappliedFieldEdit(UnappliedEdits answer) throws Exception {
        var opened = DomainCatalog.modelBacked(saveProject()).getFirst().opener().open();
        var backing = opened.capability(ProjectBacking.class);
        var field = backing.projectModel().rootClass().fields().getFirst();
        var reads = new AtomicInteger();
        var queries = new SwingQuerySession(WikidataAccess.of(null, new WikidataApiClient("test") {
            @Override protected JsonNode getEntitiesBatchWithRetry(
                    List<String> qids, boolean withClaims, List<String> pids) {
                    reads.incrementAndGet();
                    throw new AssertionError("No request should start without load confirmation");
            }
        }).bind());

        SwingUtilities.invokeAndWait(() -> {
            try (var panel = new UnifiedWorkbenchPanel(opened, queries)) {
                var values = openLoadAction(panel, backing);
                var cards = find(values, SearchableView.class, item -> true);
                var selectedCard = opened.instances().iterator().next();
                cards.renderContext().select(selectedCard);
                var input = propertyInput(panel);
                input.setText("P2561");
                var questions = new ArrayList<String>();
                find(panel, ProjectConfigurationPanel.class, item -> true)
                        .unappliedDecision((construct, action) -> {
                            questions.add(construct + " / " + action);
                            return answer;
                        });
                var confirmedPlan = new AtomicReference<String>();
                values.acquisitionConfirmation((title, plan) -> {
                    confirmedPlan.set(plan);
                    return false;
                });
                loadButton(values).doClick();

                assertEquals(List.of("Field EgyptianDeity.givenLabel / loading values"), questions);
                assertEquals(answer == UnappliedEdits.DISCARD ? "P1559" : "P2561", input.getText());
                String applied = answer == UnappliedEdits.APPLY ? "P2561" : "P1559";
                assertEquals(applied, field.mapping().propertyPid());
                if (answer == UnappliedEdits.STAY) {
                    assertNull(confirmedPlan.get(), "Stay stops before the load confirmation");
                    assertTrue(backing.unsavedModelChanges().isEmpty());
                } else {
                    assertNotNull(confirmedPlan.get());
                    assertTrue(confirmedPlan.get().contains(applied), confirmedPlan.get());
                    assertTrue(confirmedPlan.get().contains("1</b> selected card"), confirmedPlan.get());
                }
                assertEquals(selectedCard, cards.renderContext().selected());
                assertNull(value(opened));
                assertEquals(0, reads.get());
            }
        });
    }

    @Test void aRefusedApplyStopsBeforeLoadConfirmation() throws Exception {
        var opened = DomainCatalog.modelBacked(saveProject()).getFirst().opener().open();
        var backing = opened.capability(ProjectBacking.class);
        var field = backing.projectModel().rootClass().fields().getFirst();
        SwingUtilities.invokeAndWait(() -> {
            try (var panel = new UnifiedWorkbenchPanel(opened)) {
                var values = openLoadAction(panel, backing);
                var source = find(panel, JComboBox.class, combo -> {
                    for (int i = 0; i < combo.getItemCount(); i++) {
                        if (combo.getItemAt(i) == FieldSourceType.DATASOURCE) return true;
                    }
                    return false;
                });
                source.setSelectedItem(FieldSourceType.DATASOURCE);
                find(panel, ProjectConfigurationPanel.class, item -> true)
                        .unappliedDecision((construct, action) -> UnappliedEdits.APPLY);
                values.acquisitionConfirmation((title, plan) -> {
                    throw new AssertionError("A refused Apply must stop the load");
                });
                loadButton(values).doClick();

                assertEquals(FieldSourceType.DATASOURCE, source.getSelectedItem());
                assertEquals(FieldSourceType.SPARQL, field.mapping().sourceType());
                assertEquals("P1559", field.mapping().propertyPid());
                assertTrue(backing.unsavedModelChanges().isEmpty());
                assertNull(value(opened));
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void applyThenLoadUsesTheNewPropertyAndAcceptedValuesSurviveReopening(boolean alreadyHasValue)
            throws Exception {
        DomainStorage storage = saveProject();
        var modelFile = storage.modelFile("Egyptian Gods").toPath();
        var originalModel = Files.readString(modelFile);
        String existing = alreadyHasValue ? "New source value" : null;
        if (alreadyHasValue) {
            var curation = ManualCuration.forSnapshot(storage.snapshotFile("Egyptian Gods"));
            curation.put("EgyptianDeity", "Q47534", "givenLabel", existing, "wikidata", null,
                    CorrectionPolicy.FILL_IF_EMPTY, new domain.curation.ValueSource(
                            "Wikidata", "Q47534", "P1559", "https://www.wikidata.org/wiki/Q47534"));
            curation.save();
        }
        var open = DomainCatalog.modelBacked(storage).getFirst().opener();
        DomainModel opened = open.open();
        var backing = opened.capability(ProjectBacking.class);
        var field = backing.projectModel().rootClass().fields().getFirst();
        var reads = new AtomicInteger();
        var queries = new SwingQuerySession(WikidataAccess.of(null, new WikidataApiClient("test") {
            @Override protected JsonNode getEntitiesBatchWithRetry(
                    List<String> qids, boolean withClaims, List<String> pids) throws Exception {
                    assertEquals(List.of("Q47534"), qids);
                    assertEquals(List.of("P2561"), pids);
                    reads.incrementAndGet();
                    return new ObjectMapper().readTree("""
                            {"entities":{"Q47534":{"id":"Q47534","claims":{
                             "P1559":[{"rank":"normal","mainsnak":{"snaktype":"value","datatype":"string",
                                  "datavalue":{"type":"string","value":"Original source value"}}}],
                             "P2561":[{"rank":"normal","mainsnak":{"snaktype":"value","datatype":"string",
                                  "datavalue":{"type":"string","value":"New source value"}}}]
                            }}}}
                            """);
            }
        }).bind());
        var workbench = new AtomicReference<UnifiedWorkbenchPanel>();
        var accepted = new CountDownLatch(1);
        var reviewError = new AtomicReference<Throwable>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new UnifiedWorkbenchPanel(opened, queries);
                workbench.set(panel);
                var values = openLoadAction(panel, backing);
                assertEquals(0, reads.get(), "selecting a field or action is inspection");

                propertyInput(panel).setText("P2561");
                find(panel, JButton.class, item -> "Apply field source".equals(item.getText())).doClick();
                assertEquals(0, reads.get(), "applying configuration performs no lookup");
                assertEquals("P2561", field.mapping().propertyPid());
                values.acquisitionConfirmation((title, plan) -> {
                    assertTrue(plan.contains("P2561"));
                    assertFalse(plan.contains("P1559"));
                    assertTrue(plan.contains("even where it already has a value"));
                    return true;
                });
                values.acquisitionReview((title, prompt, proposals, report, accept) -> {
                    try {
                        assertEquals(existing, value(opened), "the lookup is read-only until accepted");
                        if (alreadyHasValue) {
                            assertEquals("P1559", opened.capability(Curatable.class).curation()
                                    .corrections().getFirst().source().propertyId());
                        }
                        assertEquals(1, proposals.size());
                        var decision = EnrichmentDecision.acceptDefault(proposals.getFirst());
                        assertNotNull(decision);
                        var proposal = decision.fields().getFirst().candidate();
                        assertEquals("P2561", proposal.source().propertyId());
                        assertEquals("New source value", proposal.proposedValue());
                        assertEquals(alreadyHasValue, proposal.sourceUpdate());
                        assertTrue(accept.test(List.of(decision)));
                        assertEquals("New source value", value(opened));
                    } catch (Throwable failure) {
                        reviewError.set(failure);
                    } finally {
                        accepted.countDown();
                    }
                });

                loadButton(values).doClick();
                assertFalse(find(panel, JButton.class, item -> "Save domain…".equals(item.getText())).isEnabled());
            });
            assertTrue(accepted.await(10, TimeUnit.SECONDS), "the field lookup reached its review");
            assertNull(reviewError.get(), () -> String.valueOf(reviewError.get()));
            assertEquals(1, reads.get());
            assertEquals(originalModel, Files.readString(modelFile));
            assertTrue(Files.isRegularFile(opened.capability(Curatable.class).curation().file().toPath()));

            backing.writeProjectModel();
            var reopened = open.open();
            assertEquals("P2561", reopened.capability(ProjectBacking.class).projectModel()
                    .rootClass().fields().getFirst().mapping().propertyPid());
            assertEquals("New source value", value(reopened));
            assertEquals("P2561", reopened.capability(Curatable.class).curation()
                    .corrections().getFirst().source().propertyId());
            assertEquals(CorrectionPolicy.FILL_IF_EMPTY, reopened.capability(Curatable.class).curation()
                    .corrections().getFirst().effectivePolicy());
            var sameSource = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> {
                var values = find(workbench.get(), ValidationPanel.class, item -> true);
                values.acquisitionReview((title, prompt, proposals, report, accept) -> {
                    try {
                        assertTrue(proposals.isEmpty(), "the accepted source is unchanged");
                    } catch (Throwable failure) {
                        reviewError.set(failure);
                    } finally {
                        sameSource.countDown();
                    }
                });
                loadButton(values).doClick();
            });
            assertTrue(sameSource.await(10, TimeUnit.SECONDS));
            assertNull(reviewError.get(), () -> String.valueOf(reviewError.get()));
        } finally {
            if (workbench.get() != null) SwingUtilities.invokeAndWait(workbench.get()::close);
        }
    }

    /** A generated value the source still agrees with is confirmed, not offered as a
     *  source update: accepting one would copy the snapshot into curation for every member. */
    @Test void aGeneratedValueTheSourceStillGivesIsUnchangedNotASourceUpdate() throws Exception {
        DomainStorage storage = saveProject(FieldSourceType.SPARQL, "P1559", FieldCardinality.SINGLE,
                "Original source value");
        DomainModel opened = DomainCatalog.modelBacked(storage).getFirst().opener().open();
        var backing = opened.capability(ProjectBacking.class);
        var queries = new SwingQuerySession(WikidataAccess.of(null, new WikidataApiClient("test") {
            @Override protected JsonNode getEntitiesBatchWithRetry(
                    List<String> qids, boolean withClaims, List<String> pids) throws Exception {
                return new ObjectMapper().readTree("""
                        {"entities":{"Q47534":{"id":"Q47534","claims":{
                         "P1559":[{"rank":"normal","mainsnak":{"snaktype":"value","datatype":"string",
                              "datavalue":{"type":"string","value":"Original source value"}}}]
                        }}}}
                        """);
            }
        }).bind());
        var workbench = new AtomicReference<UnifiedWorkbenchPanel>();
        var reviewed = new CountDownLatch(1);
        var proposalsSeen = new AtomicReference<List<?>>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new UnifiedWorkbenchPanel(opened, queries);
                workbench.set(panel);
                var values = openLoadAction(panel, backing);
                values.acquisitionConfirmation((title, plan) -> true);
                values.acquisitionReview((title, prompt, proposals, report, accept) -> {
                    proposalsSeen.set(List.copyOf(proposals));
                    reviewed.countDown();
                });
                loadButton(values).doClick();
            });
            assertTrue(reviewed.await(10, TimeUnit.SECONDS), "the load reached its review");
            assertEquals(List.of(), proposalsSeen.get());
            assertTrue(opened.capability(Curatable.class).curation().corrections().isEmpty(),
                    "nothing is written to curation");
        } finally {
            if (workbench.get() != null) SwingUtilities.invokeAndWait(workbench.get()::close);
        }
    }

    private static Object value(DomainModel domain) {
        return FieldAccess.getPath(domain.instances().iterator().next(), "givenLabel");
    }

    private static ValidationPanel openLoadAction(UnifiedWorkbenchPanel panel, ProjectBacking backing) {
        var field = backing.projectModel().rootClass().fields().getFirst();
        find(panel, SingleRootClassModelPanel.class, item -> true).selectField(field);
        selectAction(find(panel, WorkbenchActionResultNavigator.class, item -> true), "Load the current configured field");
        return find(panel, ValidationPanel.class, item -> true);
    }

    private static JTextField propertyInput(UnifiedWorkbenchPanel panel) {
        return find(panel, JTextField.class, item -> "field.source.property".equals(item.getName()));
    }

    private static JButton loadButton(ValidationPanel values) {
        var load = find(values, JButton.class, item -> "Load values ↗".equals(item.getText()));
        assertTrue(load.isEnabled());
        return load;
    }

    private static void selectAction(WorkbenchActionResultNavigator actions, String caption) {
        var root = (DefaultMutableTreeNode) actions.tree().getModel().getRoot();
        for (var nodes = root.depthFirstEnumeration(); nodes.hasMoreElements();) {
            var candidate = (DefaultMutableTreeNode) nodes.nextElement();
            if (caption.equals(candidate.toString())) {
                actions.tree().setSelectionPath(new TreePath(candidate.getPath()));
                return;
            }
        }
        throw new AssertionError("Could not find action " + caption);
    }

    private static <T extends Component> T find(Component root, Class<T> type, Predicate<T> match) {
        return descendants(root).filter(type::isInstance).map(type::cast).filter(match)
                .findFirst().orElseThrow(() -> new AssertionError("Missing " + type.getSimpleName()));
    }

    private static Stream<Component> descendants(Component component) {
        return component instanceof Container container
                ? Stream.concat(Stream.of(component),
                        Arrays.stream(container.getComponents()).flatMap(UnifiedFieldAcquisitionTest::descendants))
                : Stream.of(component);
    }
}
