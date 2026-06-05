package wikidata.explore.qid;

import quiz.QuizableAdapter;

public class TopQidResult extends QuizableAdapter {

    private final String name;
    private final String qid;
    private final String description;

    public TopQidResult(String qid, String name, String description) {
        this.qid = qid == null ? "" : qid;
        this.name = name == null || name.isBlank() ? this.qid : name;
        this.description = description == null ? "" : description;
    }

    @Override
    public String getName() {
        return name;
    }

    public String qid() {
        return qid;
    }

    public String description() {
        return description;
    }

    public String wikidataUrl() {
        return "https://www.wikidata.org/wiki/" + qid;
    }

    @Override
    public String toString() {
        return name + " (" + qid + ")";
    }

    @Override
    public QuizableAdapter createNew() {
        return null;
    }
}
