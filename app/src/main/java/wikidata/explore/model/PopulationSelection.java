package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** A named, persisted set of instances of one declared class. The QIDs are stable
 * datasource identities; the selection does not duplicate the instance objects. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PopulationSelection extends Selection {

    private String className = "";
    private final List<String> instanceQids = new ArrayList<>();

    public PopulationSelection() {
        super();
        kind(Kind.POPULATION);
    }

    public PopulationSelection(String name) {
        super(name, Kind.POPULATION);
    }

    public String className() {
        return className == null ? "" : className;
    }

    public void className(String value) {
        className = value == null ? "" : value.trim();
    }

    public List<String> instanceQids() {
        return instanceQids;
    }

    public void instanceQids(List<String> values) {
        instanceQids.clear();
        addQids(instanceQids, values);
    }

    @Override
    public boolean isConfigured() {
        return !name().isBlank() && !className().isBlank() && !instanceQids.isEmpty();
    }

    @Override
    public PopulationSelection copy() {
        PopulationSelection c = new PopulationSelection(name());
        copyIdentityTo(c);
        c.className = className;
        c.instanceQids.addAll(instanceQids);
        return c;
    }
}
