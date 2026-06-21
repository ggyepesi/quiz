package wikidata.explore.generation;

import wikidata.explore.codegen.GeneratedQuizableRuntimeBuilder;
import wikidata.explore.codegen.GeneratedQuizableRuntime;
import wikidata.explore.codegen.GeneratedQuizableMapper;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.RuleTreeExtractor;
import wikidata.explore.extract.DBpediaEnrichment;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.rule.RuleTreeSerializer;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.rule.RuleNode;
import quiz.Quizable;
import wikidata.WikidataSparqlClient;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;
import java.util.function.Consumer;

/**
 * The generation pipeline as separately callable stages:
 *
 *   plan          model snapshot  -> RuleNode query plan
 *   extract       plan + depth    -> downloaded dynamic objects  (slow, network)
 *   buildRuntime  model snapshot  -> compiled generated class    (fast, local)
 *   materialize   runtime + data  -> mapped Quizable instances   (fast, local)
 *
 * fullRun() chains all four. remap() reuses a previous run's download
 * and re-runs only the local stages; callers must check
 * sameExtraction() first.
 */
public class GenerationPipeline {

    public RuleNode plan(GeneratedProjectModel snapshot) {
        return RuleTreeCompiler.compileProject(snapshot);
    }

    public List<WikidataDynamicObject> extract(
            WikidataSparqlClient client,
            RuleNode plan,
            int depth,
            Consumer<String> log) throws Exception {

        return new RuleTreeExtractor(client).load(plan, depth, log);
    }

    // Fills DBpedia-sourced root fields (Wikipedia infobox) after the Wikidata
    // extraction, joined by owl:sameAs QID. No-op unless the root class has any
    // DBpedia field; failures are logged, not fatal (the run still succeeds).
    private void enrichFromDBpedia(
            GeneratedProjectModel snapshot,
            List<WikidataDynamicObject> roots,
            Consumer<String> log) {

        GeneratedClassModel root = snapshot.rootClass();
        if (root == null) {
            return;
        }
        boolean hasDbpedia = false;
        for (GeneratedFieldModel f : root.fields()) {
            if (f != null
                    && f.mapping().sourceType() == FieldSourceType.DBPEDIA
                    && !f.mapping().propertyPid().isBlank()) {
                hasDbpedia = true;
                break;
            }
        }
        if (!hasDbpedia) {
            return;
        }

        try (WikidataSparqlClient dbpedia = new WikidataSparqlClient(
                "quiz-modelbuilder (ggyepesi@gmail.com)", 2,
                WikidataSparqlClient.DBPEDIA_ENDPOINT)) {
            dbpedia.log(log);
            new DBpediaEnrichment().enrich(roots, root, dbpedia, log);
        } catch (Exception e) {
            log.accept("DBpedia enrichment failed: " + e.getMessage() + "\n");
        }
    }

    public GeneratedQuizableRuntime buildRuntime(
            GeneratedProjectModel snapshot) throws Exception {

        // Compile every class in the project (root + e.g. Star), so a
        // multi-class run maps each object to its own type.
        return new GeneratedQuizableRuntimeBuilder()
                .build(snapshot);
    }

    public List<Quizable> materialize(
            GeneratedQuizableRuntime runtime,
            List<WikidataDynamicObject> dynamicObjects) throws Exception {

        return new GeneratedQuizableMapper(runtime)
                .mapRoots(dynamicObjects);
    }

    public GenerationRun fullRun(
            GeneratedProjectModel snapshot,
            int depth,
            WikidataSparqlClient client,
            Consumer<String> log) throws Exception {

        RuleNode plan = plan(snapshot);

        List<WikidataDynamicObject> dynamicObjects =
                extract(client, plan, depth, log);

        enrichFromDBpedia(snapshot, dynamicObjects, log);

        GeneratedQuizableRuntime runtime = buildRuntime(snapshot);

        List<Quizable> instances =
                materialize(runtime, dynamicObjects);

        return new GenerationRun(
                snapshot, depth, plan, dynamicObjects, runtime, instances);
    }

    public GenerationRun remap(
            GenerationRun previous,
            GeneratedProjectModel snapshot) throws Exception {

        RuleNode plan = plan(snapshot);
        GeneratedQuizableRuntime runtime = buildRuntime(snapshot);

        List<Quizable> instances =
                materialize(runtime, previous.dynamicObjects());

        return new GenerationRun(
                snapshot,
                previous.depth(),
                plan,
                previous.dynamicObjects(),
                runtime,
                instances);
    }

    /**
     * True when the snapshot would extract exactly what the previous run
     * already downloaded. The extractor's only inputs are (plan, depth),
     * so equal plans and depths fetch the same data.
     */
    public boolean sameExtraction(
            GenerationRun previous,
            GeneratedProjectModel snapshot,
            int depth) {

        if (previous == null || depth != previous.depth()) {
            return false;
        }

        String a = planSignature(previous.plan());
        String b = planSignature(plan(snapshot));

        return a != null && a.equals(b);
    }

    private static String planSignature(RuleNode plan) {
        try {
            return new RuleTreeSerializer()
                    .mapper()
                    .writeValueAsString(plan);
        } catch (Exception e) {
            return null;
        }
    }
}
