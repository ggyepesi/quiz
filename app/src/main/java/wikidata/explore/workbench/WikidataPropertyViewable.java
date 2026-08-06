package wikidata.explore.workbench;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import wikidata.explore.WikidataProperty;

public class WikidataPropertyViewable extends ViewableAdapter {
    @DisplayField private final String name;
    private final String pid;
    private final String description;

    public WikidataPropertyViewable(WikidataProperty property) {
        this.pid = property.pid();
        this.name = property.label();
        this.description = property.description();
    }

    @Override
    public String getIdentifier() { return pid; }

    @Override
    public String getDisplayName() { return name; }

    public String pid() { return pid; }
    public String description() { return description; }

    @Override
    public String toString() { return name + " (" + pid + ")"; }
}
