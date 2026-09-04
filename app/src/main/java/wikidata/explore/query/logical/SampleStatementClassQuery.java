package wikidata.explore.query.logical;

import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.result.ClassSampleResult;
import wikidata.explore.transform.ModelStatementReifications;
import work.Query;
import work.QueryContext;

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
        // Said here rather than left to the production step, which routes a class by
        // what it IS: a statement class with no recipe would fall to the extracted
        // route and present its SUBJECTS as if they were its statement records.
        CompiledClass statementClass = compiled.findClass(className).orElseThrow(() ->
                new IllegalStateException("Compiled class is missing: " + className));
        if (ModelStatementReifications.deriveOne(statementClass, compiled) == null) {
            throw new IllegalStateException("No executable statement recipe for " + className);
        }
        SampledClassProduction.Records produced = context.step(
                "Produce bounded statement instances", "Workflow", skeleton(), parameters(),
                step -> SampledClassProduction.of(snapshot, compiled, className,
                        SampledClassProduction.Bound.firstMembers(limit),
                        context, StepGenerationLog.of(context, step)));

        return ClassSampleResults.materialize(snapshot, className, className,
                productionRoute, limit, produced.records(), produced.truncated());
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
