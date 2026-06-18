package wikidata.explore.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GeneratedProjectModel {

    private String name = "Generated Wikidata Project";
    private GeneratedClassModel rootClass;

    private final List<GeneratedClassModel> classes = new ArrayList<>();

    public GeneratedProjectModel() {
        rootClass = new GeneratedClassModel("Constellation");
        rootClass.ensureNameField();
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

    public void name(String name) {
        this.name =
                name == null || name.isBlank()
                        ? "Generated Wikidata Project"
                        : name.trim();
    }

    public GeneratedClassModel rootClass() {
        rootClass.ensureNameField();
        return rootClass;
    }

    public void rootClass(GeneratedClassModel rootClass) {
        GeneratedClassModel oldRoot = this.rootClass;

        this.rootClass =
                rootClass == null
                        ? new GeneratedClassModel("GeneratedClass")
                        : rootClass;

        this.rootClass.ensureNameField();

        if (oldRoot != null && oldRoot != this.rootClass) {
            classes.remove(oldRoot);
        }

        classes.remove(this.rootClass);
        classes.addFirst(this.rootClass);
    }

    public List<GeneratedClassModel> classes() {
        for (GeneratedClassModel c : classes) {
            c.ensureNameField();
        }

        return Collections.unmodifiableList(classes);
    }

    public void addClass(GeneratedClassModel c) {
        if (c == null) {
            return;
        }

        c.ensureNameField();

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
        c.classes.clear();
        c.rootClass = null;

        for (GeneratedClassModel cls : classes) {
            GeneratedClassModel copied = cls.copy();
            c.classes.add(copied);

            if (cls == rootClass) {
                c.rootClass = copied;
            }
        }

        if (c.rootClass == null) {
            c.rootClass = rootClass.copy();
            c.classes.addFirst(c.rootClass);
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