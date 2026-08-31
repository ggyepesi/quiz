package wikidata.explore.compiled;

import wikidata.explore.model.ClassKind;

import java.util.*;

/**
 * Immutable, inheritance-resolved class definition.
 */
public final class CompiledClass {

    private final String declarationId;
    private final String className;
    private final String displayClassName;
    private final String configuredBaseClassName;
    private final String baseClassName;
    private final String discriminatorPid;
    private final String discriminatorQid;
    private final int generationDepth;
    private final ClassKind classKind;

    /*
     * For an ordinary class this is its membership definition. For a statement
     * class it contains the statement-value constraints still represented by
     * FieldSourceMapping. The actual statement property/source class lives in
     * statementSource.
     */
    private final CompiledFieldSource sourceMapping;

    private final List<String> seedQids;
    private final CompiledCanonical canonical;
    private final CompiledStatementSource statementSource;
    private final CompiledAggregateSource aggregateSource;
    private final List<CompiledField> ownFields;
    private final List<CompiledField> effectiveFields;
    private final Map<String, CompiledField> fieldsByLowerName;

    public CompiledClass(
            String declarationId,
            String className,
            String displayClassName,
            String configuredBaseClassName,
            String baseClassName,
            String discriminatorPid,
            String discriminatorQid,
            int generationDepth,
            ClassKind classKind,
            CompiledFieldSource sourceMapping,
            List<String> seedQids,
            CompiledCanonical canonical,
            CompiledStatementSource statementSource,
            CompiledAggregateSource aggregateSource,
            List<CompiledField> ownFields,
            List<CompiledField> effectiveFields) {

        this.declarationId = clean(declarationId);
        this.className = clean(className);
        this.displayClassName = clean(displayClassName);
        this.configuredBaseClassName = clean(configuredBaseClassName);
        this.baseClassName = clean(baseClassName);
        this.discriminatorPid = clean(discriminatorPid);
        this.discriminatorQid = clean(discriminatorQid);
        this.generationDepth = Math.max(0, generationDepth);
        this.classKind = classKind == null ? ClassKind.SOURCE : classKind;
        this.sourceMapping = sourceMapping == null
                ? CompiledFieldSource.from(null)
                : sourceMapping;
        this.seedQids = seedQids == null ? List.of() : List.copyOf(seedQids);
        this.canonical = canonical == null
                ? CompiledCanonical.from(null)
                : canonical;
        this.statementSource = statementSource;
        this.aggregateSource = aggregateSource;
        this.ownFields = ownFields == null ? List.of() : List.copyOf(ownFields);
        this.effectiveFields = effectiveFields == null
                ? List.of()
                : List.copyOf(effectiveFields);

        LinkedHashMap<String, CompiledField> index = new LinkedHashMap<>();
        for (CompiledField field : this.effectiveFields) {
            index.putIfAbsent(
                    field.name().toLowerCase(Locale.ROOT),
                    field);
        }
        fieldsByLowerName = Collections.unmodifiableMap(index);
    }

    public String declarationId() { return declarationId; }
    public String className() { return className; }
    public String displayClassName() { return displayClassName; }
    public String configuredBaseClassName() { return configuredBaseClassName; }
    public String baseClassName() { return baseClassName; }
    public boolean hasBase() { return !baseClassName.isBlank(); }
    public String discriminatorPid() { return discriminatorPid; }
    public String discriminatorQid() { return discriminatorQid; }
    public boolean hasDiscriminator() {
        return discriminatorQid.matches("(?i)Q\\d+");
    }
    public int generationDepth() { return generationDepth; }
    public ClassKind classKind() { return classKind; }
    public boolean identityFromSource() { return classKind.identityFromSource(); }
    public CompiledFieldSource sourceMapping() { return sourceMapping; }

    /**
     * Compatibility name for the first RuleTreeCompiler migration.
     */
    @Deprecated
    public CompiledFieldSource membership() { return sourceMapping; }

    public List<String> seedQids() { return seedQids; }
    public CompiledCanonical canonical() { return canonical; }
    public CompiledStatementSource statementSource() { return statementSource; }
    public CompiledAggregateSource aggregateSource() { return aggregateSource; }
    public boolean aggregateClass() {
        return aggregateSource != null && aggregateSource.configured();
    }
    public boolean statementClass() {
        return statementSource != null && statementSource.configured();
    }
    public List<CompiledField> ownFields() { return ownFields; }
    public List<CompiledField> effectiveFields() { return effectiveFields; }

    public Optional<CompiledField> field(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                fieldsByLowerName.get(
                        name.trim().toLowerCase(Locale.ROOT)));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
