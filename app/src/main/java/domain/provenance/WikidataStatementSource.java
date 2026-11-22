package domain.provenance;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Hidden;
import objectview.annotations.Link;
import objectview.field.LinkedTextSequence;
import wikidata.WikidataIds;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The durable source reference of one Wikidata statement.
 *
 * <p>A statement is not merely its GUID: its readable source identity is the
 * {@code subject -- property -> object} triple. Keeping that triple here lets an
 * ordinary generated record and a later audit decision present the same source
 * after the source object itself has been removed from the instance pool.</p>
 */
public final class WikidataStatementSource extends ViewableAdapter
        implements Source {

    @Hidden private final String statement;
    @Hidden private final String subject;
    @Hidden private final String property;
    @Hidden private final String object;
    @Hidden private final String objectLabel;
    private final String rank;
    private final Map<String, List<String>> qualifiers;
    private final List<Reference> references;

    /** Human-readable statement identity. Each entity/property run owns its own link;
     * plain separators and a literal object remain ordinary searchable text. */
    @DisplayField
    @JsonIgnore
    private final LinkedTextSequence source;

    @Link
    @JsonIgnore
    private final String statementSubject;

    @Link
    @JsonIgnore
    private final String statementProperty;

    @Link
    @JsonIgnore
    private final String statementObject;

    @JsonIgnore
    private final String statementValue;

    /** The exact statement GUID, linked to its Wikibase REST representation. */
    @Link
    @JsonIgnore
    private final String guid;

    /** Exact machine-readable statement, including rank, qualifiers and references. */
    @Link(text = "Open exact statement JSON")
    @JsonIgnore
    private final String statementJson;

    @JsonCreator
    public WikidataStatementSource(
            @JsonProperty("statement") String statement,
            @JsonProperty("subject") String subject,
            @JsonProperty("property") String property,
            @JsonProperty("object") String object,
            @JsonProperty("objectLabel") String objectLabel,
            @JsonProperty("rank") String rank,
            @JsonProperty("qualifiers") Map<String, List<String>> qualifiers,
            @JsonProperty("references") List<Reference> references) {
        this.statement = clean(statement);
        this.subject = canonicalQid(subject);
        this.property = clean(property);
        this.object = clean(object);
        this.objectLabel = clean(objectLabel);
        this.rank = clean(rank).isBlank() ? "normal" : clean(rank);
        this.qualifiers = immutableValues(qualifiers);
        this.references = List.copyOf(references == null ? List.of() : references);
        this.statementSubject = linked(subject, subjectUrl());
        this.statementProperty = linked(property, propertyUrl());
        this.statementObject = linked(displayedObject(), objectUrl());
        this.statementValue = WikidataIds.isQid(object) ? null : object;
        this.guid = linked(this.statement, statementUrl());
        this.statementJson = statementUrl();
        this.source = LinkedTextSequence.of(
                linkedPart(this.subject, subjectUrl()),
                LinkedTextSequence.text(" — "),
                linkedPart(this.property, propertyUrl()),
                LinkedTextSequence.text(" → "),
                linkedPart(displayedObject(), objectUrl()));
    }

    /** A statement for which only its identity/triple is available. */
    public WikidataStatementSource(
            String statement, String subject, String property,
            String object, String objectLabel) {
        this(statement, subject, property, object, objectLabel,
                "normal", Map.of(), List.of());
    }

    @Override public String provenance() { return "wikidata"; }

    /** A statement source's native id is the statement GUID (never a QID). */
    @Override public String id() { return statement; }

    public String statement() { return statement; }

    public String subject() { return subject; }

    public String property() { return property; }

    public String object() { return object; }

    public String objectLabel() { return objectLabel; }

    public String rank() { return rank; }

    public Map<String, List<String>> qualifiers() { return qualifiers; }

    public List<Reference> references() { return references; }

    /**
     * The same statement can be reached by more than one graph path (most commonly
     * through different values of one qualifier).  Its GUID is the identity, while
     * qualifiers and references are accumulated evidence.  Merge that evidence here,
     * at the owner of the durable statement representation, instead of letting each
     * acquisition path decide which copy wins.
     */
    public WikidataStatementSource merge(WikidataStatementSource other) {
        if (other == null) return this;
        if (!statement.equals(other.statement)) {
            throw new IllegalArgumentException(
                    "Cannot merge different Wikidata statements: "
                            + statement + " and " + other.statement);
        }
        Map<String, List<String>> mergedQualifiers = mergeValues(
                qualifiers, other.qualifiers);
        List<Reference> mergedReferences = new ArrayList<>(references);
        for (Reference reference : other.references) {
            if (!mergedReferences.contains(reference)) mergedReferences.add(reference);
        }
        return new WikidataStatementSource(statement,
                richer(subject, other.subject), richer(property, other.property),
                richer(object, other.object), richer(objectLabel, other.objectLabel),
                richerRank(rank, other.rank), mergedQualifiers, mergedReferences);
    }

    public String subjectUrl() {
        return WikidataIds.isQid(subject)
                ? "https://www.wikidata.org/wiki/" + subject : "";
    }

    public String propertyUrl() {
        return WikidataIds.isPid(property)
                ? "https://www.wikidata.org/wiki/Property:" + property : "";
    }

    public String objectUrl() {
        return WikidataIds.isQid(object)
                ? "https://www.wikidata.org/wiki/" + object : "";
    }

    /** Exact machine-readable Wikibase REST representation of this statement. */
    public String statementUrl() {
        if (statement.isBlank()) return "";
        return "https://www.wikidata.org/w/rest.php/wikibase/v1/statements/"
                + URLEncoder.encode(statement, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    @Override public String getIdentifier() { return statement; }

    @Override public String getDisplayName() { return source.text(); }

    @Override public String getReferenceLabel() { return getDisplayName(); }

    @Override public boolean equals(Object value) {
        return value instanceof WikidataStatementSource other
                && statement.equals(other.statement)
                && subject.equals(other.subject)
                && property.equals(other.property)
                && object.equals(other.object)
                && objectLabel.equals(other.objectLabel)
                && rank.equals(other.rank)
                && qualifiers.equals(other.qualifiers)
                && references.equals(other.references);
    }

    @Override public int hashCode() {
        return java.util.Objects.hash(
                statement, subject, property, object, objectLabel,
                rank, qualifiers, references);
    }

    /** One Wikidata reference and every property/value snak it contains. */
    public record Reference(String hash, Map<String, List<String>> claims) {
        public Reference {
            hash = clean(hash);
            claims = immutableValues(claims);
        }
    }

    private String displayedObject() {
        if (objectLabel.isBlank() || objectLabel.equals(object)) return object;
        return WikidataIds.isQid(object)
                ? objectLabel + " (" + object + ")" : objectLabel;
    }

    private static String linked(String label, String url) {
        return url == null || url.isBlank() ? null : clean(label) + "|" + url;
    }

    private static LinkedTextSequence.Part linkedPart(String label, String url) {
        return url == null || url.isBlank()
                ? LinkedTextSequence.text(clean(label))
                : LinkedTextSequence.link(clean(label), url);
    }

    private static String canonicalQid(String value) {
        String cleaned = clean(value);
        return cleaned.matches("[Qq]\\d+")
                ? "Q" + cleaned.substring(1) : cleaned;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, List<String>> immutableValues(
            Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((property, propertyValues) -> {
            String pid = clean(property);
            if (pid.isBlank()) return;
            List<String> cleaned = new ArrayList<>();
            if (propertyValues != null) {
                propertyValues.stream().map(WikidataStatementSource::clean)
                        .filter(value -> !value.isBlank()).forEach(cleaned::add);
            }
            if (!cleaned.isEmpty()) copy.put(pid, List.copyOf(cleaned));
        });
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static Map<String, List<String>> mergeValues(
            Map<String, List<String>> first, Map<String, List<String>> second) {
        Map<String, List<String>> merged = new LinkedHashMap<>();
        if (first != null) first.forEach((key, values) ->
                merged.put(key, new ArrayList<>(values)));
        if (second != null) second.forEach((key, values) -> {
            List<String> target = merged.computeIfAbsent(key,
                    ignored -> new ArrayList<>());
            for (String value : values) if (!target.contains(value)) target.add(value);
        });
        return immutableValues(merged);
    }

    private static String richer(String first, String second) {
        return clean(first).isBlank() ? clean(second) : clean(first);
    }

    private static String richerRank(String first, String second) {
        String left = clean(first);
        String right = clean(second);
        return (left.isBlank() || "normal".equals(left)) && !right.isBlank()
                ? right : left;
    }
}
