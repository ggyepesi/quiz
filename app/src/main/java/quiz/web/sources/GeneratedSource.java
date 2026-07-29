package quiz.web.sources;

import objectview.field.DynamicFields;
import objectview.Viewable;
import objectview.facet.FacetGrouper;
import quiz.ViewableGroup;
import quiz.web.ViewableSource;
import quiz.web.ViewableStore;
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
 * snapshot — in the web, with <b>no per-domain code</b>. Snapshot fields remain
 * data until a view explicitly groups by them in Transform; the source never
 * guesses grouping dimensions from field type or cardinality.
 *
 * <p>This is the seam that lets the generation pipeline reach the existing web
 * client; the data is dynamic ({@link DynamicFields}), so rendering, field
 * listing, validation, and quizzes all work through the shared machinery.
 */
public class GeneratedSource implements ViewableSource {

    private final String type;
    private final File file;
    private final Set<String> structural;   // fields to hide (e.g. a reify class's "source")
    // Nested vocabulary paths {dottedPath, label} — e.g. {"nominee.type","type"},
    // {"forWork.genre","genre"} — retained for field coverage, never auto-grouped.
    private final List<String[]> nestedVocabularyPaths;
    private final wikidata.explore.model.GeneratedProjectModel model;   // for field expectations
    private List<WikidataDynamicObject> members;
    private ViewableGroup explicitGroups;
    private boolean explicitGroupsBuilt;

    public GeneratedSource(String type, File file) {
        this(type, file, (wikidata.explore.model.GeneratedProjectModel) null);
    }

    public GeneratedSource(String type, File file, File modelFile) {
        this(type, file, loadModel(modelFile));
    }

    private GeneratedSource(String type, File file,
                            wikidata.explore.model.GeneratedProjectModel model) {
        this.type = type;
        this.file = file;
        this.structural = structuralFor(type, model);
        this.nestedVocabularyPaths = nestedVocabularyPaths(type, model);
        this.model = model;
        // The card/field renderer (ViewableJson) hides the same structural fields.
        quiz.web.ViewableJson.registerStructural(type, this.structural);
    }

    // Nested vocabulary paths for a type: for each entity field F -> a modeled class C,
    // each entity field G on C whose target is a VOCABULARY yields the path F.G (e.g.
    // Nomination.nominee.type -> NomineeType). Direct fields are already included by
    // the owning class's normal field coverage, so only nested paths are added here.
    static List<String[]> nestedVocabularyPaths(
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

    // Structural (hidden) fields for a type: "groups" stores an explicitly authored
    // hierarchy as full membership paths; it is view structure, never a facet inferred
    // from data. A statement-reification class also carries a "source" back-reference
    // (provenance), not a user attribute. Same rule as SnapshotDomain.structuralFields.
    static Set<String> structuralFor(
            String type, wikidata.explore.model.GeneratedProjectModel model) {
        Set<String> structural = new LinkedHashSet<>();
        structural.add("groups");
        if (model != null) {
            wikidata.explore.model.GeneratedClassModel c = model.findClass(type);
            if (c != null && c.reifiesStatements()) {
                structural.add("source");
            }
        }
        return Set.copyOf(structural);
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
    public static void registerAll(ViewableStore store, String defaultType, File file)
            throws Exception {
        registerAll(store, defaultType, file, null);
    }

    public static void registerAll(ViewableStore store, String defaultType,
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
            store.register(new GeneratedSource(t, file, model));
        }
    }

    @Override
    public Collection<? extends Viewable> load() throws Exception {
        return members();
    }

    @Override
    public synchronized ViewableGroup rootGroup() throws Exception {
        if (!explicitGroupsBuilt) {
            explicitGroups = explicitGroupTree(members());
            explicitGroupsBuilt = true;
        }
        return explicitGroups;
    }

    /**
     * Rebuild a hand-authored group tree from ancestry-qualified memberships saved by
     * {@code ViewableToWdo}. This handles explicit structure only: ordinary fields such
     * as {@code version} and {@code currencies} still never become groups implicitly.
     */
    static ViewableGroup explicitGroupTree(
            Collection<? extends Viewable> members) {
        List<GroupMembership> memberships = new ArrayList<>();
        String commonRoot = null;
        boolean sameRoot = true;

        for (Viewable member : members) {
            Object value = objectview.field.FieldSet.of(member).read("groups");
            List<List<String>> paths = new ArrayList<>();
            collectGroupPaths(value, paths);
            for (List<String> segments : paths) {
                if (segments.isEmpty()) {
                    continue;
                }
                memberships.add(new GroupMembership(member, segments));
                String pathRoot = segments.get(0);
                if (commonRoot == null) {
                    commonRoot = pathRoot;
                } else if (!commonRoot.equals(pathRoot)) {
                    sameRoot = false;
                }
            }
        }
        if (memberships.isEmpty()) {
            return null;
        }

        // Manual domains normally share one root ("All"). If independent authored
        // roots occur, retain each beneath one synthetic universe instead of dropping
        // either hierarchy.
        ViewableGroup root = new ViewableGroup(
                sameRoot && commonRoot != null ? commonRoot : "All");
        for (GroupMembership membership : memberships) {
            ViewableGroup leaf = root;
            int start = sameRoot ? 1 : 0;
            for (int i = start; i < membership.segments().size(); i++) {
                leaf = leaf.getOrCreateChild(membership.segments().get(i));
            }
            leaf.addMember(membership.member());
        }
        return FacetGrouper.assignRoles(root);
    }

    private static void collectGroupPaths(
            Object value, List<List<String>> paths) {
        if (value instanceof Collection<?> collection) {
            for (Object membership : collection) {
                collectOneGroupPath(membership, paths);
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object membership : map.values()) {
                collectOneGroupPath(membership, paths);
            }
        } else {
            collectOneGroupPath(value, paths);
        }
    }

    private static void collectOneGroupPath(
            Object value, List<List<String>> paths) {
        if (value instanceof String path) {
            if (!path.isBlank()) {
                // Old snapshots stored a dotted identifier. New saves use segment
                // lists so labels containing dots remain lossless.
                paths.add(groupSegments(path));
            }
        } else if (value instanceof Collection<?> collection) {
            List<String> segments = new ArrayList<>();
            for (Object item : collection) {
                if (item instanceof String segment && !segment.isBlank()) {
                    segments.add(segment);
                }
            }
            if (!segments.isEmpty()) {
                paths.add(segments);
            }
        }
    }

    private static List<String> groupSegments(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("\\.")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private record GroupMembership(Viewable member, List<String> segments) {}

    /**
     * A snapshot does not declare grouping merely by containing fields. Grouping is a
     * view operation configured explicitly in Transform, so a raw/generated source has
     * no live re-faceting dimensions of its own.
     */
    @Override
    public List<Dimension> dimensions() {
        return List.of();
    }

    @Override
    public List<Coverage.FieldCoverage> coverage() throws Exception {
        Collection<? extends Viewable> all = load();
        List<Dimension> paths = coveragePaths(new ArrayList<>(all));
        Map<String, String> expectations = new LinkedHashMap<>();
        for (Dimension d : paths) {
            expectations.put(d.path(), expectationForPath(d.path()));
        }
        return Coverage.of(all, paths, expectations);
    }

    // The paths the validator checks coverage for: the served type's declared model
    // fields (so a field is checked even if the sample is homogeneous — e.g. Nominee.type
    // is all "human" among the first alphabetical nominees) plus nested vocabulary paths.
    // Validation is deliberately independent from grouping.
    private List<Dimension> coveragePaths(Collection<? extends Viewable> sample) {
        wikidata.explore.model.GeneratedClassModel c =
                model == null ? null : model.findClass(type);
        if (c == null) {
            return sampledCoveragePaths(sample);
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
        for (String[] p : nestedVocabularyPaths) {
            if (seen.add(p[0])) {
                out.add(new Dimension(p[1], p[0], Dimension.Kind.VALUE));
            }
        }
        return out;
    }

    /** Every observed top-level field is valid coverage input. Cardinality and value
     * count do not make it a grouping dimension. */
    private List<Dimension> sampledCoveragePaths(
            Collection<? extends Viewable> sample) {
        List<Dimension> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int count = 0;
        for (Viewable viewable : sample) {
            objectview.field.FieldSet fields =
                    objectview.field.FieldSet.of(viewable);
            for (objectview.field.FieldRef field : fields.fields()) {
                String name = field.name();
                if (name != null && !structural.contains(name) && seen.add(name)) {
                    out.add(new Dimension(name, name, Dimension.Kind.VALUE));
                }
            }
            if (++count >= 300) {
                break;
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

}
