package wikidata.explore.query.logical;

import wikidata.WikidataIds;
import wikidata.explore.compiled.CompiledAggregateSource;
import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.generation.SemanticConvergence;
import wikidata.explore.model.ClassDependencies;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.ProductionChain;
import wikidata.explore.query.core.WikidataAccess;
import wikidata.explore.query.result.ClassSampleResult;
import wikidata.explore.transform.ModelAggregates;
import wikidata.explore.transform.ModelStatementReifications;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A sample of a class that has no population of its own.
 *
 * <p>An owned class makes one part per owning instance; an aggregate reduces its source
 * class. Sampling them is one pattern, not two: follow the production chain to the class
 * that HAS a population, bound the sample there, run production forward, and show the
 * chain — the class in hand together with what it was made from. {@link ProductionChain}
 * answers the first step over {@link ClassDependencies}, so a chain that mixes the two
 * kinds, or gains a third, is walked without this query knowing more than it does now.
 *
 * <p>What differs between the kinds is only WHERE the bound can sit, and that follows
 * from the arithmetic of each:
 *
 * <ul>
 *   <li><b>Per owner.</b> A part is produced FROM one owner, so bounding the owners makes
 *       the sample small and never makes a part wrong. One pass.</li>
 *   <li><b>Per key.</b> A group is reduced FROM many records, so bounding the records
 *       gives groups missing most of their members — 25 sampled laureates grouped by
 *       (category, year) would show Nobel prizes holding one laureate each, presented as
 *       the prizes. A small sample is fine; a wrong one is not, and this is the sample
 *       used to check that a key groups the way the modeller intended. So a chain with a
 *       reduction in it takes two passes: one to learn which keys occur, a second to read
 *       those keys whole.</li>
 * </ul>
 *
 * <p>Production runs forward in generation's own order — parts composed by {@link
 * SemanticConvergence}, then groups reduced by {@link ModelAggregates} — so a sampled
 * instance is made the way a generated one is, including on a chain that alternates.
 */
public final class SampleDerivedClassQuery implements Query<ClassSampleResult> {

    /** How many source records the key-probe pass reads per key it is asked to find.
     *  Keys repeat — that is what makes them keys — so finding N takes more than N
     *  records, and reading too few finds fewer keys than the sample asked for. */
    private static final int RECORDS_PER_KEY = 8;

    private final GeneratedProjectModel snapshot;
    private final String className;
    private final String productionRoute;
    private final int limit;

    public SampleDerivedClassQuery(GeneratedProjectModel project, String className,
            String productionRoute, int limit) {
        snapshot = project == null ? null : project.copy();
        this.className = clean(className);
        this.productionRoute = clean(productionRoute);
        this.limit = Math.max(1, limit);
    }

    @Override public String purpose() { return "Sample derived class instances"; }

    @Override public String skeleton() {
        return "production chain -> bound its population -> produce forward -> show both";
    }

    @Override public Map<String, String> parameters() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("class", className);
        values.put("productionRoute", productionRoute);
        ProductionChain chain = chain();
        values.put("population",
                chain != null && chain.resolved() ? chain.population().className() : "?");
        // Counted in what the bound is actually on, because that is the thing a reader
        // has to reason about: keys where a reduction happens, owners where none does.
        values.put(chain != null && chain.has(ClassDependencies.Kind.AGGREGATED)
                ? "keys" : "owners", String.valueOf(limit));
        return values;
    }

    private ProductionChain chain() {
        return snapshot == null ? null
                : ProductionChain.of(snapshot.findClass(className), snapshot);
    }

    @Override public ClassSampleResult execute(QueryContext context) throws Exception {
        if (snapshot == null) throw new IllegalStateException("No model to sample");
        ProductionChain chain = chain();
        if (chain == null || !chain.resolved()) {
            throw new IllegalStateException(
                    chain == null ? "No model to sample" : chain.refusal());
        }
        GeneratedClassModel population = chain.population();
        CompiledProjectModel compiled = ProjectModelCompiler.compile(snapshot);

        if (!chain.has(ClassDependencies.Kind.AGGREGATED)) {
            return context.step("Produce from bounded population", "Workflow", skeleton(),
                    parameters(), step -> {
                        GenerationLog log = StepGenerationLog.of(context, step);
                        SampledClassProduction.Records produced = SampledClassProduction.of(
                                snapshot, compiled, population.className(),
                                SampledClassProduction.Bound.firstMembers(limit),
                                context, log);
                        List<WikidataDynamicObject> pool =
                                new ArrayList<>(produced.records());
                        produceForward(chain, compiled, pool, context, log);
                        return show(chain, pool, produced.records(), produced.truncated());
                    });
        }

        String objectField = objectKeyField(compiled, chain);
        Keys chosen = context.step("Choose keys to sample", "Workflow", skeleton(),
                parameters(), step -> {
                    GenerationLog log = StepGenerationLog.of(context, step);
                    SampledClassProduction.Records probe = SampledClassProduction.of(
                            snapshot, compiled, population.className(),
                            SampledClassProduction.Bound.firstMembers(limit * RECORDS_PER_KEY),
                            context, log);
                    List<WikidataDynamicObject> pool = new ArrayList<>(probe.records());
                    produceForward(chain, compiled, pool, context, log);
                    Keys keys = keysOf(pool, className, objectField, limit);
                    log.message("Key sample: " + keys.ids().size() + " key(s) of "
                            + className + " found in " + probe.records().size()
                            + " " + population.className() + " record(s).\n");
                    return keys;
                });

        if (chosen.ids().isEmpty()) {
            return ClassSampleResults.show(snapshot, className, className,
                    productionRoute, List.of(), false, chainOrder(chain));
        }

        return context.step("Read those keys whole", "Workflow", skeleton(), parameters(),
                step -> {
                    GenerationLog log = StepGenerationLog.of(context, step);
                    log.message(chosen.objectQids().isEmpty()
                            ? "No key component reaches the acquisition, so the whole "
                                    + "population is read.\n"
                            : "Narrowed to " + chosen.objectQids().size()
                                    + " statement object(s): "
                                    + String.join(", ", chosen.objectQids()) + ".\n");
                    SampledClassProduction.Records whole = SampledClassProduction.of(
                            snapshot, compiled, population.className(),
                            SampledClassProduction.Bound.wholeObjects(chosen.objectQids()),
                            context, log);
                    List<WikidataDynamicObject> pool = new ArrayList<>(whole.records());
                    produceForward(chain, compiled, pool, context, log);
                    // Truncation is about KEYS: the groups are whole, and "more
                    // available" of a complete group says the opposite of what this
                    // query guarantees.
                    return show(chain, keep(pool, className, chosen.ids()),
                            whole.records(), chosen.ids().size() >= limit);
                });
    }

    /**
     * Generation's derivation steps, run over the sampled population — all of them.
     *
     * <p>In generation's order: parts are composed before groups are reduced, because a
     * part must exist to be grouped. Running the CHAIN's order instead would be a second
     * answer to a question generation has already settled.
     *
     * <p>Every step, not only the ones the chain names. Skipping the others looked like
     * an economy and was a misrepresentation: the chain says how the SAMPLED class is
     * produced, and says nothing about what hangs off the classes its instances reach. A
     * NobelPrize is aggregated and owns nothing, so composition was skipped — and its
     * laureates came back without the structured names that a generated laureate has,
     * from a step that would have made them had it run. A sampled instance has to be
     * what a generated one is, and that is decided by what is IN the pool, not by which
     * edge was followed to choose the pool.
     */
    private void produceForward(ProductionChain chain, CompiledProjectModel compiled,
            List<WikidataDynamicObject> pool, QueryContext context, GenerationLog log) {
        SemanticConvergence.Result converged = SemanticConvergence.apply(
                snapshot, pool, WikidataAccess.api(context), log, List.of(), null);
        log.message("Composed " + converged.ownedCreated() + " owned part(s) and settled "
                + converged.classifiedKinds() + " kind(s).\n");
        ModelAggregates.apply(compiled, pool, log);
    }

    /**
     * The sample: the class in hand, and the population it came from.
     *
     * <p>Showing only the derived instances hides what they were made of, which is half
     * of what a modeller is checking — whether the parts belong to the right owners,
     * whether the groups took the right records. The producers follow the produced, so
     * the reader meets the answer before the working.
     */
    private ClassSampleResult show(ProductionChain chain,
            List<WikidataDynamicObject> derived, List<WikidataDynamicObject> population,
            boolean truncated) throws Exception {
        List<WikidataDynamicObject> rows = new ArrayList<>();
        derived.stream().limit(limit).forEach(rows::add);
        population.stream().limit(limit).forEach(row -> {
            if (!rows.contains(row)) rows.add(row);
        });
        return ClassSampleResults.show(snapshot, className, className, productionRoute,
                rows, truncated, chainOrder(chain));
    }

    /**
     * The classes on the chain, produced before producer.
     *
     * <p>The sample is OF the class in hand, so it comes first and what made it follows
     * — the answer before the working. Without a stated order the viewer groups by type
     * in the order its reference walk happens to reach them, which on a two-class result
     * is as likely to lead with the population as with the class that was asked for.
     */
    private static List<String> chainOrder(ProductionChain chain) {
        List<String> order = new ArrayList<>();
        for (ClassDependencies.Edge link : chain.links()) {
            String produced = link.dependent().className();
            if (!order.contains(produced)) order.add(produced);
        }
        if (chain.population() != null
                && !order.contains(chain.population().className())) {
            order.add(chain.population().className());
        }
        return order;
    }

    /**
     * The keys chosen from a probe pass.
     *
     * @param ids        which groups to keep, by identifier
     * @param objectQids the entities those groups' object-side key component names, when
     *                   there is one — the part of the key the acquisition can carry
     */
    record Keys(List<String> ids, List<String> objectQids) { }

    /** The first {@code count} groups of {@code type}, and what they say about the key. */
    static Keys keysOf(List<WikidataDynamicObject> pool, String type,
            String objectField, int count) {
        List<String> ids = new ArrayList<>();
        Set<String> qids = new LinkedHashSet<>();
        for (WikidataDynamicObject group : pool) {
            if (ids.size() >= count) break;
            if (group == null || !group.directClassNames().contains(type)) continue;
            String id = group.getIdentifier();
            if (id == null || ids.contains(id)) continue;
            ids.add(id);
            if (!objectField.isBlank()) {
                String qid = qidOf(group.get(objectField));
                if (!qid.isBlank()) qids.add(qid);
            }
        }
        return new Keys(ids, List.copyOf(qids));
    }

    /** The entity a key value names, or blank when the value is not one. */
    private static String qidOf(Object value) {
        String id = value instanceof WikidataDynamicObject entity ? entity.qid()
                : value instanceof objectview.Viewable viewable ? viewable.getIdentifier()
                : String.valueOf(value);
        return id != null && WikidataIds.isQid(id.trim()) ? id.trim() : "";
    }

    /** Only the named groups, in the order they were chosen. */
    static List<WikidataDynamicObject> keep(
            List<WikidataDynamicObject> pool, String type, List<String> ids) {
        Map<String, WikidataDynamicObject> byId = new LinkedHashMap<>();
        for (WikidataDynamicObject object : pool) {
            if (object != null && object.directClassNames().contains(type)) {
                byId.put(object.getIdentifier(), object);
            }
        }
        List<WikidataDynamicObject> kept = new ArrayList<>();
        for (String id : ids) {
            WikidataDynamicObject found = byId.get(id);
            if (found != null) kept.add(found);
        }
        return kept;
    }

    /**
     * Which of the aggregate's own fields holds the part of the key the acquisition can
     * carry, or blank when none does.
     *
     * <p>Exactly one key component can be: the one whose source field is the statement's
     * value field, because that field IS the statement's object and the object end is
     * bounded by a construct that exists. A component reading a qualifier has no end to
     * bind and is reached by reducing what comes back.
     *
     * <p>Only the reduction NEAREST the population can narrow the read, because that is
     * the only one whose source is the class being fetched. A longer chain reduces again
     * further along, over records that no longer exist to be asked for.
     */
    static String objectKeyField(CompiledProjectModel compiled, ProductionChain chain) {
        ClassDependencies.Edge nearest = chain.nearestPopulation();
        if (nearest == null || nearest.kind() != ClassDependencies.Kind.AGGREGATED) {
            return "";
        }
        CompiledClass aggregate =
                compiled.findClass(nearest.dependent().className()).orElse(null);
        CompiledClass sourceClass =
                compiled.findClass(nearest.dependency().className()).orElse(null);
        if (aggregate == null || sourceClass == null) return "";
        CompiledAggregateSource source = aggregate.aggregateSource();
        if (source == null) return "";
        ModelStatementReifications.Reification reification =
                ModelStatementReifications.deriveOne(sourceClass, compiled);
        if (reification == null) return "";
        String objectField = reification.load().valueField();
        return source.keys().stream()
                .filter(key -> key.sourceField().equals(objectField))
                .map(CompiledAggregateSource.Key::targetField)
                .findFirst().orElse("");
    }

    @Override public int rowCount(ClassSampleResult result) {
        return result == null ? 0 : result.size();
    }

    @Override public String summary(ClassSampleResult result) {
        return result == null ? "0 sampled instances"
                : result.size() + " row(s) over the production chain"
                + (result.truncated() ? "; more available" : "; complete within bound");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
