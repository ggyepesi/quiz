package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The condition that narrows an inherited class population.
 *
 * <p>A subclass is never a second population query.  It classifies entities already
 * admitted as instances of its base: either by a property/value test performed by the
 * query, or by membership in a saved population performed locally on the generated
 * object graph.</p>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record SubclassCondition(
        Kind kind,
        String propertyPid,
        String qid,
        String selectionName,
        String selectionId) {

    public enum Kind { NONE, PROPERTY_VALUE, IN_POPULATION, OUTSIDE_POPULATION }

    public SubclassCondition {
        kind = kind == null ? Kind.NONE : kind;
        propertyPid = clean(propertyPid);
        qid = clean(qid);
        selectionName = clean(selectionName);
        selectionId = DeclarationIds.clean(selectionId);
    }

    public static SubclassCondition none() {
        return new SubclassCondition(Kind.NONE, "", "", "", "");
    }

    public static SubclassCondition propertyValue(String pid, String qid) {
        return new SubclassCondition(Kind.PROPERTY_VALUE, pid, qid, "", "");
    }

    public static SubclassCondition inPopulation(String name, String id) {
        return new SubclassCondition(Kind.IN_POPULATION, "", "", name, id);
    }

    public static SubclassCondition outsidePopulation(String name, String id) {
        return new SubclassCondition(Kind.OUTSIDE_POPULATION, "", "", name, id);
    }

    public boolean configured() {
        return switch (kind) {
            case NONE -> false;
            case PROPERTY_VALUE -> propertyPid.matches("(?i)P\\d+")
                    && qid.matches("(?i)Q\\d+");
            case IN_POPULATION, OUTSIDE_POPULATION -> !selectionName.isBlank();
        };
    }

    public boolean populationBased() {
        return kind == Kind.IN_POPULATION || kind == Kind.OUTSIDE_POPULATION;
    }

    public SubclassCondition rebound(String id, String name) {
        return populationBased()
                ? new SubclassCondition(kind, "", "", name, id) : this;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
