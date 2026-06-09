package wikidata;

import quiz.QuizableAdapter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WikidataEntity extends QuizableAdapter {
    private static final Map<String, WikidataEntity> CACHE =
            new ConcurrentHashMap<>();

    private String name;
    private String qid;
    private String url;

    public static WikidataEntity canonical(String name, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("null/blank id");
        }
        return CACHE.computeIfAbsent(id, a -> new WikidataEntity(name, id));
    }

    private WikidataEntity() {}

    private WikidataEntity(String label, String qid) {
        this.name = label;
        this.qid = qid;
        this.url = qid == null || qid.isBlank()
                ? ""
                : "https://www.wikidata.org/wiki/" + qid;
    }

    public String getQid() {
        return qid;
    }

    public String getUrl() {
        return url;
    }

    @Override
    public String getIdentifier() {
        return qid == null ? "" : qid;
    }

    @Override
    public String getDisplayName() {
        return name == null ? "" : name;
    }

    @Override
    public QuizableAdapter createNew() {
        return new WikidataEntity();
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WikidataEntity other)) return false;
        return qid != null && !qid.isBlank() && qid.equals(other.qid);
    }

    @Override
    public int hashCode() {
        return qid == null || qid.isBlank()
                ? System.identityHashCode(this)
                : qid.hashCode();
    }

    @Override
    public String toString() {
        return name + ", " + qid;// + ", " + url;
    }
}