package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

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
    private String discriminatorPid = "";
    private String discriminatorQid = "";
    private String alias = "";

    // Explicitly groups the two values that define a statement class:
    // the source model class and the Wikidata property whose statements are
    // promoted into instances of this class.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private StatementClassSource statementSource;

    // Legacy JSON compatibility. Older model files stored the source class here
    // and the statement property in instanceMapping.propertyPid().
    @Deprecated
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String statementSourceClass = "";

    private int generationDepth = 1;

    private final FieldSourceMapping instanceMapping =
            new FieldSourceMapping();

    private final List<GeneratedFieldModel> fields = new ArrayList<>();
    private final List<GeneratedFacet> facets = new ArrayList<>();
    private final List<String> seedQids = new ArrayList<>();

    private CanonicalSpec canonical;

    public GeneratedClassModel() {
        this("GeneratedClass");
    }

    public GeneratedClassModel(String className) {
        className(className);
    }

    public String className() {
        return className;
    }

    public void className(String className) {
        this.className =
                className == null || className.isBlank()
                        ? "GeneratedClass"
                        : className.trim();
    }

    public int generationDepth() {
        return generationDepth;
    }

    public void generationDepth(int depth) {
        generationDepth = Math.max(0, depth);
    }

    public String alias() {
        return alias == null ? "" : alias;
    }

    public void alias(String value) {
        alias = clean(value);
    }

    public String displayClassName() {
        return alias().isBlank() ? className : alias();
    }

    public String baseClassName() {
        return baseClassName;
    }

    public void baseClassName(String value) {
        baseClassName = clean(value);
    }

    public boolean hasBase() {
        return !baseClassName.isEmpty();
    }

    public String discriminatorPid() {
        return discriminatorPid;
    }

    public void discriminatorPid(String value) {
        discriminatorPid = clean(value);
    }

    public String discriminatorQid() {
        return discriminatorQid;
    }

    public void discriminatorQid(String value) {
        discriminatorQid = clean(value);
    }

    public boolean hasDiscriminator() {
        return discriminatorQid.matches("(?i)Q\\d+");
    }

    public String effectiveDiscriminatorPid() {
        String pid = clean(discriminatorPid);
        return pid.matches("(?i)P\\d+") ? pid : "P31";
    }

    /**
     * Returns the explicit statement source. For an old model, a compatibility
     * view is synthesized from statementSourceClass and instanceMapping.
     */
    public StatementClassSource statementSource() {
        if (statementSource != null) {
            return statementSource;
        }
        if (statementSourceClass == null
                || statementSourceClass.isBlank()) {
            return null;
        }
        return new StatementClassSource(
                statementSourceClass,
                instanceMapping.propertyPid());
    }

    /**
     * Makes the explicit representation authoritative. The property is also
     * mirrored into instanceMapping temporarily so consumers not yet migrated
     * continue to work while the refactor is introduced incrementally.
     */
    public void statementSource(StatementClassSource value) {
        statementSource = value == null ? null : value.copy();

        if (statementSource == null) {
            statementSourceClass = "";
            return;
        }

        statementSourceClass = "";

        if (statementSource.hasProperty()) {
            instanceMapping.propertyPid(statementSource.propertyPid());
        }
    }

    public boolean reifiesStatements() {
        StatementClassSource source = statementSource();
        return source != null && source.hasSourceClass();
    }

    public String statementPropertyPid() {
        StatementClassSource source = statementSource();
        if (source != null && !source.propertyPid().isBlank()) {
            return source.propertyPid();
        }
        return instanceMapping.propertyPid();
    }

    @Deprecated
    @JsonIgnore
    public String statementSourceClass() {
        StatementClassSource source = statementSource();
        return source == null ? "" : source.sourceClassName();
    }

    @Deprecated
    public void statementSourceClass(String value) {
        statementSourceClass = clean(value);

        if (statementSource != null) {
            statementSource.sourceClassName(statementSourceClass);
            statementSourceClass = "";
        }
    }

    public FieldSourceMapping instanceMapping() {
        return instanceMapping;
    }

    public List<String> seedQids() {
        return seedQids;
    }

    public List<GeneratedFieldModel> fields() {
        return fields;
    }

    public List<GeneratedFacet> facets() {
        return facets;
    }

    public List<GeneratedFieldModel> effectiveFields(
            GeneratedProjectModel project) {
        return effectiveFields(project, new HashSet<>());
    }

    private List<GeneratedFieldModel> effectiveFields(
            GeneratedProjectModel project,
            Set<String> visited) {

        if (baseClassName.isEmpty()
                || project == null
                || !visited.add(className)) {
            return new ArrayList<>(fields);
        }

        GeneratedClassModel base = project.findClass(baseClassName);
        if (base == null) {
            return new ArrayList<>(fields);
        }

        LinkedHashMap<String, GeneratedFieldModel> merged =
                new LinkedHashMap<>();

        for (GeneratedFieldModel inherited
                : base.effectiveFields(project, visited)) {
            if (inherited != null && inherited.name() != null) {
                merged.put(inherited.name(), inherited);
            }
        }

        for (GeneratedFieldModel own : fields) {
            if (own != null && own.name() != null) {
                merged.put(own.name(), own);
            }
        }

        return new ArrayList<>(merged.values());
    }

    public FieldSourceMapping effectiveInstanceMapping(
            GeneratedProjectModel project) {

        if (!instanceMapping.sourceQid().isBlank()
                || baseClassName.isEmpty()
                || project == null) {
            return instanceMapping;
        }

        GeneratedClassModel base = project.findClass(baseClassName);
        if (base == null
                || base == this
                || className.equals(base.className())) {
            return instanceMapping;
        }

        return base.effectiveInstanceMapping(project);
    }

    public CanonicalSpec canonical() {
        return canonical;
    }

    public void canonical(CanonicalSpec canonical) {
        this.canonical = canonical;
    }

    public boolean hasCanonical() {
        return canonical != null;
    }

    public CanonicalSpec effectiveCanonical() {
        return canonical != null ? canonical : inferCanonical();
    }

    /**
     * Infers the old runtime identity without mutating an old model.
     *
     * <p>Statement reification historically treated DATE qualifiers as
     * attributes rather than identity, because the date is often missing on
     * one denormalized copy of an otherwise identical statement. Explicit
     * legacy inDedupKey values still win. This inference is now exposed through
     * CanonicalSpec so runtime code has one source of truth.</p>
     */
    private CanonicalSpec inferCanonical() {
        CanonicalSpec spec = new CanonicalSpec();

        if (!reifiesStatements()) {
            return spec.kind(CanonicalSpec.Kind.WIKIDATA_ENTITY)
                       .displayNameMode(
                               CanonicalSpec.DisplayNameMode.LABEL)
                       .labelLanguage("en");
        }

        spec.kind(CanonicalSpec.Kind.DERIVED);

        GeneratedFieldModel primary = null;
        boolean hasExplicitDedupFlags = fields.stream()
                                              .filter(java.util.Objects::nonNull)
                                              .map(GeneratedFieldModel::mapping)
                                              .anyMatch(mapping ->
                                                                mapping.inDedupKey() != null);

        for (GeneratedFieldModel field : fields) {
            if (field == null
                    || field.isNameField()
                    || field.cardinality()
                    == FieldCardinality.COLLECTION
                    // Derived fields (COMPANION_MATCH flags like `won`, INVERT
                    // reverse refs) are produced AFTER reify — they must not enter
                    // the identity key, or e.g. won=null vs won=<x> would split the
                    // work/person copies of one statement so they never collapse.
                    || field.mapping().productionKind()
                    != FieldProductionKind.AUTO) {
                continue;
            }

            if (primary == null) {
                primary = field;
            }

            if (hasExplicitDedupFlags) {
                if (Boolean.TRUE.equals(
                        field.mapping().inDedupKey())) {
                    spec.keyFields().add(field.name());
                }
            } else if (field.type() != FieldType.DATE) {
                // Preserve the legacy statement-reification default:
                // ceremony/event dates describe an event but do not distinguish
                // the two denormalized copies used to construct that event.
                spec.keyFields().add(field.name());
            }
        }

        if (primary != null) {
            spec.displayNameMode(
                        CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField(primary.name());
        } else {
            spec.displayNameMode(
                    CanonicalSpec.DisplayNameMode.LABEL);
        }

        return spec;
    }

    public GeneratedClassModel copy() {
        GeneratedClassModel copy =
                new GeneratedClassModel(className);

        copy.generationDepth = generationDepth;
        copy.baseClassName = baseClassName;
        copy.discriminatorPid = discriminatorPid;
        copy.discriminatorQid = discriminatorQid;
        copy.alias = alias;

        copy.statementSource =
                statementSource == null
                        ? null
                        : statementSource.copy();
        copy.statementSourceClass = statementSourceClass;

        copy.instanceMapping.copyFrom(instanceMapping);
        copy.seedQids.addAll(seedQids);
        copy.facets.addAll(facets);
        copy.canonical =
                canonical == null ? null : canonical.copy();

        for (GeneratedFieldModel field : fields) {
            if (field != null && !field.isNameField()) {
                copy.fields.add(field.copy());
            }
        }

        return copy;
    }

    public GeneratedFieldModel addField(
            String name,
            FieldType type,
            FieldCardinality cardinality) {

        GeneratedFieldModel field =
                new GeneratedFieldModel(
                        reservedSafeFieldName(name),
                        type,
                        cardinality);
        fields.add(field);
        return field;
    }

    private static String reservedSafeFieldName(String name) {
        if (name == null) {
            return "field";
        }

        String trimmed = name.trim();
        return trimmed.equalsIgnoreCase("name")
                || trimmed.equalsIgnoreCase("qid")
                ? trimmed + "Value"
                : trimmed;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        return className;
    }
}
