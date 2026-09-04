package wikidata.explore.query.logical;

import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.RuleTreeExtractor;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;
import wikidata.explore.query.core.Datasource;
import wikidata.explore.query.core.WikidataAccess;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.transform.ModelStatementReifications;
import wikidata.explore.transform.QualifierLoadConfig;
import wikidata.explore.transform.QualifierLoader;
import wikidata.explore.transform.TransformEngine;
import work.QueryContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Producing one class's records against the live endpoint, bounded — the step every
 * class sample takes before it materializes.
 *
 * <p>It was written twice, once per production route, and the aggregate sample needs it
 * TWICE MORE in one run. A third and fourth copy is how the routes drift: whether a
 * classification-derived class falls back to its evidence type, whether truncation is
 * decided on subjects or on records, and how deep the extractor goes are all decisions
 * that must not have two answers.
 *
 * <p>Which route a class takes is asked here, of the compiled model — a statement class
 * is one that has an executable reification recipe — rather than by each caller deciding
 * and then picking a query.
 */
final class SampledClassProduction {

    private SampledClassProduction() { }

    /**
     * How much of a class to produce.
     *
     * @param members    the first N records, or 0 for as many as the class's own
     *                   configuration allows — which is what a full generation reads
     * @param objectQids narrows a statement route to statements whose OBJECT is one of
     *                   these; empty leaves the class's own bound. The extracted route
     *                   has no equivalent and ignores it: a bound that cannot be pushed
     *                   into the query is not silently applied as one.
     */
    record Bound(int members, List<String> objectQids) {
        Bound {
            members = Math.max(0, members);
            objectQids = objectQids == null ? List.of() : List.copyOf(objectQids);
        }

        static Bound firstMembers(int members) {
            return new Bound(members, List.of());
        }

        /** As much as the class's own configuration allows, of these objects only. */
        static Bound wholeObjects(List<String> objectQids) {
            return new Bound(0, objectQids);
        }

        boolean bounded() {
            return members > 0;
        }
    }

    /** @param truncated whether more records exist beyond what was produced */
    record Records(List<WikidataDynamicObject> records, boolean truncated) { }

    static Records of(GeneratedProjectModel snapshot, CompiledProjectModel compiled,
            String className, Bound bound, QueryContext context, GenerationLog log)
            throws Exception {
        CompiledClass clazz = compiled.findClass(className).orElseThrow(() ->
                new IllegalStateException("Compiled class is missing: " + className));
        ModelStatementReifications.Reification reification =
                ModelStatementReifications.deriveOne(clazz, compiled);
        return reification == null
                ? extracted(snapshot, compiled, clazz, bound, context, log)
                : reified(compiled, clazz, reification, bound, context, log);
    }

    /** A class whose members are entities matched by its own rule. */
    private static Records extracted(GeneratedProjectModel snapshot,
            CompiledProjectModel compiled, CompiledClass clazz, Bound bound,
            QueryContext context, GenerationLog log) throws Exception {
        RuleNode plan = RuleTreeCompiler.compileClass(clazz, compiled);
        // Classification-derived/reference classes deliberately have no population
        // mapping. For inspection, use the evidence type MembershipPattern already
        // owns; never write it back into the model or generation plan.
        if (plan.sourceQid().isBlank()) {
            plan.sourceQid(MembershipPattern.typeQid(
                    snapshot.findClass(clazz.className()), snapshot));
        }
        if (bound.bounded()) plan.limit(bound.members() + 1);

        RuleTreeExtractor extractor = new RuleTreeExtractor(
                WikidataAccess.sparql(context, Datasource.WIKIDATA))
                .api(WikidataAccess.api(context))
                .cancellation(context.cancellation());
        extractor.log(log);
        List<WikidataDynamicObject> records =
                extractor.load(plan, clazz.generationDepth(), log);
        return new Records(records,
                bound.bounded() && records.size() > bound.members());
    }

    /** A class whose members are statements, acquired and then reified. */
    private static Records reified(CompiledProjectModel compiled, CompiledClass clazz,
            ModelStatementReifications.Reification reification, Bound bound,
            QueryContext context, GenerationLog log) throws Exception {
        QualifierLoadConfig load = narrowed(reification.load(), bound.objectQids());
        List<WikidataDynamicObject> pool = new ArrayList<>();
        if (!load.discoverSubjects()) {
            CompiledClass source = compiled.findClass(load.entityType()).orElseThrow(() ->
                    new IllegalStateException("Statement source class is missing: "
                            + load.entityType()));
            RuleNode sourcePlan = RuleTreeCompiler.compileClass(source, compiled);
            if (bound.bounded()) sourcePlan.limit(bound.members() + 1);
            RuleTreeExtractor extractor = new RuleTreeExtractor(
                    WikidataAccess.sparql(context, Datasource.WIKIDATA))
                    .api(WikidataAccess.api(context))
                    .cancellation(context.cancellation());
            extractor.log(log);
            pool.addAll(extractor.load(sourcePlan, 0, log));
        }

        QualifierLoader loader = new QualifierLoader().api(WikidataAccess.api(context));
        if (bound.bounded()) loader.discoveryLimit(bound.members() + 1);
        loader.enrich(pool, load,
                WikidataAccess.sparql(context, Datasource.WIKIDATA), log);
        // Truncation is decided on the SUBJECTS, before reification: one subject can
        // carry several statements, so counting records would call a complete sample
        // truncated whenever anyone held two.
        boolean truncated = bound.bounded() && pool.size() > bound.members();
        List<WikidataDynamicObject> records = new TransformEngine().applyReify(
                pool, reification.reify(), load.valueField());
        return new Records(records, truncated);
    }

    /**
     * The same acquisition, restricted to statements about these objects.
     *
     * <p>A private narrowing of a compiled recipe, never of the authored model: the
     * modeller's bound is what they wrote, and a sample asking a narrower question must
     * not become an edit of the question.
     */
    private static QualifierLoadConfig narrowed(
            QualifierLoadConfig load, List<String> objectQids) {
        if (objectQids.isEmpty()) return load;
        EntityBound bound = EntityBound.explicit(objectQids);
        if (!bound.bounded()) return load;
        return new QualifierLoadConfig(load.entityType(), load.propertyPid(),
                load.statementField(), load.statementType(), load.valueField(),
                bound, load.subjectBound(), load.qualifiers(), bound.qids(),
                load.discoverSubjects(), load.valueDomainName());
    }
}
