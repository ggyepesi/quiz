package wikidata;

import java.util.ArrayList;
import java.util.List;

/**
 * A deterministic stand-in for {@link WikidataSparqlClient}: returns canned
 * bindings for queries matched by a substring, and an empty result otherwise.
 * Lets extraction/qualifier-load run in tests without a live WDQS endpoint.
 */
public class FakeWikidataSparqlClient extends WikidataSparqlClient {

    private final List<Stub> stubs = new ArrayList<>();

    public FakeWikidataSparqlClient() {
        super("FakeWikidataSparqlClient/1.0 (test)");
    }

    /** Return {@code bindings} for any query whose text contains {@code needle}. */
    public FakeWikidataSparqlClient onQueryContaining(
            String needle, List<WikidataBinding> bindings) {
        stubs.add(new Stub(needle, bindings));
        return this;
    }

    @Override
    public List<WikidataBinding> query(String sparql) {
        if (sparql != null) {
            for (Stub s : stubs) {
                if (sparql.contains(s.needle)) {
                    return new ArrayList<>(s.bindings);
                }
            }
        }
        return List.of();
    }

    private record Stub(String needle, List<WikidataBinding> bindings) {
    }
}
