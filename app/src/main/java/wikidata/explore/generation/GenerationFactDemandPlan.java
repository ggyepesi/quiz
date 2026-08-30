package wikidata.explore.generation;

import wikidata.api.FactDemand;
import wikidata.api.FactDemandPlan;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.DisambiguationPrune;
import wikidata.explore.transform.ModelStatementReifications;
import wikidata.explore.transform.ReferentFieldLoad;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles the prospective fact needs that can be known before discovery.
 *
 * <p>Only the compiling lives here, because only the compiling needs to know the transform
 * steps. What it produces is a {@link FactDemandPlan}, which those same steps are handed —
 * and which names nothing in this package, so the two stages no longer depend on each other.
 */
public final class GenerationFactDemandPlan {

    private GenerationFactDemandPlan() { }

    public static FactDemandPlan compile(GeneratedProjectModel model) {
        return compile(model, null);
    }

    public static FactDemandPlan compile(
            GeneratedProjectModel model,
            datasource.api.SourceExecutionPlan sourcePlan) {
        return compile(model, model == null ? null
                : wikidata.explore.compiled.ProjectModelCompiler.compile(model), sourcePlan);
    }

    static FactDemandPlan compile(
            GeneratedProjectModel model,
            wikidata.explore.compiled.CompiledProjectModel compiled,
            datasource.api.SourceExecutionPlan sourcePlan) {
        if (model == null) return FactDemandPlan.empty();
        List<FactDemand> planned = new ArrayList<>();
        planned.addAll(DisambiguationPrune.factDemands(model));
        planned.addAll(ReferentFieldLoad.factDemands(model));
        for (var recipe : ModelStatementReifications.derive(compiled)) {
            var load = recipe.load();
            planned.add(FactDemand.of(
                    "statement acquisition", load.entityType(),
                    List.of(load.propertyPid()),
                    "load " + load.statementType() + " statements and qualifiers"));
        }
        java.util.Set<String> labelClasses =
                sourcePlan == null ? model.classes().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(c -> c.className()).collect(java.util.stream.Collectors.toSet())
                        : wikidata.explore.model.ClassNameSourcePlan.labels(sourcePlan);
        java.util.Set<String> aliasClasses =
                sourcePlan == null ? labelClasses
                        : wikidata.explore.model.ClassNameSourcePlan.aliases(sourcePlan);
        java.util.Set<String> articleClasses =
                sourcePlan == null ? java.util.Set.of()
                        : articleCorrespondenceClasses(sourcePlan);
        for (var clazz : model.classes()) {
            if (clazz == null) continue;
            if (articleClasses.contains(clazz.className())) {
                planned.add(FactDemand.metadata(
                        "wikipedia article evidence", clazz.className(),
                        List.of(FactDemand.EntityMetadata.SITELINKS),
                        "name the article a category or infobox source is read from"));
            }
            if (labelClasses.contains(clazz.className())) {
                planned.add(FactDemand.metadata(
                        "final label hydration", clazz.className(),
                        List.of(FactDemand.EntityMetadata.LABEL),
                        "resolve placeholder QIDs after the graph closes"));
            }
            if (aliasClasses.contains(clazz.className())) {
                planned.add(FactDemand.metadata(
                        "alias coverage", clazz.className(),
                        List.of(FactDemand.EntityMetadata.ALIASES),
                        "carry configured alternate names on entity responses"));
            }
        }
        return new FactDemandPlan(planned);
    }

    /**
     * Classes a configured Wikipedia source reads through an ARTICLE, which a QID names
     * by its sitelink.
     *
     * <p>Demanded so the first acquisition carries it. Left out, the fact store rightly
     * refuses a document that has no sitelinks and the population is fetched a second
     * time to add them — which is exactly what aliases did before they were demanded
     * here, for 265 seconds over 11,154 subjects and 684 KB of actual payload.
     *
     * <p>Asked of each provider's prepared input requirements. This adapter knows how
     * Wikidata satisfies article correspondence, but does not know which operations
     * requested it.
     */
    private static java.util.Set<String> articleCorrespondenceClasses(
            datasource.api.SourceExecutionPlan plan) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (datasource.api.SourceExecutionPlan.Step step
                : plan.steps(datasource.api.BindingScope.FIELD_VALUE)) {
            if (step.prepared().inputRequirements().stream().anyMatch(requirement ->
                    datasource.EntityRef.WIKIDATA.equals(requirement.sourceId())
                            && requirement.kind()
                            == datasource.api.SourceInputRequirement.Kind
                                    .ARTICLE_CORRESPONDENCE)) {
                out.add(step.target().className());
            }
        }
        return java.util.Set.copyOf(out);
    }
}
