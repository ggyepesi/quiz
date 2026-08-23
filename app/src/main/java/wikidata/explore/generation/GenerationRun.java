package wikidata.explore.generation;

import objectview.Viewable;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.result.ObjectQueryResult;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything one generation produced, end to end: the model snapshot it
 * ran against, the compiled rule-tree plan, the downloaded dynamic
 * objects, the compiled runtime and the mapped instances. Inspection
 * features (generated source, SPARQL preview, remapping) should read
 * from here instead of keeping their own copies.
 */
public record GenerationRun(
        GeneratedProjectModel modelSnapshot,
        int depth,
        RuleNode plan,
        List<WikidataDynamicObject> dynamicObjects,
        GeneratedViewableRuntime runtime,
        List<Viewable> instances,
        RemapState remapState,
        List<wikidata.explore.extract.LoadedDeclaration> loadedDeclarations,
        Quality quality,
        List<wikidata.explore.transform.FieldExpectations.FieldCoverage> fieldCoverage,
        SelfReferenceAudit selfReferenceAudit,
        OwnedCompositionAudit ownedCompositionAudit,
        KindClassificationAudit kindClassificationAudit,
        ProjectionAudit projectionAudit) {

    public GenerationRun {
        loadedDeclarations = loadedDeclarations == null
                ? List.of() : List.copyOf(loadedDeclarations);
        quality = quality == null ? Quality.completeQuality() : quality;
        fieldCoverage = fieldCoverage == null ? List.of() : List.copyOf(fieldCoverage);
        selfReferenceAudit = selfReferenceAudit == null
                ? SelfReferenceAudit.notRun() : selfReferenceAudit;
        ownedCompositionAudit = ownedCompositionAudit == null
                ? OwnedCompositionAudit.notRun() : ownedCompositionAudit;
        kindClassificationAudit = kindClassificationAudit == null
                ? KindClassificationAudit.notRun() : kindClassificationAudit;
        projectionAudit = projectionAudit == null
                ? ProjectionAudit.notRun() : projectionAudit;
    }

    /**
     * Whether field projections ran, and WHICH records they changed.
     *
     * <p>A projection overlays a value read through a reference — a nomination's year
     * from its ceremony's date. It is overwrite-only, so on a settled pool it fills
     * nothing and a non-empty answer is the event. A changed value may previously have
     * been absent or stale; this audit deliberately makes no stronger claim.
     */
    public record ProjectionAudit(boolean executed, List<WikidataDynamicObject> changed) {
        public ProjectionAudit {
            changed = distinctByIdentity(changed);
            if (!executed && !changed.isEmpty()) {
                throw new IllegalArgumentException(
                        "A rule that did not run cannot have changed anything");
            }
        }

        public static ProjectionAudit ran(List<WikidataDynamicObject> changed) {
            return new ProjectionAudit(true, changed);
        }

        public static ProjectionAudit notRun() {
            return new ProjectionAudit(false, List.of());
        }

        public String description() {
            if (!executed) {
                return "Not run in this operation";
            }
            return changed.isEmpty()
                    ? "Ran; no projected field value changed"
                    : "Ran; " + changed.size()
                            + " record(s) had a projected field value changed";
        }

        private static List<WikidataDynamicObject> distinctByIdentity(
                List<WikidataDynamicObject> values) {
            if (values == null || values.isEmpty()) return List.of();
            java.util.Set<WikidataDynamicObject> seen =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            return values.stream().filter(java.util.Objects::nonNull)
                    .filter(seen::add).toList();
        }
    }

    /**
     * Whether entity kinds were classified, and WHICH entities were restamped.
     *
     * <p>A kind is settled from stored evidence, so a pool that has already been
     * classified should yield none on the next pass. Every pass restamping the same
     * thousands is a pass that cannot see its own previous work — which is exactly what
     * a Remap reported, run after run, while the reason went unexamined because the
     * number looked like the domain rather than like a fault.
     */
    public record KindClassificationAudit(
            boolean executed, List<WikidataDynamicObject> newlyClassified) {
        public KindClassificationAudit {
            newlyClassified = newlyClassified == null
                    ? List.of() : List.copyOf(newlyClassified);
            if (!executed && !newlyClassified.isEmpty()) {
                throw new IllegalArgumentException(
                        "A rule that did not run cannot have classified anything");
            }
        }

        public static KindClassificationAudit ran(
                List<WikidataDynamicObject> newlyClassified) {
            return new KindClassificationAudit(true, newlyClassified);
        }

        public static KindClassificationAudit notRun() {
            return new KindClassificationAudit(false, List.of());
        }

        public String description() {
            if (!executed) {
                return "Not run in this operation";
            }
            return newlyClassified.isEmpty()
                    ? "Ran; every kind was already settled"
                    : "Ran; " + newlyClassified.size() + " entity(ies) restamped";
        }
    }

    /**
     * Whether owned composition ran, and what it MANUFACTURED — not what it holds.
     *
     * <p>The distinction is the point. Composition is meant to find the parts it made
     * last time and reuse them, so a pass that keeps creating is one that cannot
     * recognise its own work. A Remap that added 6863 duplicate Names on every press
     * reported nothing unusual, because the only number anyone saw was how many parts
     * existed rather than how many had just been invented.
     */
    public record OwnedCompositionAudit(
            boolean executed, List<WikidataDynamicObject> created) {
        public OwnedCompositionAudit {
            created = created == null ? List.of() : List.copyOf(created);
            if (!executed && !created.isEmpty()) {
                throw new IllegalArgumentException(
                        "A rule that did not run cannot have created anything");
            }
        }

        public static OwnedCompositionAudit ran(List<WikidataDynamicObject> created) {
            return new OwnedCompositionAudit(true, created);
        }

        public static OwnedCompositionAudit notRun() {
            return new OwnedCompositionAudit(false, List.of());
        }

        public String description() {
            if (!executed) {
                return "Not run in this operation";
            }
            return created.isEmpty()
                    ? "Ran; every owned part already existed and was reused"
                    : "Ran; " + created.size() + " part(s) newly created";
        }
    }

    /** Compatibility for a transform sequence that ran and retained its findings. */
    public GenerationRun(GeneratedProjectModel modelSnapshot, int depth, RuleNode plan,
                         List<WikidataDynamicObject> dynamicObjects,
                         GeneratedViewableRuntime runtime, List<Viewable> instances,
                         RemapState remapState,
                         List<wikidata.explore.extract.LoadedDeclaration> loadedDeclarations,
                         Quality quality,
                         List<wikidata.explore.transform.FieldExpectations.FieldCoverage>
                                 fieldCoverage,
                         List<wikidata.explore.transform.TransformEngine.SelfRefFinding>
                                 selfReferenceFindings) {
        this(modelSnapshot, depth, plan, dynamicObjects, runtime, instances, remapState,
                loadedDeclarations, quality, fieldCoverage,
                SelfReferenceAudit.ran(selfReferenceFindings), OwnedCompositionAudit.notRun(),
                KindClassificationAudit.notRun(), ProjectionAudit.notRun());
    }

    /** Compatibility: a run whose reify decisions were not retained. */
    public GenerationRun(GeneratedProjectModel modelSnapshot, int depth, RuleNode plan,
                         List<WikidataDynamicObject> dynamicObjects,
                         GeneratedViewableRuntime runtime, List<Viewable> instances,
                         RemapState remapState,
                         List<wikidata.explore.extract.LoadedDeclaration> loadedDeclarations,
                         Quality quality,
                         List<wikidata.explore.transform.FieldExpectations.FieldCoverage>
                                 fieldCoverage) {
        this(modelSnapshot, depth, plan, dynamicObjects, runtime, instances, remapState,
                loadedDeclarations, quality, fieldCoverage, SelfReferenceAudit.notRun(),
                OwnedCompositionAudit.notRun(),
                KindClassificationAudit.notRun(), ProjectionAudit.notRun());
    }

    /** Compatibility constructor for callers that have quality but no finalization report. */
    public GenerationRun(GeneratedProjectModel modelSnapshot, int depth, RuleNode plan,
                         List<WikidataDynamicObject> dynamicObjects,
                         GeneratedViewableRuntime runtime, List<Viewable> instances,
                         RemapState remapState,
                         List<wikidata.explore.extract.LoadedDeclaration> loadedDeclarations,
                         Quality quality) {
        this(modelSnapshot, depth, plan, dynamicObjects, runtime, instances, remapState,
                loadedDeclarations, quality, List.of(), SelfReferenceAudit.notRun(),
                OwnedCompositionAudit.notRun(),
                KindClassificationAudit.notRun(), ProjectionAudit.notRun());
    }

    /** Compatibility constructor for local/remap paths that produced a complete run. */
    public GenerationRun(GeneratedProjectModel modelSnapshot, int depth, RuleNode plan,
                         List<WikidataDynamicObject> dynamicObjects,
                         GeneratedViewableRuntime runtime, List<Viewable> instances,
                         RemapState remapState,
                         List<wikidata.explore.extract.LoadedDeclaration> loadedDeclarations) {
        this(modelSnapshot, depth, plan, dynamicObjects, runtime, instances,
                remapState, loadedDeclarations, Quality.completeQuality(),
                List.of(), SelfReferenceAudit.notRun(), OwnedCompositionAudit.notRun(),
                KindClassificationAudit.notRun(), ProjectionAudit.notRun());
    }

    /** Whether the self-reference rule was evaluated, distinct from finding nothing. */
    public record SelfReferenceAudit(
            boolean executed,
            List<wikidata.explore.transform.TransformEngine.SelfRefFinding> findings) {
        public SelfReferenceAudit {
            findings = findings == null ? List.of() : List.copyOf(findings);
            if (!executed && !findings.isEmpty()) {
                throw new IllegalArgumentException("A rule that did not run cannot have findings");
            }
        }
        public static SelfReferenceAudit ran(
                List<wikidata.explore.transform.TransformEngine.SelfRefFinding> findings) {
            return new SelfReferenceAudit(true, findings);
        }
        public static SelfReferenceAudit notRun() {
            return new SelfReferenceAudit(false, List.of());
        }
        public String description() {
            if (!executed) return "Not run in this operation";
            return findings.isEmpty()
                    ? "Ran; no self-reference decisions"
                    : "Ran; " + findings.size() + " decision(s) recorded";
        }
    }

    /** Back-compatible convenience for consumers interested only in decisions. */
    public List<wikidata.explore.transform.TransformEngine.SelfRefFinding>
            selfReferenceFindings() {
        return selfReferenceAudit.findings();
    }

    /** Back-compat: a run with no cached transform inputs (remap = display-only). */
    public GenerationRun(GeneratedProjectModel modelSnapshot, int depth, RuleNode plan,
                         List<WikidataDynamicObject> dynamicObjects,
                         GeneratedViewableRuntime runtime, List<Viewable> instances) {
        this(modelSnapshot, depth, plan, dynamicObjects, runtime, instances, null);
    }

    /** Which declarations have been fetched is carried by the run, so a save records it
     *  and the next enrich asks only for what is new. */
    public GenerationRun(GeneratedProjectModel modelSnapshot, int depth, RuleNode plan,
                         List<WikidataDynamicObject> dynamicObjects,
                         GeneratedViewableRuntime runtime, List<Viewable> instances,
                         RemapState remapState) {
        this(modelSnapshot, depth, plan, dynamicObjects, runtime, instances,
                remapState, List.of());
    }

    /** Explicit data-completeness contract. A partial download is usable for review,
     * but can no longer masquerade as a complete generated domain. */
    public record Quality(
            boolean complete,
            List<String> warnings,
            List<String> unavailableQids) {
        public Quality {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            unavailableQids = unavailableQids == null
                    ? List.of() : unavailableQids.stream().distinct().toList();
        }

        public static Quality completeQuality() {
            return new Quality(true, List.of(), List.of());
        }

        public static Quality partial(List<String> warnings, List<String> qids) {
            return new Quality(false, warnings, qids);
        }
    }

    /**
     * The cached inputs a domain Remap re-transforms offline: the ENRICHED pool
     * (post qualifier-load, pre-reify — a deep copy, since the transforms mutate)
     * and the companion-match sets (so `won` re-computes without re-fetching P166).
     */
    public record RemapState(
            List<WikidataDynamicObject> enrichedPool,
            Map<String, Set<List<String>>> companionSets) {}

    public int size() {
        return instances == null ? 0 : instances.size();
    }

    public ObjectQueryResult objectResult() {
        return new ObjectQueryResult(
                instances,
                runtime.generatedClass(),
                runtime.source());
    }
}
