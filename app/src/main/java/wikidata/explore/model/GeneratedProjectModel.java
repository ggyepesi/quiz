package wikidata.explore.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GeneratedProjectModel {

    private String name = "Generated Wikidata Project";
    private GeneratedClassModel rootClass;

    // How many levels of child-object edges generation should traverse. Stored
    // with the project so a saved model remembers it (depth 0 skips all child
    // edges, e.g. a constellation's stars).
    private int generationDepth = 1;

    private final List<GeneratedClassModel> classes = new ArrayList<>();

    // Named non-product configurations (vocabularies, populations, facets) that
    // classes/fields reference but which are never served — see Source. Empty for
    // every existing model; a domain opts into them as roles are pulled out of
    // "class" overloading.
    private final List<Source> sources = new ArrayList<>();

    public GeneratedProjectModel() {
        rootClass = new GeneratedClassModel("Constellation");
        classes.add(rootClass);
    }

    /**
     * The Constellation domain config distilled from the hand-built
     * tracer-bullet (see ConstellationSnapshotMain). Membership is "instance of
     * constellation" (P31 = Q8928); the data fields are the properties we found
     * worth keeping, each reached FROM the constellation (ROOT_TO_ITEM):
     *
     *   P1813 abbreviation  - IAU code; doubles as the membership filter that
     *                         drops obsolete/duplicate constellations.
     *   P2046 area          - solid angle in square degrees.
     *   P18   chart         - the Commons sky-chart image (name-blurred for quiz).
     *   P361  hemisphere     - "part of" the northern/southern sky (reference).
     *   P138  namedAfter     - the mythological/figurative source (reference).
     *
     * Neighbours (a self-referential Constellation collection) and per-star
     * brightness (P1215 magnitude, a Star sub-class) are intentionally left for
     * the workbench to add on top of this base.
     */
    public static GeneratedProjectModel constellationDemo() {
        GeneratedProjectModel p = new GeneratedProjectModel();
        p.name("Constellations");

        GeneratedClassModel c = new GeneratedClassModel("Constellation");
        c.instanceMapping().sourceQid("Q8928");
        c.instanceMapping().sourceLabel("constellation");
        c.instanceMapping().propertyPid("P31");
        c.instanceMapping().propertyLabel("instance of");
        c.instanceMapping().limit(200);

        field(c, "abbreviation", FieldType.STRING, FieldCardinality.SINGLE,
                "P1813", "IAU abbreviation", FieldRenderMode.INLINE);
        field(c, "area", FieldType.NUMBER, FieldCardinality.SINGLE,
                "P2046", "area (deg2)", FieldRenderMode.INLINE);
        field(c, "chart", FieldType.IMAGE, FieldCardinality.SINGLE,
                "P18", "image", FieldRenderMode.INLINE);
        field(c, "hemisphere", FieldType.ENTITY, FieldCardinality.SINGLE,
                "P361", "part of", FieldRenderMode.REFERENCE);
        field(c, "namedAfter", FieldType.ENTITY, FieldCardinality.SINGLE,
                "P138", "named after", FieldRenderMode.REFERENCE);

        p.rootClass(c);
        return p;
    }

    /**
     * Adds one data field reached from the root entity via {@code wdt:Pxxx}
     * (ROOT_TO_ITEM), with the property pre-filled so the workbench loads a
     * ready-to-run mapping rather than a blank row.
     */
    private static void field(
            GeneratedClassModel c,
            String name,
            FieldType type,
            FieldCardinality cardinality,
            String propertyPid,
            String propertyLabel,
            FieldRenderMode renderMode) {

        GeneratedFieldModel f = c.addField(name, type, cardinality);
        f.renderMode(renderMode);
        f.mapping().propertyPid(propertyPid);
        f.mapping().propertyLabel(propertyLabel);
        f.mapping().direction(RuleDirection.ROOT_TO_ITEM);
    }

    public String name() {
        return name;
    }

    public int generationDepth() {
        return generationDepth;
    }

    public void generationDepth(int depth) {
        this.generationDepth = Math.max(0, depth);
    }

    public void name(String name) {
        this.name =
                name == null || name.isBlank()
                        ? "Generated Wikidata Project"
                        : name.trim();
    }

    public GeneratedClassModel rootClass() {
        return rootClass;
    }

    public void rootClass(GeneratedClassModel rootClass) {
        GeneratedClassModel oldRoot = this.rootClass;

        this.rootClass =
                rootClass == null
                        ? new GeneratedClassModel("GeneratedClass")
                        : rootClass;


        if (oldRoot != null && oldRoot != this.rootClass) {
            classes.remove(oldRoot);
        }

        classes.remove(this.rootClass);
        classes.addFirst(this.rootClass);
    }

    public List<GeneratedClassModel> classes() {
        return Collections.unmodifiableList(classes);
    }

    /** The domain's named non-product Sources (vocabularies/populations/facets). */
    public List<Source> sources() {
        return Collections.unmodifiableList(sources);
    }

    public void addSource(Source source) {
        if (source != null) {
            sources.add(source);
        }
    }

    public Source findSource(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Source s : sources) {
            if (s != null && s.name().equalsIgnoreCase(name.trim())) {
                return s;
            }
        }
        return null;
    }

    /** Replaces this model's contents (name + classes) with another's, in
     *  place — so references held to this instance (the workbench panels) keep
     *  pointing at the same object after loading a saved model. */
    public void copyContentsFrom(GeneratedProjectModel other) {
        if (other == null) {
            return;
        }
        this.name = other.name;
        this.generationDepth = other.generationDepth;
        this.classes.clear();
        this.classes.addAll(other.classes);
        this.sources.clear();
        this.sources.addAll(other.sources);

        // Serialization has no object identity, so the root is written both as
        // `rootClass` and inside `classes` and deserializes as two separate
        // instances. Reconcile by name to the one already in the list, so the
        // root isn't duplicated (which showed Constellation twice in the tree).
        GeneratedClassModel root = other.rootClass;
        if (root != null) {
            GeneratedClassModel inList = findClass(root.className());
            if (inList != null) {
                root = inList;
            }
        }
        if (root == null) {
            root = classes.isEmpty()
                    ? new GeneratedClassModel("GeneratedClass")
                    : classes.getFirst();
        }
        this.rootClass = root;
        if (!classes.contains(rootClass)) {
            classes.addFirst(rootClass);
        }
    }

    public void addClass(GeneratedClassModel c) {
        if (c == null) {
            return;
        }


        if (!classes.contains(c)) {
            classes.add(c);
        }
    }

    public GeneratedClassModel findClass(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        for (GeneratedClassModel c : classes) {
            if (name.equals(c.className())) {
                return c;
            }
        }
        // Case-insensitive fallback: class-name references (a field's "Of class",
        // a "Reifies statements of") are user labels, and a rename that only
        // changed the case shouldn't silently break them.
        for (GeneratedClassModel c : classes) {
            if (name.equalsIgnoreCase(c.className())) {
                return c;
            }
        }

        return null;
    }

    public GeneratedClassModel getOrCreateClass(String name) {
        String clean =
                name == null || name.isBlank()
                        ? "GeneratedClass"
                        : name.trim();

        GeneratedClassModel existing = findClass(clean);

        if (existing != null) {
            return existing;
        }

        GeneratedClassModel created = new GeneratedClassModel(clean);
        addClass(created);
        return created;
    }

    /**
     * Deep snapshot for handing to a background generation run, so EDT
     * edits made while the run is in flight cannot tear it.
     */
    public GeneratedProjectModel copy() {
        GeneratedProjectModel c = new GeneratedProjectModel();
        c.name = name;
        c.generationDepth = generationDepth;
        c.classes.clear();
        c.rootClass = null;

        for (GeneratedClassModel cls : classes) {
            GeneratedClassModel copied = cls.copy();
            c.classes.add(copied);

            if (cls == rootClass) {
                c.rootClass = copied;
            }
        }

        if (c.rootClass == null && rootClass != null) {
            // The source can carry its root as a distinct object from its
            // same-named entry in `classes`: a saved model serializes the root
            // both as `rootClass` and inside `classes`, and Jackson deserializes
            // the two separately. Adopt the already-copied same-named class as the
            // root rather than adding a second copy — otherwise the copy ends up
            // with two classes sharing the root's name (a duplicate that fails
            // validation the moment it is compiled).
            for (GeneratedClassModel cls : c.classes) {
                if (cls.className().equalsIgnoreCase(rootClass.className())) {
                    c.rootClass = cls;
                    break;
                }
            }
            if (c.rootClass == null) {
                c.rootClass = rootClass.copy();
                c.classes.addFirst(c.rootClass);
            }
        }

        for (Source s : sources) {
            if (s != null) {
                c.sources.add(s.copy());
            }
        }

        return c;
    }

    public void removeClass(GeneratedClassModel c) {
        if (c == null || c == rootClass) {
            return;
        }

        classes.remove(c);
    }

    @Override
    public String toString() {
        return name;
    }
}