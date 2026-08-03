package wikidata.explore.model;

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

    // The statement property defines the class's production grain. A source
    // model class supplies the subject population from its extracted members.
    // The source class is structurally optional (blank = discover subjects
    // directly), but that direct-discovery loader is not yet implemented, so a
    // source class is currently required — see StatementClassSource.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private StatementClassSource statementSource;

    private int generationDepth = 1;

    private final FieldSourceMapping instanceMapping =
            new FieldSourceMapping();

    private final List<GeneratedFieldModel> fields = new ArrayList<>();
    private final List<String> seedQids = new ArrayList<>();

    private CanonicalSpec canonical = new CanonicalSpec();

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

    public StatementClassSource statementSource() {
        return statementSource;
    }

    public void statementSource(StatementClassSource value) {
        statementSource = value == null ? null : value.copy();
        if (statementSource != null) {
            // The source changes the identity CATEGORY, but it cannot choose the
            // natural-key fields here: callers commonly assign the source before
            // adding/mapping the statement value and qualifiers. Editors invoke
            // StatementCanonicalDefaults after those field semantics are known,
            // and persist the resulting list in canonical.keyFields.
            canonical.kind(CanonicalSpec.Kind.DERIVED);
        }
    }

    /**
     * Whether this class is produced from Wikidata statements.
     *
     * <p>Keys on the statement property, not on the source class — the property
     * is what defines a statement class. A source class is structurally optional
     * (its members would supply the subjects), but the direct-discovery loader
     * for the blank case is not yet implemented, so generation still requires
     * one.</p>
     */
    public boolean reifiesStatements() {
        StatementClassSource source = statementSource();
        return source != null && source.isConfigured();
    }

    public String statementPropertyPid() {
        StatementClassSource source = statementSource();
        return source == null ? "" : source.propertyPid();
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
        this.canonical = canonical == null ? new CanonicalSpec() : canonical;
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
        copy.instanceMapping.copyFrom(instanceMapping);
        copy.seedQids.addAll(seedQids);
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
