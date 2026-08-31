package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Selection.Kind#POPULATION} Selection: a subject set — the entities that
 * carry {@link #relationPid()} into {@link #targetQids()} (e.g. the entities with
 * P1411 into the Oscar categories) that a reify draws its subjects from. An empty
 * {@code targetQids} means the relation alone.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PopulationSelection extends Selection {

    private String relationPid = "";
    private final List<String> targetQids = new ArrayList<>();

    public PopulationSelection() {
        super();
        kind(Kind.POPULATION);
    }

    public PopulationSelection(String name) {
        super(name, Kind.POPULATION);
    }

    public String relationPid() {
        return relationPid == null ? "" : relationPid;
    }

    public void relationPid(String value) {
        relationPid = value == null ? "" : value.trim();
    }

    public List<String> targetQids() {
        return targetQids;
    }

    public void targetQids(List<String> values) {
        targetQids.clear();
        addQids(targetQids, values);
    }

    @Override
    public boolean isConfigured() {
        return !name().isBlank() && relationPid().matches("(?i)P\\d+");
    }

    @Override
    public PopulationSelection copy() {
        PopulationSelection c = new PopulationSelection(name());
        copyIdentityTo(c);
        c.relationPid = relationPid;
        c.targetQids.addAll(targetQids);
        return c;
    }
}
