package wikidata.explore.tree;

import quiz.QuizableAdapter;
import wikidata.explore.WikidataProperty;

public class WikidataPropertyQuizable extends QuizableAdapter {
    private final String name;
    private final String pid;
    private final String description;

    public WikidataPropertyQuizable(WikidataProperty property) {
        this.pid = property.pid();
        this.name = property.label();
        this.description = property.description();
    }

    @Override
    public String getName() { return name; }

    public String pid() { return pid; }
    public String description() { return description; }

    @Override
    public String toString() { return name + " (" + pid + ")"; }

    @Override
    public QuizableAdapter createNew() {
        return null;
    }
}
