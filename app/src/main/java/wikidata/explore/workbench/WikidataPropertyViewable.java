package wikidata.explore.workbench;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import wikidata.explore.WikidataProperty;

public class WikidataPropertyViewable extends ViewableAdapter {
    @DisplayField private final String name;
    private final String pid;
    private final String description;
    private final String subpropertyOf;
    private final String inverseProperty;

    public WikidataPropertyViewable(WikidataProperty property) {
        this.pid = property.pid();
        this.name = property.label();
        this.description = property.description();
        this.subpropertyOf = property.superpropertyPids();
        this.inverseProperty = property.inversePropertyPids();
    }

    @Override
    public String getIdentifier() { return pid; }

    @Override
    public String getDisplayName() { return name; }

    public String pid() { return pid; }
    public String description() { return description; }
    public String subpropertyOf() { return subpropertyOf; }
    public String inverseProperty() { return inverseProperty; }

    @Override
    public String toString() { return name + " (" + pid + ")"; }
}
