package wikidata.explore.query.logical;

import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.extract.RuleTreeExtractor;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;
import wikidata.explore.query.core.Datasource;
import wikidata.explore.query.core.WikidataAccess;
import wikidata.explore.query.result.ClassSampleResult;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.rule.RuleTreeCompiler;
import work.Query;
import work.QueryContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs a bounded Source-class production through the normal extraction/materialization path. */
public final class SampleEffectiveClassQuery implements Query<ClassSampleResult> {
    private final GeneratedProjectModel snapshot;
    private final String requestedClass;
    private final String sampledClass;
    private final String productionRoute;
    private final int limit;

    public SampleEffectiveClassQuery(
            GeneratedProjectModel project, String requestedClass, String sampledClass,
            String productionRoute, int limit) {
        this.snapshot = project == null ? null : project.copy();
        this.requestedClass = clean(requestedClass);
        this.sampledClass = clean(sampledClass);
        this.productionRoute = clean(productionRoute);
        this.limit = Math.max(1, limit);
    }

    @Override public String purpose() { return "Sample class instances"; }
    @Override public String skeleton() {
        return "effective class -> bounded normal extraction -> generated instances";
    }
    @Override public Map<String, String> parameters() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("class", requestedClass);
        values.put("productionRoute", productionRoute);
        values.put("limit", String.valueOf(limit));
        return values;
    }

    @Override public ClassSampleResult execute(QueryContext context) throws Exception {
        if (snapshot == null) throw new IllegalStateException("No model to sample");
        CompiledProjectModel compiled = ProjectModelCompiler.compile(snapshot);
        CompiledClass clazz = compiled.findClass(sampledClass).orElseThrow(() ->
                new IllegalStateException("Compiled class is missing: " + sampledClass));
        RuleNode plan = RuleTreeCompiler.compileClass(clazz, compiled);
        // Classification-derived/reference classes deliberately have no population
        // mapping. For inspection, use the evidence type MembershipPattern already
        // owns; never write it back into the model or generation plan.
        if (plan.sourceQid().isBlank()) {
            var editableClass = snapshot.findClass(sampledClass);
            plan.sourceQid(MembershipPattern.typeQid(editableClass, snapshot));
        }
        plan.limit(limit + 1);

        List<wikidata.explore.extract.WikidataDynamicObject> extracted = context.step(
                "Extract bounded class instances", "Workflow", skeleton(), parameters(), step -> {
                    var generationLog = StepGenerationLog.of(context, step);
                    RuleTreeExtractor extractor = new RuleTreeExtractor(
                            WikidataAccess.sparql(context, Datasource.WIKIDATA))
                            .api(WikidataAccess.api(context))
                            .cancellation(context.cancellation());
                    extractor.log(generationLog);
                    return extractor.load(plan, clazz.generationDepth(), generationLog);
                });

        return ClassSampleResults.materialize(snapshot, requestedClass, sampledClass,
                productionRoute, limit, extracted);
    }

    @Override public int rowCount(ClassSampleResult result) {
        return result == null ? 0 : result.size();
    }
    @Override public String summary(ClassSampleResult result) {
        if (result == null) return "0 sampled instances";
        return result.size() + " sampled instance(s)"
                + (result.truncated() ? "; more available" : "; complete within bound");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
