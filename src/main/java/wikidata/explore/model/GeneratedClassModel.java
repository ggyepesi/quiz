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

    // A subclass discriminator: a (property, value) pair that NARROWS the
    // inherited membership to instances that ALSO have ?value wdt:<pid> wd:<qid>
    // (e.g. Person = the nominee membership AND P31=human). Compiles to the node's
    // membership filter ON TOP of the inherited relation membership — the
    // intersection plain `extends` couldn't express. The property defaults to P31
    // (the entity's type, the common case) but can be any relation, so a subclass
    // can discriminate on any type-like field, not just instance-of. Blank = none.
    private String discriminatorPid = "";   // default P31 when blank
    private String discriminatorQid = "";

    // STATEMENT reification: when set, instances of THIS class are the statements
    // of `instanceMapping().propertyPid()` on each member of statementSourceClass
    // (e.g. Nomination = the P1411 statements of Oscarnominations). Its fields draw
    // from the statement: the value (ps:) and qualifiers (pq:, via
    // FieldSourceMapping.qualifierPid). Derived to a qualifier-load + reify at
    // generation (ModelStatementReifications), no Transform file. Blank = none.
    private String statementSourceClass = "";

    // How many levels of child-object edges to traverse when generating THIS
    // class as a root (e.g. Constellation=1 to pull stars; Star=0). Stored with
    // the class so it's remembered per class.
    private int generationDepth = 1;

    private final FieldSourceMapping instanceMapping = new FieldSourceMapping();
    private final List<GeneratedFieldModel> fields = new ArrayList<>();

    // Declared grouping dimensions (presentation-layer facets, vs subclasses which
    // change the schema). Fed to FacetGrouper via DomainFacets. Saved with the class.
    private final List<GeneratedFacet> facets = new ArrayList<>();

    // Explicit instance QIDs. When set, these entities ARE the class's instances
    // (a curated set, e.g. the Twelve Olympians or the Labours of Hercules,
    // seeded from a Wikipedia category/WikiProject). With no membership type
    // they stand alone; with a type they restrict it. Saved with the class.
    private final List<String> seedQids = new ArrayList<>();

    // How this class is canonicalized (identity + displayName). Nullable: a model
    // saved before canonicalization has none, and one is INFERRED on demand
    // (see effectiveCanonical) so old files load untouched. See
    // docs/canonicalization-model.md.
    private CanonicalSpec canonical;

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

    public String discriminatorPid() { return discriminatorPid; }
    public void discriminatorPid(String v) {
        this.discriminatorPid = v == null ? "" : v.trim();
    }
    public String discriminatorQid() { return discriminatorQid; }
    public void discriminatorQid(String v) {
        this.discriminatorQid = v == null ? "" : v.trim();
    }
    public boolean hasDiscriminator() {
        return discriminatorQid != null
                && discriminatorQid.trim().matches("(?i)Q\\d+");
    }

    public String statementSourceClass() { return statementSourceClass; }
    public void statementSourceClass(String v) {
        this.statementSourceClass = v == null ? "" : v.trim();
    }
    public boolean reifiesStatements() {
        return statementSourceClass != null && !statementSourceClass.isBlank();
    }
    /** The discriminator property, defaulting to P31 (instance of) when unset. */
    public String effectiveDiscriminatorPid() {
        String p = discriminatorPid == null ? "" : discriminatorPid.trim();
        return p.matches("(?i)P\\d+") ? p : "P31";
    }

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

    /** This class's declared grouping facets (presentation partitions). */
    public List<GeneratedFacet> facets() {
        return facets;
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

    /** The explicitly-set canonicalization, or null if none was saved (a legacy
     *  model). Prefer {@link #effectiveCanonical()} unless you specifically need
     *  to know whether it was set. */
    public CanonicalSpec canonical() { return canonical; }

    public void canonical(CanonicalSpec canonical) { this.canonical = canonical; }

    public boolean hasCanonical() { return canonical != null; }

    /** The canonicalization to use: the explicit one if set, else one inferred
     *  from the class's legacy shape (so pre-canonicalization models still resolve
     *  an identity + displayName). Inference is read-only — it does not mutate the
     *  model. */
    public CanonicalSpec effectiveCanonical() {
        return canonical != null ? canonical : inferCanonical();
    }

    // Proposal for a legacy class with no explicit CanonicalSpec: a statement-
    // reified class is DERIVED (identity = its single-valued fields as a grain key,
    // displayName = its first single-valued non-name field); anything else is a
    // WIKIDATA_ENTITY (qid + label). The reconfiguration assistant refines this;
    // here it just guarantees a sensible identity + displayName on load.
    private CanonicalSpec inferCanonical() {
        CanonicalSpec spec = new CanonicalSpec();

        if (reifiesStatements()) {
            spec.kind(CanonicalSpec.Kind.DERIVED);

            GeneratedFieldModel primary = null;
            for (GeneratedFieldModel f : fields) {
                if (f == null || f.isNameField()
                        || f.cardinality() == FieldCardinality.COLLECTION) {
                    continue;
                }
                if (primary == null) {
                    primary = f;
                }
                spec.keyFields().add(f.name());
            }

            if (primary != null) {
                spec.displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                        .displayNameField(primary.name());
            } else {
                spec.displayNameMode(CanonicalSpec.DisplayNameMode.LABEL);
            }
        } else {
            spec.kind(CanonicalSpec.Kind.WIKIDATA_ENTITY)
                    .displayNameMode(CanonicalSpec.DisplayNameMode.LABEL)
                    .labelLanguage("en");
        }

        return spec;
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
        c.discriminatorPid = discriminatorPid;
        c.discriminatorQid = discriminatorQid;
        c.statementSourceClass = statementSourceClass;
        c.instanceMapping.copyFrom(instanceMapping);
        c.seedQids.addAll(seedQids);
        c.facets.addAll(facets);
        c.canonical = canonical == null ? null : canonical.copy();

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
                new GeneratedFieldModel(reservedSafeFieldName(name), type, cardinality);

        fields.add(f);
        return f;
    }

    // `name` and `qid` are reserved for identity (see docs/canonicalization-model.md):
    // a DATA field must not shadow them (that's the OscarNominations sort-poison bug).
    // Keep the data under a safe name instead of rejecting it.
    private static String reservedSafeFieldName(String name) {
        if (name == null) {
            return "field";
        }
        String n = name.trim();
        return (n.equalsIgnoreCase("name") || n.equalsIgnoreCase("qid"))
                ? n + "Value"
                : n;
    }

    @Override
    public String toString() {
        return className;
    }
}