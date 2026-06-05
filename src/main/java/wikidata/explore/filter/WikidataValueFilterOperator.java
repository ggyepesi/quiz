package wikidata.explore.filter;

public enum WikidataValueFilterOperator {
    EQ("="),
    NE("!="),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">=");

    private final String sparql;

    WikidataValueFilterOperator(String sparql) {
        this.sparql = sparql;
    }

    public String sparql() {
        return sparql;
    }

    @Override
    public String toString() {
        return sparql;
    }
}
