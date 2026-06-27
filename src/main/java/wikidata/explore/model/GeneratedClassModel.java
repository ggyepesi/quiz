package wikidata.explore.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

public class GeneratedClassModel {

    private String className;

    // Single-inheritance base class (by name, resolved against the owning
    // project). A subclass's effective fields are the base's plus its own, and
    // it inherits the base's membership when it defines none — so a shared
    // Person base can be extended per domain (OscarActor adds filmography).
    // Blank = no base. Deliberately minimal: no private/final/abstract.
    private String baseClassName = "";

    // How many levels of child-object edges to traverse when generating THIS
    // class as a root (e.g. Constellation=1 to pull stars; Star=0). Stored with
    // the class so it's remembered per class.
    private int generationDepth = 1;

    private final FieldSourceMapping instanceMapping = new FieldSourceMapping();
    private final List<GeneratedFieldModel> fields = new ArrayList<>();

    // Explicit instance QIDs. When set, these entities ARE the class's instances
    // (a curated set, e.g. the Twelve Olympians or the Labours of Hercules,
    // seeded from a Wikipedia category/WikiProject). With no membership type
    // they stand alone; with a type they restrict it. Saved with the class.
    private final List<String> seedQids = new ArrayList<>();

    public GeneratedClassModel() {
        this("GeneratedClass");
    }

    public GeneratedClassModel(String className) {
        this.className =
                className == null || className.isBlank()
                        ? "GeneratedClass"
                        : className.trim();

        ensureNameField();
    }

    public String className() { return className; }

    public void className(String className) {
        this.className =
                className == null || className.isBlank()
                        ? "GeneratedClass"
                        : className.trim();
    }

    public int generationDepth() { return generationDepth; }
    public void generationDepth(int d) { this.generationDepth = Math.max(0, d); }

    public String baseClassName() { return baseClassName; }
    public void baseClassName(String v) {
        this.baseClassName = v == null ? "" : v.trim();
    }
    public boolean hasBase() { return !baseClassName.isEmpty(); }

    public FieldSourceMapping instanceMapping() {
        return instanceMapping;
    }

    /** Explicit instance QIDs that are (or restrict) this class's members. */
    public List<String> seedQids() {
        return seedQids;
    }

    /** This class's own (non-inherited) fields. */
    public List<GeneratedFieldModel> fields() {
        ensureNameField();
        return fields;
    }

    /**
     * Fields of this class including those inherited from its base chain
     * (resolved via {@code project}); a subclass field overrides an inherited
     * one with the same name. Falls back to {@link #fields()} when there is no
     * base or no project to resolve it against.
     */
    public List<GeneratedFieldModel> effectiveFields(GeneratedProjectModel project) {
        return effectiveFields(project, new HashSet<>());
    }

    private List<GeneratedFieldModel> effectiveFields(
            GeneratedProjectModel project, Set<String> visited) {
        ensureNameField();
        // visited guards against an extends-cycle (A extends B extends A).
        if (baseClassName.isEmpty() || project == null || !visited.add(className)) {
            return new ArrayList<>(fields);
        }
        GeneratedClassModel base = project.findClass(baseClassName);
        if (base == null) {
            return new ArrayList<>(fields);
        }
        // Base first, then own fields override an inherited one by name.
        LinkedHashMap<String, GeneratedFieldModel> merged = new LinkedHashMap<>();
        for (GeneratedFieldModel bf : base.effectiveFields(project, visited)) {
            if (bf != null && bf.name() != null) {
                merged.put(bf.name(), bf);
            }
        }
        for (GeneratedFieldModel of : fields) {
            if (of != null && of.name() != null) {
                merged.put(of.name(), of);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * The membership/source mapping to generate this class by: its own if it
     * defines one (non-blank source QID), otherwise the base's (so a subclass
     * inherits the base's membership unless it overrides it).
     */
    public FieldSourceMapping effectiveInstanceMapping(GeneratedProjectModel project) {
        if (!instanceMapping.sourceQid().isBlank()
                || baseClassName.isEmpty() || project == null) {
            return instanceMapping;
        }
        GeneratedClassModel base = project.findClass(baseClassName);
        if (base == null || base == this || className.equals(base.className())) {
            return instanceMapping;
        }
        return base.effectiveInstanceMapping(project);
    }

    public void ensureNameField() {
        for (GeneratedFieldModel f : fields) {
            if (f != null && f.isNameField()) {
                return;
            }
        }

        fields.add(0, GeneratedFieldModel.nameField());
    }

    public GeneratedClassModel copy() {
        GeneratedClassModel c = new GeneratedClassModel(className);

        c.generationDepth = generationDepth;
        c.baseClassName = baseClassName;
        c.instanceMapping.copyFrom(instanceMapping);
        c.seedQids.addAll(seedQids);

        c.fields.clear();
        for (GeneratedFieldModel f : fields) {
            if (f != null) {
                c.fields.add(f.copy());
            }
        }

        return c;
    }

    public GeneratedFieldModel addField(
            String name,
            FieldType type,
            FieldCardinality cardinality) {

        ensureNameField();

        GeneratedFieldModel f =
                new GeneratedFieldModel(name, type, cardinality);

        fields.add(f);
        return f;
    }

    @Override
    public String toString() {
        return className;
    }
}