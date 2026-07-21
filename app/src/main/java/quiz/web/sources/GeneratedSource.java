package quiz.web.sources;

import objectview.render.FieldLabels;
import objectview.field.DynamicFields;
import quiz.Quizable;
import quiz.QuizableGroup;
import objectview.facet.Facet;
import objectview.facet.FacetGrouper;
import quiz.web.QuizableSource;
import quiz.web.QuizableStore;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serves a generated (dynamic) knowledge set — a {@code WikidataDynamicObject}
 * snapshot — in the web, with <b>no per-domain code</b>. The group tree is
 * auto-derived from the inferred schema: entity-valued fields become reference
 * facets, low-cardinality scalar fields become bucket facets (via the generic
 * {@link FacetGrouper}). Images and high-cardinality scalars are skipped.
 *
 * <p>This is the seam that lets the generation pipeline reach the existing web
 * client; the data is dynamic ({@link DynamicFields}), so rendering, field
 * listing, faceting, and quizzes all work through the shared machinery.
 */
public class GeneratedSource implements QuizableSource {

    private static final int MAX_BUCKETS = 25;

    private final String type;
    private final File file;
    private final File modelFile;   // optional: the dataset's saved model (declared facets)
    private final Set<String> structural;   // fields to hide (e.g. a reify class's "source")
    // Nested vocabulary dimensions {dottedPath, label} — e.g. {"nominee.type","type"},
    // {"forWork.genre","genre"} — so a statement class groups by a referent's vocab tag.
    private final List<String[]> vocabFacetPaths;
    private final wikidata.explore.model.GeneratedProjectModel model;   // for field expectations
    private List<WikidataDynamicObject> members;

    public GeneratedSource(String type, File file) {
        this(type, file, null);
    }

    public GeneratedSource(String type, File file, File modelFile) {
        this(type, file, modelFile, loadModel(modelFile));
    }

    private GeneratedSource(String type, File file, File modelFile,
                            wikidata.explore.model.GeneratedProjectModel model) {
        this.type = type;
        this.file = file;
        this.modelFile = modelFile;
        this.structural = structuralFor(type, model);
        this.vocabFacetPaths = vocabFacetPaths(type, model);
        this.model = model;
        // The card/field renderer (QuizableJson) hides the same structural fields.
        quiz.web.QuizableJson.registerStructural(type, this.structural);
    }

    // Nested vocab facet paths for a type: for each entity field F -> a modeled class C,
    // each entity field G on C whose target is a VOCABULARY yields the path F.G (e.g.
    // Nomination.nominee.type -> NomineeType). A DIRECT vocab field (category) is already
    // an auto-facet, so it is not included here.
    static List<String[]> vocabFacetPaths(
            String type, wikidata.explore.model.GeneratedProjectModel model) {
        List<String[]> out = new ArrayList<>();
        if (model == null) {
            return out;
        }
        wikidata.explore.model.GeneratedClassModel t = model.findClass(type);
        if (t == null) {
            return out;
        }
        for (wikidata.explore.model.GeneratedFieldModel f : t.fields()) {
            if (f == null || f.type() != wikidata.explore.model.FieldType.ENTITY) {
                continue;
            }
            wikidata.explore.model.GeneratedClassModel c =
                    f.entityClassName() == null ? null : model.findClass(f.entityClassName());
            if (c == null) {
                continue;   // f targets a vocabulary or nothing, not a nested class
            }
            for (wikidata.explore.model.GeneratedFieldModel g : c.fields()) {
                if (g == null || g.type() != wikidata.explore.model.FieldType.ENTITY) {
                    continue;
                }
                String vocab = g.entityClassName();
                if (vocab != null && !vocab.isBlank()
                        && model.findClass(vocab) == null
                        && model.findSelection(vocab)
                                instanceof wikidata.explore.model.VocabularySelection) {
                    out.add(new String[] {f.name() + "." + g.name(), g.name()});
                }
            }
        }
        return out;
    }

    // The dataset's saved model, or null (legacy/untyped snapshot). Loaded once per
    // registerAll and shared across the per-type sources.
    private static wikidata.explore.model.GeneratedProjectModel loadModel(File modelFile) {
        if (modelFile == null || !modelFile.isFile()) {
            return null;
        }
        try {
            return new wikidata.explore.model.GeneratedProjectModelStore().load(modelFile);
        } catch (Exception ignore) {
            return null;
        }
    }

    // Structural (hidden) fields for a type: a statement-reification class carries a
    // "source" back-reference (provenance), not a user attribute. Same rule as
    // SnapshotDomain.structuralFields on the desktop side.
    static Set<String> structuralFor(
            String type, wikidata.explore.model.GeneratedProjectModel model) {
        if (model == null) {
            return Set.of();
        }
        wikidata.explore.model.GeneratedClassModel c = model.findClass(type);
        return c != null && c.reifiesStatements() ? Set.of("source") : Set.of();
    }

    @Override
    public String type() {
        return type;
    }

    // One snapshot can carry several classes (e.g. Constellation + its child
    // Stars). This source serves the entities stamped with THIS type. A legacy
    // untyped snapshot has no stamped types, so we fall back to serving the
    // roots stamped as this type (the original single-class behaviour).
    private synchronized List<WikidataDynamicObject> members() throws Exception {
        if (members == null) {
            WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
            List<WikidataDynamicObject> all = store.loadAll(file);
            // Bare references (unstamped, no substance — e.g. type values) read
            // as display-name strings on the web too, matching the workbench.
            wikidata.explore.transform.BareReferenceCollapse.apply(all);
            boolean anyTyped = all.stream().anyMatch(o -> isStamped(o.typeName()));
            if (anyTyped) {
                List<WikidataDynamicObject> m = new ArrayList<>();
                for (WikidataDynamicObject o : all) {
                    if (type.equals(o.typeName())) m.add(o);
                }
                members = m;
            } else {
                members = new ArrayList<>(store.load(file)); // roots
                for (WikidataDynamicObject o : members) o.type(type);
            }
            // Browse order is alphabetical by name (the snapshot/pool order is
            // extraction order, which isn't reliably sorted).
            members.sort(java.util.Comparator.comparing(
                    o -> o.getDisplayName() == null ? "" : o.getDisplayName(),
                    String.CASE_INSENSITIVE_ORDER));
        }
        return members;
    }

    private static boolean isStamped(String t) {
        return t != null && !t.isBlank() && !"WikidataDynamicObject".equals(t);
    }

    /**
     * Registers a {@link GeneratedSource} per distinct stamped class in the
     * snapshot — e.g. Constellation AND Star from one constellations snapshot —
     * so each is browsable/quizzable. Falls back to a single source named
     * {@code defaultType} for an untyped (legacy) snapshot.
     */
    public static void registerAll(QuizableStore store, String defaultType, File file)
            throws Exception {
        registerAll(store, defaultType, file, null);
    }

    public static void registerAll(QuizableStore store, String defaultType,
                                   File file, File modelFile) throws Exception {
        Set<String> types = new LinkedHashSet<>();
        if (file.isFile()) {
            for (WikidataDynamicObject o
                    : new WikidataDynamicObjectJsonStore().loadAll(file)) {
                if (isStamped(o.typeName())) types.add(o.typeName());
            }
        }
        if (types.isEmpty()) types.add(defaultType);
        wikidata.explore.model.GeneratedProjectModel model = loadModel(modelFile);
        for (String t : types) {
            store.register(new GeneratedSource(t, file, modelFile, model));
        }
    }

    @Override
    public Collection<? extends Quizable> load() throws Exception {
        return members();
    }

    @Override
    public QuizableGroup rootGroup() throws Exception {
        Collection<? extends Quizable> all = load();
        List<Facet<Quizable>> facets = new ArrayList<>();
        for (Dimension d : declaredDimensions(all)) {
            facets.add(facetFor(d));
        }
        return FacetGrouper.group(QuizableGroup::new, "All " + type, all, facets);
    }

    /**
     * The DECLARED groupable dimensions for this type — an explicit, ordered list the
     * web serves so the client can re-facet LIVE (flip among them). The declaration is
     * model-derived where possible: a referent's vocabulary tag (nominee.type,
     * forWork.genre) comes from the model; each top-level field's KIND (reference /
     * boolean / low-cardinality value) is read from a sample so an unmodeled snapshot
     * still yields dimensions. Structural fields (a reify "source" back-ref) are never
     * dimensions. Execution (building the actual grouping) stays at serve time.
     */
    @Override
    public List<Dimension> dimensions() throws Exception {
        return declaredDimensions(load());
    }

    @Override
    public List<Coverage.FieldCoverage> coverage() throws Exception {
        Collection<? extends Quizable> all = load();
        List<Dimension> paths = coveragePaths(new ArrayList<>(all));
        Map<String, String> expectations = new LinkedHashMap<>();
        for (Dimension d : paths) {
            expectations.put(d.path(), expectationForPath(d.path()));
        }
        return Coverage.of(all, paths, expectations);
    }

    // The paths the validator checks coverage for: the served type's DECLARED model
    // fields (so a field is checked even if the sample is homogeneous — e.g. Nominee.type
    // is all "human" among the first alphabetical nominees) plus the nested vocab paths.
    // Falls back to the sample-derived dimensions for a legacy/unmodeled snapshot.
    private List<Dimension> coveragePaths(Collection<? extends Quizable> sample) {
        wikidata.explore.model.GeneratedClassModel c =
                model == null ? null : model.findClass(type);
        if (c == null) {
            return declaredDimensions(sample);
        }
        List<Dimension> out = new ArrayList<>();
        java.util.Set<String> seen = new HashSet<>();
        for (wikidata.explore.model.GeneratedFieldModel f : c.fields()) {
            if (f == null || f.name() == null || structural.contains(f.name())) {
                continue;
            }
            if (seen.add(f.name())) {
                out.add(new Dimension(f.name(), f.name(), Dimension.Kind.VALUE));
            }
        }
        for (String[] p : vocabFacetPaths) {
            if (seen.add(p[0])) {
                out.add(new Dimension(p[1], p[0], Dimension.Kind.VALUE));
            }
        }
        return out;
    }

    // The declared expectation (NONE / EXPECTED / REQUIRED) of the LEAF field a dotted
    // path resolves to in the model — e.g. "forWork.genre" -> ForWork.genre.expectation.
    private String expectationForPath(String path) {
        if (model == null) {
            return "NONE";
        }
        wikidata.explore.model.GeneratedClassModel c = model.findClass(type);
        wikidata.explore.model.GeneratedFieldModel f = null;
        for (String seg : path.split("\\.")) {
            if (c == null) {
                return "NONE";
            }
            f = null;
            for (wikidata.explore.model.GeneratedFieldModel cand : c.fields()) {
                if (cand != null && seg.equals(cand.name())) {
                    f = cand;
                    break;
                }
            }
            if (f == null) {
                return "NONE";
            }
            c = f.entityClassName() == null ? null : model.findClass(f.entityClassName());
        }
        return f == null ? "NONE" : f.expectation().name();
    }

    private List<Dimension> declaredDimensions(Collection<? extends Quizable> all) {
        List<Dimension> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        wikidata.explore.model.GeneratedClassModel c =
                model == null ? null : model.findClass(type);
        if (c != null) {
            // Detection = the MODEL for typed fields (vocabulary / class-ref / boolean):
            // a field IS a dimension because of its declared type, NOT because a sample
            // is low-cardinality. Fixes a large vocab dropped by the bucket cap
            // (WorkGenre, 129) and a homogeneous one (Nominee.type, all "human").
            for (wikidata.explore.model.GeneratedFieldModel f : c.fields()) {
                if (f == null || f.name() == null || structural.contains(f.name())) {
                    continue;
                }
                Dimension.Kind kind = modelKind(f, all);
                if (kind != null && seen.add(f.name())) {
                    out.add(new Dimension(f.name(), f.name(), kind));
                }
            }
            for (String[] p : vocabFacetPaths) {   // nested vocab tags — also model-declared
                if (seen.add(p[0])) {
                    out.add(new Dimension(p[1], p[0], kindForPath(all, p[0])));
                }
            }
        }
        // Sample-based FALLBACK — only for fields the model can't type (plain scalars),
        // or EVERY field when the snapshot has no model. Never overrides the model.
        sampledDimensions(all, seen, c == null, out);
        return out;
    }

    // A field's dimension KIND from the MODEL, or null if the model can't classify it (a
    // plain scalar — left to the sample fallback). A vocab field's ref-vs-value shape is
    // read from ONE served value: a constraint vocab serves entities (REFERENCE), a
    // descriptive vocab collapses to display-name strings (VALUE).
    private Dimension.Kind modelKind(
            wikidata.explore.model.GeneratedFieldModel f, Collection<? extends Quizable> all) {
        if (f.type() == wikidata.explore.model.FieldType.BOOLEAN) {
            return Dimension.Kind.BOOLEAN;
        }
        if (f.type() == wikidata.explore.model.FieldType.ENTITY) {
            String t = f.entityClassName();
            if (t != null && model.findClass(t) != null) {
                return Dimension.Kind.REFERENCE;                 // targets a modeled class
            }
            if (t != null && model.findSelection(t)
                    instanceof wikidata.explore.model.VocabularySelection) {
                return kindForPath(all, f.name());               // targets a vocabulary
            }
        }
        return null;
    }

    /** REFERENCE if the path's first served value is a chip (Viewable), else VALUE. */
    private static Dimension.Kind kindForPath(
            Collection<? extends Quizable> pool, String path) {
        int seen = 0;
        for (Quizable q : pool) {
            Object v = resolveFirst(q, path);
            if (v != null) {
                return v instanceof objectview.Viewable
                        ? Dimension.Kind.REFERENCE : Dimension.Kind.VALUE;
            }
            if (++seen >= 500) {
                break;
            }
        }
        return Dimension.Kind.VALUE;
    }

    private static Object resolveFirst(Quizable q, String path) {
        List<Object> current = new ArrayList<>();
        current.add(q);
        for (String seg : path.split("\\.")) {
            List<Object> next = new ArrayList<>();
            for (Object o : current) {
                if (o instanceof objectview.Viewable v) {
                    Object val = objectview.field.FieldSet.of(v).read(seg);
                    if (val instanceof Collection<?> col) {
                        next.addAll(col);
                    } else if (val != null) {
                        next.add(val);
                    }
                }
            }
            current = next;
        }
        for (Object o : current) {
            if (o != null) {
                return o;
            }
        }
        return null;
    }

    // The old sample heuristic, now a FALLBACK: a reference / boolean / low-cardinality
    // scalar field the model didn't already declare. Refs and booleans are added only
    // when {@code unmodeled} (a legacy snapshot has no model to type them).
    private void sampledDimensions(
            Collection<? extends Quizable> all, Set<String> seen,
            boolean unmodeled, List<Dimension> out) {
        Map<String, Boolean> isRef = new LinkedHashMap<>();
        Map<String, Boolean> isBool = new LinkedHashMap<>();
        Map<String, Set<String>> distinct = new LinkedHashMap<>();
        int n = 0;
        for (Quizable q : all) {
            objectview.field.FieldSet fs = objectview.field.FieldSet.of(q);
            for (objectview.field.FieldRef fr : fs.fields()) {
                String name = fr.name();
                Object v = fs.read(name);
                isRef.merge(name, hasReference(v), Boolean::logicalOr);
                isBool.merge(name, v instanceof Boolean, Boolean::logicalAnd);
                distinct.computeIfAbsent(name, k -> new HashSet<>()).addAll(scalarKeys(v));
            }
            if (++n >= 300) {
                break;
            }
        }
        for (String name : isRef.keySet()) {
            if (seen.contains(name) || isImageKey(name) || structural.contains(name)) {
                continue;
            }
            boolean ref = Boolean.TRUE.equals(isRef.get(name));
            boolean bool = Boolean.TRUE.equals(isBool.get(name));
            if (unmodeled && ref) {
                out.add(new Dimension(name, name, Dimension.Kind.REFERENCE));
                seen.add(name);
            } else if (unmodeled && bool) {
                out.add(new Dimension(name, name, Dimension.Kind.BOOLEAN));
                seen.add(name);
            } else if (!ref && !bool) {
                int d = distinct.getOrDefault(name, Set.of()).size();
                if (d >= 2 && d <= MAX_BUCKETS) {
                    out.add(new Dimension(name, name, Dimension.Kind.VALUE));
                    seen.add(name);
                }
            }
        }
    }

    private static Facet<Quizable> facetFor(Dimension d) {
        return switch (d.kind()) {
            case REFERENCE -> Facet.reference(d.path(), d.label());
            case BOOLEAN -> Facet.mapped(d.path(), d.label(),
                    v -> FieldLabels.booleanBucket(v, d.label()));
            case VALUE -> Facet.field(d.path(), d.label());
        };
    }

    private static boolean hasReference(Object v) {
        if (v instanceof Quizable) {
            return true;
        }
        if (v instanceof Collection<?> c) {
            for (Object i : c) {
                if (i instanceof Quizable) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> scalarKeys(Object v) {
        Set<String> out = new HashSet<>();
        if (v instanceof Quizable || v == null) {
            return out;
        }
        if (v instanceof Collection<?> c) {
            for (Object i : c) {
                if (!(i instanceof Quizable) && i != null) {
                    out.add(String.valueOf(i));
                }
            }
        } else {
            out.add(String.valueOf(v));
        }
        return out;
    }

    private static boolean isImageKey(String name) {
        String n = name.toLowerCase();
        return n.contains("chart") || n.contains("image") || n.contains("img")
                || n.contains("photo") || n.contains("logo") || n.contains("portrait")
                || n.contains("flag");
    }
}
