package wikidata.explore.model;

import datasource.schema.FieldType;

import com.fasterxml.jackson.annotation.JsonInclude;
import datasource.api.SourceRecipe;
import datasource.api.SourceBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

public class GeneratedClassModel {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String declarationId = "";
    private String className;

    // Single-inheritance base class (by name, resolved against the owning
    // project). A subclass's effective fields are the base's plus its own, and
    // it inherits the base's membership when it defines none — so a shared
    // Person base can be extended per domain (OscarActor adds filmography).
    // Blank = no base. Deliberately minimal: no private/final/abstract.
    private String baseClassName = "";
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String baseClassId = "";

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

    /** The model this class is imported from, empty when the class is this project's
     *  own. An imported class is configuration owned elsewhere: it is shown and used
     *  here, and edited only where it lives. Copying sets nothing here — a copy belongs
     *  to whoever copied it, and its resemblance to the original is a starting point,
     *  not a claim. */
    private String importedFrom = "";

    /** Explicit population kind for a component class. The owner is intentionally
     * not stored here: every ENTITY field targeting this class is a production site. */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private ClassKind classKind = ClassKind.SOURCE;

    // The statement property defines the class's production grain. A source
    // model class supplies the subject population from its extracted members.
    // The source class is structurally optional (blank = discover subjects
    // directly), but that direct-discovery loader is not yet implemented, so a
    // source class is currently required — see StatementClassSource.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private StatementClassSource statementSource;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AggregateClassSource aggregateSource;

    private int generationDepth = 1;

    private final FieldSourceMapping instanceMapping =
            new FieldSourceMapping();

    private final List<GeneratedFieldModel> fields = new ArrayList<>();
    private final List<String> seedQids = new ArrayList<>();

    /** Persisted provider/operation view of this class's population configuration.
     *  During migration Wikidata mappings remain the editable truth; see
     *  {@link #populationSource()} and {@link PopulationSourceBindings}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private SourceRecipe populationSource;

    /** Explicit population, identity and naming sources for this class. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<SourceBinding> sourceBindings = new ArrayList<>();

    private CanonicalSpec canonical = new CanonicalSpec();

    public GeneratedClassModel() {
        className("GeneratedClass");
    }

    public GeneratedClassModel(String className) {
        declarationId = DeclarationIds.create();
        className(className);
    }

    public String declarationId() { return DeclarationIds.clean(declarationId); }
    public void declarationId(String value) { declarationId = DeclarationIds.clean(value); }
    void ensureDeclarationId(String projectName) {
        if (declarationId().isBlank()) {
            declarationId = DeclarationIds.legacy(projectName, "class", className());
        }
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

    /** The model that owns this class; empty when the class is this project's own. */
    public String importedFrom() {
        return importedFrom == null ? "" : importedFrom;
    }

    public void importedFrom(String value) {
        importedFrom = clean(value);
    }

    /**
     * How this class reads when the project it came from matters: {@code People.Person}
     * for an imported class, plain {@code Person} for this project's own. A display
     * form derived from the owner — the class is referenced by its own name everywhere,
     * so no stored qualified name can fall out of step with this one.
     */
    public String qualifiedClassName() {
        return isImported() ? importedFrom() + "." + className : className;
    }

    /**
     * Whether this class is owned by another model. An imported class is not edited
     * here at all — not its fields, not its name, not its membership. The single
     * question every editor asks, so where a class may be changed is decided once.
     *
     * <p>Copying does not make a class imported. A copy is the copier's and carries no
     * claim from wherever it was copied.
     */
    public boolean isImported() {
        return !importedFrom().isBlank();
    }

    public String displayClassName() {
        return alias().isBlank() ? className : alias();
    }

    public boolean ownedClass() {
        return classKind() == ClassKind.OWNED;
    }

    public void ownedClass(boolean value) {
        classKind(value ? ClassKind.OWNED : ClassKind.SOURCE);
    }

    /**
     * What kind of class this is — stored, and answered from storage.
     *
     * <p>It used to be stored for OWNED and AGGREGATE and RECOMPUTED for the other two:
     * "Statement" meant "has a statement property filled in" rather than "was declared a
     * statement class". So a class could not be a statement class before it had a
     * property, and switching a new class to Statement did not stick — the setter wrote
     * the value and this method immediately overrode it. There was then no way to reach
     * the editor that picks the property, because reaching it required the kind to hold.
     *
     * <p>Being declared a statement class and having a property yet are two questions.
     * The second belongs to validation, which already refuses a statement class without
     * one before generation, and to {@link #reifiesStatements()}.
     */
    public ClassKind classKind() {
        return classKind == null ? ClassKind.SOURCE : classKind;
    }

    /**
     * Sets the kind, and clears whatever the new kind cannot have.
     *
     * <p>A switch rather than a chain of ifs: a kind added to the enum and forgotten
     * here is a compile error, not a class that silently keeps the population of the
     * kind it used to be.
     */
    public void classKind(ClassKind value) {
        classKind = value == null ? ClassKind.SOURCE : value;
        switch (classKind) {
            case SOURCE -> aggregateSource = null;
            case STATEMENT -> aggregateSource = null;
            case OWNED -> {
                aggregateSource = null;
                clearIndependentPopulation();
            }
            case AGGREGATE -> clearIndependentPopulation();
        }
    }

    /** Clears query/seed/reification state that would independently populate a class. */
    public void clearIndependentPopulation() {
        statementSource(null);
        populationSource = null;
        seedQids.clear();
        instanceMapping.sourceQid("");
        instanceMapping.sourceLabel("");
        instanceMapping.propertyPid("");
        instanceMapping.propertyLabel("");
        instanceMapping.additionalTypeQids().clear();
        instanceMapping.excludedTypeQids().clear();
    }

    public String baseClassName() {
        return baseClassName;
    }

    public void baseClassName(String value) {
        baseClassName = clean(value);
        baseClassId = "";
    }

    public String baseClassId() { return DeclarationIds.clean(baseClassId); }
    public void baseClassId(String value) { baseClassId = DeclarationIds.clean(value); }
    void baseClassReference(String id, String name) {
        baseClassId = DeclarationIds.clean(id);
        baseClassName = clean(name);
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

    public AggregateClassSource aggregateSource() {
        return aggregateSource;
    }

    public void aggregateSource(AggregateClassSource value) {
        aggregateSource = value == null ? null : value.copy();

        if (aggregateSource != null) {
            statementSource = null;
            classKind = ClassKind.AGGREGATE;
            canonical().keyFields().clear();
        }
    }

    /**
     * Assigning a statement source makes this a STATEMENT class, and now records it.
     *
     * <p>The sentence below was already here and was already true; the kind was simply
     * recomputed from whether a property had been filled in, so nothing had to store it.
     * A class could therefore not be a statement class before it had a property — and
     * the editor that picks the property is the one you reach by being one.
     *
     * <p>Having a source and having a property are separate: {@link #reifiesStatements()}
     * still asks the second, which is what generation and validation need.
     *
     * <p>The identity regime follows from the kind rather than being set alongside it.
     * The natural-key FIELDS still cannot be chosen here: callers commonly assign the
     * source before adding the statement value and qualifiers, so editors invoke
     * StatementCanonicalDefaults once those semantics are known.
     */
    public void statementSource(StatementClassSource value) {
        statementSource = value == null ? null : value.copy();
        if (statementSource != null) {
            aggregateSource = null;
            classKind = ClassKind.STATEMENT;
        } else if (classKind == ClassKind.STATEMENT) {
            // Taking the source away takes the kind with it: a statement class with no
            // statement source is not a kind, it is a leftover.
            classKind = ClassKind.SOURCE;
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

    /** The datasource offering which produces this class's population. */
    public SourceRecipe populationSource() {
        return PopulationSourceBindings.effective(this);
    }

    public List<SourceBinding> sourceBindings() { return sourceBindings; }

    /** Bind this class to an executable population recipe. The migration adapter
     *  writes the established mapping fields too, so the current compiler executes
     *  exactly what the catalogue recipe describes. */
    public void populationSource(SourceRecipe value) {
        PopulationSourceBindings.assign(this, value);
    }

    SourceRecipe declaredPopulationSource() {
        return populationSource;
    }

    void declaredPopulationSource(SourceRecipe value) {
        populationSource = value;
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

        // Owned classes may extend another class for its fields/schema, but their
        // population always comes from owning fields, never from the base's query.
        if (ownedClass()
                || !instanceMapping.sourceQid().isBlank()
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

    /** Never null. A persisted model can carry an explicit {@code "canonical": null}
     *  (Jackson writes the field directly, bypassing the setter's guard), and every
     *  consumer — validation, canonicalization, codegen — requires the spec. The
     *  model repairs it here, once, instead of each caller null-checking; the default
     *  is identity-by-qid with a label display, which is what a class without an
     *  explicit spec means. */
    public CanonicalSpec canonical() {
        if (canonical == null) {
            canonical = new CanonicalSpec();
        }
        return canonical;
    }

    public void canonical(CanonicalSpec canonical) {
        this.canonical = canonical == null ? new CanonicalSpec() : canonical;
    }

    public GeneratedClassModel copy() {
        GeneratedClassModel copy =
                new GeneratedClassModel(className);

        copy.declarationId = declarationId;
        copy.generationDepth = generationDepth;
        copy.baseClassName = baseClassName;
        copy.baseClassId = baseClassId;
        copy.discriminatorPid = discriminatorPid;
        copy.discriminatorQid = discriminatorQid;
        copy.alias = alias;
        copy.importedFrom = importedFrom;
        copy.classKind = classKind;

        copy.statementSource =
                statementSource == null
                        ? null
                        : statementSource.copy();
        copy.aggregateSource = aggregateSource == null ? null : aggregateSource.copy();
        copy.instanceMapping.copyFrom(instanceMapping);
        copy.seedQids.addAll(seedQids);
        copy.populationSource = populationSource;
        copy.sourceBindings.addAll(sourceBindings);
        copy.canonical = canonical().copy();   // never null — see canonical()

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
        if (type == FieldType.DATE) {
            // A field created now has no legacy projection to preserve, so it keeps
            // what the source states. YEAR is the answer to a different question —
            // what a model written before qualifier dates existed meant — and that
            // one is answered by the absence of this value on disk, not here.
            field.mapping().qualifierDateMode(QualifierDateMode.DATE);
        }
        fields.add(field);
        return field;
    }

    private static String reservedSafeFieldName(String name) {
        if (name == null) {
            return "field";
        }

        String trimmed = name.trim();
        return isReservedFieldName(trimmed)
                ? trimmed + "Value"
                : trimmed;
    }

    /** Built-in identity/display properties that cannot also be model data fields. */
    public static boolean isReservedFieldName(String name) {
        if (name == null) return false;
        String clean = name.trim();
        return clean.equalsIgnoreCase("name") || clean.equalsIgnoreCase("qid");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        return className;
    }
}
