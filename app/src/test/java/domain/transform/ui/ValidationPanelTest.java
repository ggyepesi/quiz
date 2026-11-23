package domain.transform.ui;

import objectview.field.FieldPath;
import org.junit.jupiter.api.Test;
import domain.DomainSchemas;
import domain.datasource.evidence.WikipediaArticle;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;
import domain.curation.Correction;
import domain.curation.FieldRulePromoter;
import domain.transform.app.ProductClass;
import domain.transform.app.ProductDomain;
import domain.transform.app.ProductField;
import domain.transform.app.ProductSchema;
import domain.workbench.execution.ui.SwingQueryRunner;
import work.QueryContext;
import domain.curation.ScopeFilter;
import domain.runtime.SnapshotDomain;
import domain.runtime.SnapshotFieldGraph;
import domain.runtime.WikidataDynamicObject;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationPanelTest {

    /** DataSourceApp showed "Not configured in ModelBuilder" for DeityGroup.members, whose
     *  primary source is relation routes: it asked only for a property. A declared
     *  datasource binding is configured and is named by its registered operation. */
    @Test void aFieldWhosePrimarySourceIsADatasourceOperationReadsAsConfigured() {
        domain.datasource.api.SourceBinding routes = new domain.datasource.api.SourceBinding(
                domain.datasource.api.SourceBindingTarget.fieldValue("DeityGroup", "members",
                        domain.datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new domain.datasource.api.SourceRecipe("wikidata", "relation-routes",
                        java.util.Map.of("routes", "[]")));

        assertEquals("Source: Bounded relation routes", ValidationPanel.configuredSource(
                null, routes, "Source: ", "Not configured in ModelBuilder"));
        assertEquals("Not configured in ModelBuilder", ValidationPanel.configuredSource(
                null, null, "Source: ", "Not configured in ModelBuilder"));
        domain.config.FieldSourceMapping property =
                new domain.config.FieldSourceMapping();
        property.propertyPid("P1082");
        property.propertyLabel("population");
        assertEquals("Source: population (P1082)", ValidationPanel.configuredSource(
                property, null, "Source: ", "Not configured in ModelBuilder"));
    }

    @Test void baseTypeCountIncludesSubtypeInstances() {
        WikidataDynamicObject state = object("Q1", "State");
        WikidataDynamicObject usState = object("Q2", "USState");

        SnapshotFieldGraph graph = new SnapshotFieldGraph();
        SnapshotFieldGraph.TypeShape stateType =
                new SnapshotFieldGraph.TypeShape();
        stateType.name = "State";
        stateType.member = true;
        SnapshotFieldGraph.TypeShape usStateType =
                new SnapshotFieldGraph.TypeShape();
        usStateType.name = "USState";
        usStateType.baseType = "State";
        usStateType.member = true;
        graph.types.put("State", stateType);
        graph.types.put("USState", usStateType);

        SnapshotDomain domain = new SnapshotDomain(List.of(state, usState), graph);

        assertEquals(2,
                ValidationPanel.membersOf(domain, domain.instances(), "State").size());
        assertEquals(1,
                ValidationPanel.membersOf(domain, domain.instances(), "USState").size());
    }

    @Test void selectedFieldUsesExplicitMissingPresentOrAllScope() {
        WikidataDynamicObject state = object("Q1", "State");
        WikidataDynamicObject usStateWithDate = object("Q2", "USState");
        usStateWithDate.put("admissionDate", "1959-01-03");
        WikidataDynamicObject usStateWithoutDate = object("Q3", "USState");

        SnapshotFieldGraph graph = new SnapshotFieldGraph();
        SnapshotFieldGraph.TypeShape stateType = new SnapshotFieldGraph.TypeShape();
        stateType.name = "State";
        stateType.member = true;
        SnapshotFieldGraph.TypeShape usStateType = new SnapshotFieldGraph.TypeShape();
        usStateType.name = "USState";
        usStateType.baseType = "State";
        usStateType.member = true;
        graph.types.put("State", stateType);
        graph.types.put("USState", usStateType);
        SnapshotDomain domain = new SnapshotDomain(
                List.of(state, usStateWithDate, usStateWithoutDate), graph);

        List<WikidataDynamicObject> all =
                List.of(state, usStateWithDate, usStateWithoutDate);
        assertEquals(List.of(usStateWithDate), ValidationPanel.membersWithFieldScope(
                domain, all, "USState", "admissionDate", ScopeFilter.PRESENT));
        assertEquals(List.of(usStateWithoutDate), ValidationPanel.membersWithFieldScope(
                domain, all, "USState", "admissionDate", ScopeFilter.MISSING));
        assertEquals(List.of(usStateWithDate, usStateWithoutDate),
                ValidationPanel.membersWithFieldScope(
                        domain, all, "USState", "admissionDate", ScopeFilter.ALL));
    }

    @Test void coverageOfSubtypeFieldExcludesOrdinaryBaseInstances() {
        WikidataDynamicObject state = object("Q1", "State");
        WikidataDynamicObject usStateWithDate = object("Q2", "USState");
        usStateWithDate.put("admissionDate", "1959-01-03");
        WikidataDynamicObject usStateWithoutDate = object("Q3", "USState");

        SnapshotFieldGraph graph = new SnapshotFieldGraph();
        SnapshotFieldGraph.TypeShape stateType = new SnapshotFieldGraph.TypeShape();
        stateType.name = "State";
        stateType.member = true;
        SnapshotFieldGraph.TypeShape usStateType = new SnapshotFieldGraph.TypeShape();
        usStateType.name = "USState";
        usStateType.baseType = "State";
        usStateType.member = true;
        graph.types.put("State", stateType);
        graph.types.put("USState", usStateType);
        SnapshotDomain domain = new SnapshotDomain(
                List.of(state, usStateWithDate, usStateWithoutDate), graph);

        FieldCoverageColumns columns = new FieldCoverageColumns(
                domain, () -> "State",
                () -> List.of(state, usStateWithDate, usStateWithoutDate));
        FieldCoverageColumns.Coverage coverage =
                columns.coverage(FieldPath.parse(
                        "@subtype:USState.admissionDate"));

        assertEquals(2, coverage.eligible());
        assertEquals(1, coverage.present());
        assertEquals(1, coverage.missing());
    }

    @Test void identityCoverageDerivesFromTheStableIdentity() {
        // A QID identity IS a Wikidata identity; a non-QID key has none. There is no
        // stored source field — coverage is read from the identity itself.
        WikidataDynamicObject identified = new WikidataDynamicObject("Q1", "Universe");
        WikidataDynamicObject unresolved =
                new WikidataDynamicObject("unresolved", "unresolved");

        assertEquals("Q1", domain.provenance.SourceIdentities.wikidataQid(identified));
        assertNull(domain.provenance.SourceIdentities.wikidataQid(unresolved));
    }

    @Test void coverageFansOutMapAndArrayIntermediates() {
        WikidataDynamicObject first = object("first", "Nested");
        first.put("value", "present");
        WikidataDynamicObject root = object("root", "Root");
        root.put("mapped", java.util.Map.of("first", first));
        root.put("array", new WikidataDynamicObject[] {first});

        org.junit.jupiter.api.Assertions.assertTrue(
                FieldCoverageColumns.hasValue(
                        root, FieldPath.parse("mapped.value")));
        org.junit.jupiter.api.Assertions.assertTrue(
                FieldCoverageColumns.hasValue(
                        root, FieldPath.parse("array.value")));
    }

    @Test void coverageCanBeInvalidatedAfterInPlaceCuration() {
        WikidataDynamicObject state = object("Q1", "State");
        List<WikidataDynamicObject> members = List.of(state);
        SnapshotDomain domain = new SnapshotDomain(members);
        FieldCoverageColumns columns = new FieldCoverageColumns(
                domain, () -> "State", () -> members);

        assertEquals(0, columns.coverage(FieldPath.of("capital")).present());
        state.put("capital", "Example");
        assertEquals(0, columns.coverage(FieldPath.of("capital")).present());
        columns.invalidate();
        assertEquals(1, columns.coverage(FieldPath.of("capital")).present());
    }

    @Test void tableRowActionStatesTheRowsAndCurrentMembersBeforeItRuns() {
        var parsed = wikipedia.WikipediaArticleClient.parseArticleText("""
                <h2>Description</h2><h3>List of names</h3>
                <table><caption>Assessors</caption>
                  <tr><th>Name of the deity</th><th>Task</th></tr>
                  <tr><td>Ra</td><td>truth</td></tr>
                  <tr><td>Unknown deity</td><td>judgement</td></tr>
                </table>
                """);
        WikipediaArticle article = new WikipediaArticle(
                wikipedia.WikipediaArticleClient.sourceDocument(
                        "Assessors of Maat", "1", parsed.text(), List.of(), parsed.links()),
                parsed.text(), List.of(), parsed.structuredSections());
        WikidataDynamicObject assessors = object("Q9133532", "DeityGroup");
        assessors.wikipediaArticleEvidence(article, true);
        assessors.put("members", List.of(object("Q1", "EgyptianDeity")));
        var spec = new WikipediaDatasourceProvider.ArticleTableRowInstanceSpec(
                "Description > List of names", "Assessors", "Name of the deity",
                "AssessorOfMaat", "rowDetails", "groups", "deities");

        ValidationPanel.TableRowEvidenceSummary summary =
                ValidationPanel.tableRowEvidenceSummary(
                        List.of(assessors), "members", spec);

        assertEquals(2, summary.rows());
        assertEquals(1, summary.currentMembers());
        assertEquals(1, summary.retainedArticles());
        assertTrue(summary.actionDescription(spec).contains("Read 2 configured table rows"));
        assertTrue(summary.actionDescription(spec).contains("keep the 1 current member"));
    }

    @Test void tableRowActionNamesCurrentMembersBeforeTheArticleIsFetched() {
        WikidataDynamicObject assessors = object("Q9133532", "DeityGroup");
        assessors.put("members", List.of(
                object("Q1", "EgyptianDeity"), object("Q2", "EgyptianDeity"),
                object("Q3", "EgyptianDeity"), object("Q4", "EgyptianDeity")));
        var spec = new WikipediaDatasourceProvider.ArticleTableRowInstanceSpec(
                "Description > List of names", "", "Name of the deity",
                "AssessorOfMaat", "rowDetails", "groups", "deities");

        ValidationPanel.TableRowEvidenceSummary summary =
                ValidationPanel.tableRowEvidenceSummary(
                        List.of(assessors), "members", spec);

        assertEquals(0, summary.retainedArticles());
        assertTrue(summary.actionDescription(spec).contains("keep the 4 current members"));
        assertTrue(summary.description(spec).contains("structured article"));
    }

    @Test void configuredTableRowsAppearAsAnExplicitActionType() {
        WikidataDynamicObject assessors = object("Q9133532", "DeityGroup");
        assessors.put("members", List.of(
                object("Q1", "EgyptianDeity"), object("Q2", "EgyptianDeity"),
                object("Q3", "EgyptianDeity"), object("Q4", "EgyptianDeity")));
        ProductDomain base = new ProductDomain(new ProductSchema(List.of(
                new ProductClass("DeityGroup", "DeityGroup", List.of(
                        new ProductField("members", "List<DeityGroupMember>", true, true,
                                "DeityGroupMember", false))),
                new ProductClass("DeityGroupMember", "DeityGroupMember", List.of())),
                List.of("DeityGroup")), List.of(assessors), objectview.Viewable.class, null);
        domain.datasource.api.SourceBinding binding = new domain.datasource.api.SourceBinding(
                domain.datasource.api.SourceBindingTarget.fieldValue(
                        "DeityGroup", "members",
                        domain.datasource.api.SourceBindingSlot.CANDIDATE_EVIDENCE),
                new domain.datasource.api.SourceRecipe("wikipedia",
                        WikipediaDatasourceProvider.ARTICLE_TABLE_ROW_INSTANCES, Map.of(
                        WikipediaDatasourceProvider.TABLE_SECTION,
                                "Description > List of names",
                        WikipediaDatasourceProvider.TABLE_COLUMN, "Name of the deity",
                        WikipediaDatasourceProvider.TABLE_ROW_TARGET_CLASS, "AssessorOfMaat",
                        WikipediaDatasourceProvider.TABLE_ROW_DETAILS_FIELD, "rowDetails",
                        WikipediaDatasourceProvider.TABLE_ROW_SOURCE_FIELD, "groups",
                        WikipediaDatasourceProvider.TABLE_ROW_REFERENCES_FIELD, "deities")));
        DomainWithBinding configured = new DomainWithBinding(base, binding);
        ValidationPanel panel = new ValidationPanel(configured, List.of(assessors),
                new SwingQueryRunner(new QueryContext(), null), () -> { }, null,
                WorkbenchPurpose.DATASOURCE);

        panel.useFixedTarget("DeityGroup", "members", "Assessors of Maat");

        javax.swing.JButton action = findButton(panel, "Find table-row instances…");
        assertTrue(action != null, "the configured operation must be named as an action");
        assertTrue(findLabel(panel, "keep the 4 current members"),
                "the action must say what it keeps before fetching the table");
    }

    /** The saved Assessors instance also carries EgyptianDeity. That hierarchy is
     * deeper, but it is unrelated to DeityGroup and has no members field. The field
     * operation must stay in the explicitly selected DeityGroup context. */
    @Test void anUnrelatedDeeperClassCannotHideTheConfiguredFieldFromItsProvider() {
        WikidataDynamicObject assessors = object("Q9133532", "DeityGroup");
        assessors.directClasses(java.util.Set.of("DeityGroup", "EgyptianDeity"));
        ProductDomain domain = new ProductDomain(new ProductSchema(List.of(
                new ProductClass("DeityGroup", "DeityGroup", List.of(
                        new ProductField("members", "List<DeityGroupMember>", true, true,
                                "DeityGroupMember", false))),
                new ProductClass("DeityGroupMember", "DeityGroupMember", List.of()),
                new ProductClass("EgyptianDeity", "EgyptianDeity",
                        "DeityGroupMember", List.of())),
                List.of("DeityGroup", "EgyptianDeity")), List.of(assessors),
                objectview.Viewable.class, null);

        assertEquals("EgyptianDeity", domain.mostSpecificClass(assessors),
                "the fixture reproduces the unrelated deeper runtime class");
        String context = ValidationPanel.fieldContextType(
                domain, assessors, "DeityGroup");
        assertEquals("DeityGroup", context);
        objectview.field.FieldRef schema =
                DomainSchemas.resolve(domain, context, "members");
        var provider = new domain.enrichment.WikipediaTableRowInstanceEvidenceProvider(
                new WikipediaDatasourceProvider.ArticleTableRowInstanceSpec(
                        "Description > List of names", "", "Name of the deity",
                        "AssessorOfMaat", "rowDetails", "groups", "deities"),
                List.of(), "EgyptianDeity", domain);
        var request = new domain.enrichment.EnrichmentRequest(
                new domain.enrichment.EnrichmentProposal.Subject(
                        context, "Q9133532", "Q9133532", "Assessors of Maat"),
                "members", true, List.of(), schema);

        assertTrue(provider.supports(request),
                "the configured table-row action must reach its provider");
    }

    private static WikidataDynamicObject object(String id, String type) {
        WikidataDynamicObject object = new WikidataDynamicObject(id, id);
        object.type(type);
        return object;
    }

    private static javax.swing.JButton findButton(
            java.awt.Container root, String text) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JButton button
                    && text.equals(button.getText())) return button;
            if (child instanceof java.awt.Container nested) {
                javax.swing.JButton found = findButton(nested, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean findLabel(java.awt.Container root, String text) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JLabel label
                    && label.getText() != null && label.getText().contains(text)) return true;
            if (child instanceof java.awt.Container nested && findLabel(nested, text)) return true;
        }
        return false;
    }

    private static final class DomainWithBinding extends domain.DelegatingDomainModel
            implements FieldRulePromoter {
        private final domain.datasource.api.SourceBinding binding;

        private DomainWithBinding(domain.DomainModel base,
                domain.datasource.api.SourceBinding binding) {
            super(base);
            this.binding = binding;
        }

        @Override public domain.datasource.api.SourceBinding declaredBinding(
                String type, String field,
                domain.datasource.api.SourceBindingSlot slot) {
            return "DeityGroup".equals(type) && "members".equals(field)
                    && slot == domain.datasource.api.SourceBindingSlot.CANDIDATE_EVIDENCE
                    ? binding : null;
        }

        @Override public PromotionPreview previewPromotion(Correction correction) {
            return PromotionPreview.ineligible("Test domain is read-only.");
        }

        @Override public PromotionPreview promote(Correction correction) {
            return PromotionPreview.ineligible("Test domain is read-only.");
        }
    }

    /** Reported: "Stage selected card seemingly does nothing". The review closes as it
     *  stages, so its own status line was never seen; the workbench now names the
     *  outcome, counted like the review, and says when nothing changed the instances. */
    @Test void stagingNamesItsOutcomeAndAnEmptyEffectIsNotSilent() {
        domain.datasource.SourceRef source = new domain.datasource.SourceRef(
                "Wikipedia (English)", "Assessors of Maat", "https://example.test/A");
        domain.transform.DynamicViewable row = new domain.transform.DynamicViewable(
                "row-1", "Usekh-nemmet");
        row.type("AssessorOfMaat");
        domain.enrichment.EnrichmentProposal.FieldCandidate member =
                new domain.enrichment.EnrichmentProposal.FieldCandidate(
                        "row-1", "", "members", List.of(), row, source,
                        domain.enrichment.EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                        null, true, List.of());
        domain.enrichment.EnrichmentProposal.InstanceCandidate instance =
                new domain.enrichment.EnrichmentProposal.InstanceCandidate(
                        "row-1", "AssessorOfMaat", "row-1", "Usekh-nemmet",
                        List.of(), source, List.of(), true);
        domain.enrichment.EnrichmentDecision decision =
                new domain.enrichment.EnrichmentDecision(
                        new domain.enrichment.EnrichmentProposal.Subject(
                                "DeityGroup", "Q9133532", "Q9133532", "Assessors of Maat"),
                        List.of(), List.of(new domain.enrichment.EnrichmentDecision
                                .FieldDecision(member, member.suggestedAction())),
                        null, List.of(instance));

        assertEquals("Staged 1 change (1 new instance); Save staged changes keeps them.",
                ValidationPanel.stageOutcomeText(List.of(decision),
                        new domain.enrichment.EnrichmentDecisionApplier.StagedPreview(
                                2, 1, List.of())));
        assertTrue(ValidationPanel.stageOutcomeText(List.of(decision),
                        new domain.enrichment.EnrichmentDecisionApplier.StagedPreview(
                                2, 0, List.of()))
                .contains("none changed the loaded instances"));
    }
}
