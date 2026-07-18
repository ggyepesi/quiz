package wikidata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deterministic stand-in for {@link WikidataSparqlClient} driven by <b>data, not
 * query text</b>: you seed the result rows (variable → value bindings) the query is
 * expected to produce, and {@code query} returns them — no brittle matching on the
 * SPARQL string. Mirrors {@link wikidata.api.FakeWikidataApiClient}, which answers
 * structurally by QID.
 *
 * <pre>
 *   FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient()
 *       .row(Map.of("member", "Q105883400"))
 *       .row(Map.of("member", "Q38195662"));
 * </pre>
 *
 * <p>Answering arbitrary SPARQL by fully modelling a triple store is out of scope;
 * seed the rows the extraction step under test needs (typically one membership
 * backbone query), or bypass SPARQL entirely by seeding the pool directly.
 */
public class FakeWikidataSparqlClient extends WikidataSparqlClient {

    private final List<Map<String, String>> rows = new ArrayList<>();

    public FakeWikidataSparqlClient() {
        super("FakeWikidataSparqlClient/1.0 (test)");
    }

    /** Seed one result row: a map of SPARQL variable → value. */
    public FakeWikidataSparqlClient row(Map<String, String> bindings) {
        rows.add(new LinkedHashMap<>(bindings));
        return this;
    }

    @Override
    public List<WikidataBinding> query(String sparql) {
        List<WikidataBinding> out = new ArrayList<>();
        for (Map<String, String> row : rows) {
            out.add(new WikidataBinding(row));
        }
        return out;
    }
}
