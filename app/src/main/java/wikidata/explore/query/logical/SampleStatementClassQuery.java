package wikidata.explore.query.logical;

import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.RuleTreeExtractor;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.core.Datasource;
import wikidata.explore.query.core.WikidataAccess;
import wikidata.explore.query.result.ClassSampleResult;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.transform.ModelStatementReifications;
import wikidata.explore.transform.QualifierLoadConfig;
import wikidata.explore.transform.QualifierLoader;
import wikidata.explore.transform.TransformEngine;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded statement acquisition followed by the normal statement reification. */
public final class SampleStatementClassQuery implements Query<ClassSampleResult> {
    private final GeneratedProjectModel snapshot;
    private final String className;
    private final String productionRoute;
    private final int limit;

    public SampleStatementClassQuery(
            GeneratedProjectModel project, String className,
            String productionRoute, int limit) {
        snapshot = project == null ? null : project.copy();
        this.className = clean(className);
        this.productionRoute = clean(productionRoute);
        this.limit = Math.max(1, limit);
    }

    @Override public String purpose() { return "Sample statement class instances"; }
    @Override public String skeleton() {
        return "bounded subjects -> acquire statements and qualifiers -> reify records";
    }
    @Override public Map<String, String> parameters() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("class", className);
        values.put("productionRoute", productionRoute);
        values.put("limit", String.valueOf(limit));
        return values;
    }

    @Override public ClassSampleResult execute(QueryContext context) throws Exception {
        if (snapshot == null) throw new IllegalStateException("No model to sample");
        CompiledProjectModel compiled = ProjectModelCompiler.compile(snapshot);
        CompiledClass statementClass = compiled.findClass(className).orElseThrow(() ->
                new IllegalStateException("Compiled class is missing: " + className));
        ModelStatementReifications.Reification reification =
                ModelStatementReifications.deriveOne(statementClass, compiled);
        if (reification == null) {
            throw new IllegalStateException("No executable statement recipe for " + className);
        }

        return context.step("Produce bounded statement instances", "Workflow", skeleton(),
                parameters(), step -> {
                    GenerationLog log = StepGenerationLog.of(context, step);
                    QualifierLoadConfig load = reification.load();
                    List<WikidataDynamicObject> pool = new ArrayList<>();
                    if (!load.discoverSubjects()) {
                        CompiledClass source = compiled.findClass(load.entityType()).orElseThrow(() ->
                                new IllegalStateException("Statement source class is missing: "
                                        + load.entityType()));
                        RuleNode sourcePlan = RuleTreeCompiler.compileClass(source, compiled);
                        sourcePlan.limit(limit + 1);
                        RuleTreeExtractor extractor = new RuleTreeExtractor(
                                WikidataAccess.sparql(context, Datasource.WIKIDATA))
                                .api(WikidataAccess.api(context))
                                .cancellation(context.cancellation());
                        extractor.log(log);
                        pool.addAll(extractor.load(sourcePlan, 0, log));
                    }

                    new QualifierLoader().api(WikidataAccess.api(context))
                            .discoveryLimit(limit + 1)
                            .enrich(pool, load,
                                    WikidataAccess.sparql(context, Datasource.WIKIDATA), log);
                    boolean subjectsTruncated = pool.size() > limit;
                    List<WikidataDynamicObject> records = new TransformEngine().applyReify(
                            pool, reification.reify(), load.valueField());
                    return ClassSampleResults.materialize(snapshot, className, className,
                            productionRoute, limit, records, subjectsTruncated);
                });
    }

    @Override public int rowCount(ClassSampleResult result) {
        return result == null ? 0 : result.size();
    }
    @Override public String summary(ClassSampleResult result) {
        return result == null ? "0 sampled statement instances"
                : result.size() + " sampled statement instance(s)"
                + (result.truncated() ? "; more available" : "; complete within bound");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
