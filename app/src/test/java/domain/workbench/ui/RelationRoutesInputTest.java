package domain.workbench.ui;

import domain.datasource.wikidata.WikidataRelationRouteSpec;
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

    /** The dialog used to check only each step's direction, so it accepted a route
     *  starting at a qualifier that configuration then refused. */
    @Test void anInvalidStepOrderIsShownBeforeTheRoutesAreUsed() {
        var model = new RelationRoutesInput.RouteTableModel(WikidataRelationRouteSpec.parse("""
                [{"id":"r","label":"R","steps":[
                   {"relation":"P40","direction":"OUTGOING"}]}]
                """).routes());
        model.setValueAt(domain.datasource.wikidata.WikidataRouteRelation.Kind.QUALIFIER, 0, 2);

        assertEquals("Not valid yet: Wikidata relation route 'R', step 1: Qualifier P40 "
                        + "requires a statement node but the previous step yields entity",
                RelationRoutesInput.preview(model));
    }

    @Test void anInvalidDirectionIsShownBeforeTheRoutesAreUsed() {
        var model = new RelationRoutesInput.RouteTableModel(WikidataRelationRouteSpec.parse("""
                [{"id":"r","label":"R","steps":[
                   {"relation":"statement:P40","direction":"OUTGOING"},
                   {"relation":"qualifier:P3831","direction":"OUTGOING"}]}]
                """).routes());
        model.setValueAt(domain.datasource.graph.GraphTraversalDirection.INCOMING, 0, 4);

        assertEquals("Not valid yet: Statement P40 steps support outgoing traversal only",
                RelationRoutesInput.preview(model));
    }
}
