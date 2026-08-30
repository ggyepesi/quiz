package wikidata.explore.transform;

import wikidata.WikidataIds;

import java.util.List;

/**
 * A Load-time construct: load a property's STATEMENTS (not just the direct
 * claim) with their qualifiers, as nested statement objects on each entity.
 *
 * <p>The direct claim {@code ?film wdt:P166 ?award} loses everything but the
 * award. The statement path {@code ?film p:P166 ?st . ?st ps:P166 ?award} lets
 * us also read {@code ?st pq:P585 ?year} (ceremony year), {@code pq:P1686 ?for}
 * (for-work). This config drives {@link QualifierLoader}, which attaches one
 * {@code statementType} object per statement under {@code entityType.statementField},
 * holding the main value under {@code valueField} plus each qualifier.
 *
 * <p>e.g. {@code QualifierLoadConfig("Film", "P166", "awards", "AwardStatement",
 * "award", [P585→year YEAR, P1686→forWork ENTITY])}. A {@code reify(promote)}
 * construct then lifts those statements into top-level {@code Nomination} events.
 */
public record QualifierLoadConfig(
        String entityType,
        String propertyPid,
        String statementField,
        String statementType,
        String valueField,
        String valueTypeQid,
        List<Qualifier> qualifiers,
        List<String> valueQids,
        List<String> discoveryValueQids,
        boolean discoverSubjects,
        String valueDomainName) {

    /** Previous canonical form: one value domain both discovers subjects and filters
     *  their statements. Explicit vocabularies intentionally retain that behaviour. */
    public QualifierLoadConfig(String entityType, String propertyPid,
            String statementField, String statementType, String valueField,
            String valueTypeQid, List<Qualifier> qualifiers, List<String> valueQids,
            boolean discoverSubjects, String valueDomainName) {
        this(entityType, propertyPid, statementField, statementType, valueField,
                valueTypeQid, qualifiers, valueQids, valueQids, discoverSubjects,
                valueDomainName);
    }

    /** Back-compat 9-arg form (no named value domain — used for logging only). */
    public QualifierLoadConfig(String entityType, String propertyPid,
            String statementField, String statementType, String valueField,
            String valueTypeQid, List<Qualifier> qualifiers, List<String> valueQids,
            boolean discoverSubjects) {
        this(entityType, propertyPid, statementField, statementType, valueField,
                valueTypeQid, qualifiers, valueQids, valueQids, discoverSubjects, "");
    }

    /** Back-compat 8-arg form: subjects already exist in the pool (no discovery). */
    public QualifierLoadConfig(String entityType, String propertyPid,
            String statementField, String statementType, String valueField,
            String valueTypeQid, List<Qualifier> qualifiers, List<String> valueQids) {
        this(entityType, propertyPid, statementField, statementType, valueField,
                valueTypeQid, qualifiers, valueQids, valueQids, false, "");
    }

    /** Back-compat 7-arg form (no explicit value QIDs). */
    public QualifierLoadConfig(String entityType, String propertyPid,
            String statementField, String statementType, String valueField,
            String valueTypeQid, List<Qualifier> qualifiers) {
        this(entityType, propertyPid, statementField, statementType, valueField,
                valueTypeQid, qualifiers, List.of(), List.of(), false, "");
    }

    /** Back-compat 6-arg form (no value-type filter). */
    public QualifierLoadConfig(String entityType, String propertyPid,
            String statementField, String statementType, String valueField,
            List<Qualifier> qualifiers) {
        this(entityType, propertyPid, statementField, statementType, valueField,
                "", qualifiers, List.of(), List.of(), false, "");
    }

    /** A human label for where the value domain came from (a VOCABULARY Selection
     *  name), for the discovery log; blank => describe it generically. */
    public String valueDomainLabel() {
        if (valueDomainName != null && !valueDomainName.isBlank()) {
            return "Selection '" + valueDomainName + "'";
        }
        // "Seeds" only where they seed something. Every config carries discovery
        // values — the compatibility constructors set them to the accepted values —
        // so their presence says nothing; discovering subjects with a domain that
        // does not also filter them is what makes a value a seed.
        return discoversOnly()
                ? discoveryValueQids.size() + " discovery seed(s)"
                : valueQids.size() + " allowed values";
    }

    /** Whether the value domain finds subjects without also filtering their
     *  statements — the seeded-target-class case, as opposed to a vocabulary that
     *  does both jobs. */
    public boolean discoversOnly() {
        return discoverSubjects() && hasDiscoveryValueQids() && !hasValueQids();
    }

    /** When set (e.g. Q19020 "Academy Awards"), keep only statements whose main
     *  value is {@code wdt:P31} this type — e.g. only Oscar categories, dropping
     *  every other award a winner also received. */
    public boolean hasValueType() {
        return valueTypeQid != null && WikidataIds.isQid(valueTypeQid);
    }

    /** The EXPLICIT allowed value QIDs (e.g. the 59 Oscar categories). When
     *  present, the loader pins {@code VALUES ?value { … }} — a tighter, more
     *  deterministic join than the broad {@code wdt:P31} type filter, and it
     *  drops statements whose value isn't one of these. */
    public boolean hasValueQids() {
        return valueQids != null && !valueQids.isEmpty();
    }

    /** Values used only to find the initial statement subjects. They need not be the
     *  accepted statement-value domain: a seeded Position may discover its holders,
     *  after which every P39 statement of those holders is materialized. */
    public boolean hasDiscoveryValueQids() {
        return discoveryValueQids != null && !discoveryValueQids.isEmpty();
    }

    public enum Kind {
        /** Resolve the qualifier to a labelled entity (e.g. P1686 for-work). */
        ENTITY,
        /** A time qualifier reduced to its 4-digit year (e.g. P585) — enough for a
         *  ceremony, and wrong for anything a day belongs to. */
        YEAR,
        /** A time qualifier kept whole: the precision the value states, and the
         *  calendar it states it in. A reign beginning on 25 December 1000 is a day
         *  in the Julian calendar, and {@link YEAR} would keep neither fact. */
        DATE,
        /** A raw literal qualifier kept as a string. */
        STRING
    }

    /**
     * @param multi the qualifier repeats on one statement (e.g. P2453 nominee for a
     *              shared award lists every co-nominee) — collect ALL values into a
     *              list instead of keeping one. Driven by the model field's
     *              "Count: List" cardinality.
     */
    /**
     * @param language for a monolingual-text value, the language to keep. Blank keeps
     *                 every wording, which is the only honest answer when none was
     *                 asked for. Ignored by the other kinds, whose values state no
     *                 language of their own.
     */
    public record Qualifier(String pid, String fieldName, Kind kind, boolean multi,
                            String language) {
        public Qualifier {
            language = language == null ? "" : language.trim();
        }

        public Qualifier(String pid, String fieldName, Kind kind, boolean multi) {
            this(pid, fieldName, kind, multi, "");
        }

        /** Back-compat single-valued form. */
        public Qualifier(String pid, String fieldName, Kind kind) {
            this(pid, fieldName, kind, false, "");
        }
    }

    public boolean valid() {
        return entityType != null && !entityType.isBlank()
                && propertyPid != null && WikidataIds.isPid(propertyPid)
                && statementField != null && !statementField.isBlank();
    }
}
