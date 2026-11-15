package workbench;

import datasource.wikidata.WikidataRelationRouteSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The route editor previews the exact routes and the requests each step makes. */
class RelationRoutesInputTest {

    @Test void thePreviewNamesEveryStepAndItsRequests() {
        var routes = WikidataRelationRouteSpec.parse("""
                [{"id":"direct","label":"Direct child","steps":[
                   {"relation":"P40","direction":"OUTGOING"}]},
                 {"id":"father","label":"Father of","steps":[
                   {"relation":"P22","direction":"INCOMING"}]},
                 {"id":"group","label":"Qualified child group","steps":[
                   {"relation":"statement:P40","direction":"OUTGOING"},
                   {"relation":"qualifier:P3831","direction":"OUTGOING"},
                   {"relation":"P527","direction":"OUTGOING"}]}]
                """).routes();

        String preview = RelationRoutesInput.preview(
                new RelationRoutesInput.RouteTableModel(routes));

        assertEquals("""
                Direct child
                  Step 1 — outgoing Property P40: 1 Wikidata entity request per 50 owners
                Father of
                  Step 1 — incoming Property P22: 1 SPARQL query per 50 owners
                Qualified child group
                  Step 1 — outgoing Statement P40: 1 Wikidata entity request per 50 owners
                  Step 2 — outgoing Qualifier P3831: no request: read from the statements \
                step 1 fetched
                  Step 3 — outgoing Property P527: 1 Wikidata entity request per 50 \
                entities reached by step 2
                Adjacency an earlier route already read is not requested again.""",
                preview);
    }

    @Test void anInvalidDirectionIsShownBeforeTheRoutesAreUsed() {
        var model = new RelationRoutesInput.RouteTableModel(WikidataRelationRouteSpec.parse("""
                [{"id":"r","label":"R","steps":[
                   {"relation":"statement:P40","direction":"OUTGOING"}]}]
                """).routes());
        model.setValueAt(datasource.graph.GraphTraversalDirection.INCOMING, 0, 4);

        assertEquals("Not valid yet: Statement P40 steps support outgoing traversal only",
                RelationRoutesInput.preview(model));
    }
}
