package domain.provenance;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import objectview.ViewableAdapter;
import objectview.annotations.Hidden;
import objectview.annotations.Label;
import objectview.annotations.Reference;

import java.util.Objects;

/** One configured Wikidata relation route reaching one terminal for one owner field. */
public final class WikidataRelationRouteMatch extends ViewableAdapter {

    public enum Status {
        MATCHED("Matched; final field not settled"),
        RETAINED("Retained"),
        NOT_RETAINED("Not retained");

        private final String label;

        Status(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    @Hidden private final String ownerClass;
    @Label("Target field") private final String field;
    @Hidden private final String routeId;
    @Label("Route") private final String route;
    @Reference @Label("Reached entity") private final WikidataSource terminal;
    @Label("Final field status") private final Status status;
    /** Correlated facet value; derived from the two stored facts, never serialized as
     *  another source of truth. */
    @JsonIgnore @Label("Route coverage") private final String coverage;

    @JsonCreator
    public WikidataRelationRouteMatch(
            @JsonProperty("ownerClass") String ownerClass,
            @JsonProperty("field") String field,
            @JsonProperty("routeId") String routeId,
            @JsonProperty("route") String route,
            @JsonProperty("terminal") WikidataSource terminal,
            @JsonProperty("status") Status status) {
        this.ownerClass = clean(ownerClass);
        this.field = clean(field);
        this.routeId = required(routeId, "Route id is required");
        this.route = clean(route).isBlank() ? this.routeId : clean(route);
        this.terminal = Objects.requireNonNull(terminal, "Reached entity is required");
        this.status = status == null ? Status.MATCHED : status;
        this.coverage = this.route + " — " + this.status;
    }

    public String ownerClass() { return ownerClass; }
    public String field() { return field; }
    public String routeId() { return routeId; }
    public String route() { return route; }
    public WikidataSource terminal() { return terminal; }
    public Status status() { return status; }
    public String coverage() { return coverage; }

    public WikidataRelationRouteMatch settled(boolean retained) {
        Status settled = retained ? Status.RETAINED : Status.NOT_RETAINED;
        return status == settled ? this : new WikidataRelationRouteMatch(
                ownerClass, field, routeId, route, terminal, settled);
    }

    /** Repeated convergence passes reach the same match; the settled answer wins. */
    public WikidataRelationRouteMatch merge(WikidataRelationRouteMatch other) {
        if (other == null) return this;
        if (!getIdentifier().equals(other.getIdentifier())) {
            throw new IllegalArgumentException("Cannot merge different route matches");
        }
        Status merged = status == Status.RETAINED || other.status == Status.RETAINED
                ? Status.RETAINED
                : status == Status.NOT_RETAINED || other.status == Status.NOT_RETAINED
                        ? Status.NOT_RETAINED : Status.MATCHED;
        return new WikidataRelationRouteMatch(ownerClass, field, routeId,
                richer(route, other.route), terminal, merged);
    }

    @Override public String getIdentifier() {
        return ownerClass + "\u0000" + field + "\u0000" + routeId + "\u0000" + terminal.qid();
    }

    @Override public String getDisplayName() { return coverage; }

    @Override public boolean equals(Object value) {
        return value instanceof WikidataRelationRouteMatch other
                && ownerClass.equals(other.ownerClass)
                && field.equals(other.field)
                && routeId.equals(other.routeId)
                && route.equals(other.route)
                && terminal.equals(other.terminal)
                && status == other.status;
    }

    @Override public int hashCode() {
        return Objects.hash(ownerClass, field, routeId, route, terminal, status);
    }

    private static String richer(String first, String second) {
        return clean(second).length() > clean(first).length() ? clean(second) : clean(first);
    }

    private static String required(String value, String message) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
