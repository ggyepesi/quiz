package wikidata.explore.compiled;

import java.util.*;

/** Immutable, inheritance-resolved class definition. */
public final class CompiledClass {
    private final String className;
    private final String displayClassName;
    private final String configuredBaseClassName;
    private final String baseClassName;
    private final String discriminatorPid;
    private final String discriminatorQid;
    private final int generationDepth;
    private final CompiledFieldSource membership;
    private final List<String> seedQids;
    private final List<CompiledFacet> facets;
    private final CompiledCanonical canonical;
    private final CompiledStatementSource statementSource;
    private final List<CompiledField> ownFields;
    private final List<CompiledField> effectiveFields;
    private final Map<String, CompiledField> fieldsByLowerName;

    public CompiledClass(
            String className, String displayClassName,
            String configuredBaseClassName, String baseClassName,
            String discriminatorPid, String discriminatorQid,
            int generationDepth, CompiledFieldSource membership,
            List<String> seedQids, List<CompiledFacet> facets,
            CompiledCanonical canonical,
            CompiledStatementSource statementSource,
            List<CompiledField> ownFields, List<CompiledField> effectiveFields) {
        this.className = clean(className);
        this.displayClassName = clean(displayClassName);
        this.configuredBaseClassName = clean(configuredBaseClassName);
        this.baseClassName = clean(baseClassName);
        this.discriminatorPid = clean(discriminatorPid);
        this.discriminatorQid = clean(discriminatorQid);
        this.generationDepth = Math.max(0, generationDepth);
        this.membership = membership == null
                ? CompiledFieldSource.from(null) : membership;
        this.seedQids = seedQids == null ? List.of() : List.copyOf(seedQids);
        this.facets = facets == null ? List.of() : List.copyOf(facets);
        this.canonical = canonical == null
                ? CompiledCanonical.from(null) : canonical;
        this.statementSource = statementSource;
        this.ownFields = ownFields == null ? List.of() : List.copyOf(ownFields);
        this.effectiveFields = effectiveFields == null
                ? List.of() : List.copyOf(effectiveFields);
        LinkedHashMap<String, CompiledField> index = new LinkedHashMap<>();
        for (CompiledField field : this.effectiveFields) {
            index.putIfAbsent(field.name().toLowerCase(Locale.ROOT), field);
        }
        this.fieldsByLowerName = Collections.unmodifiableMap(index);
    }

    public String className() { return className; }
    public String displayClassName() { return displayClassName; }
    public String configuredBaseClassName() { return configuredBaseClassName; }
    public String baseClassName() { return baseClassName; }
    public boolean hasBase() { return !baseClassName.isBlank(); }
    public String discriminatorPid() { return discriminatorPid; }
    public String discriminatorQid() { return discriminatorQid; }
    public boolean hasDiscriminator() { return discriminatorQid.matches("(?i)Q\\d+"); }
    public int generationDepth() { return generationDepth; }
    public CompiledFieldSource membership() { return membership; }
    public List<String> seedQids() { return seedQids; }
    public List<CompiledFacet> facets() { return facets; }
    public CompiledCanonical canonical() { return canonical; }
    public CompiledStatementSource statementSource() { return statementSource; }
    public boolean statementClass() { return statementSource != null && statementSource.configured(); }
    public List<CompiledField> ownFields() { return ownFields; }
    public List<CompiledField> effectiveFields() { return effectiveFields; }

    public Optional<CompiledField> field(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Optional.ofNullable(fieldsByLowerName.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
