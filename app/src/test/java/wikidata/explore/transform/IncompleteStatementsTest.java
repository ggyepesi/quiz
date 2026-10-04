package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A statement whose subject or object was removed after acquisition is incomplete, not
 * smaller. The disambiguation prune removed Wikimedia-internal list pages that carry P39,
 * scrubbed the references to them as designed, and left eleven History office holdings
 * with no holder; re-reducing the saved snapshot by its key folded them into three.
 */
class IncompleteStatementsTest {

    @Test void aStatementThatLostADeclaredEndIsFound() {
        GeneratedProjectModel model = model(true);
        WikidataDynamicObject complete = holding("Q1$a", true, true);
        WikidataDynamicObject noHolder = holding("Q2$b", false, true);
        WikidataDynamicObject noPosition = holding("Q3$c", true, false);

        List<IncompleteStatements.Missing> missing =
                IncompleteStatements.find(model, List.of(complete, noHolder, noPosition));

        assertEquals(List.of("Q2$b", "Q3$c"), missing.stream()
                .map(value -> value.statement().getIdentifier()).toList());
        assertEquals(List.of(FieldProductionKind.STATEMENT_SUBJECT,
                        FieldProductionKind.STATEMENT_OBJECT),
                missing.stream().map(IncompleteStatements.Missing::end).toList());
    }

    /** A reusable model may leave an end open; with no destination there is nothing to
     *  find empty. */
    @Test void anEndWithNoDeclaredDestinationIsNotRequired() {
        GeneratedProjectModel model = model(false);

        assertTrue(IncompleteStatements.find(model,
                List.of(holding("Q2$b", false, true))).isEmpty());
    }

    private static GeneratedProjectModel model(boolean declareSubject) {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass().className("Person");
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        StatementClassSource source = new StatementClassSource("P39");
        source.objectBound(EntityBound.explicit(List.of("Q30461")));
        holding.statementSource(source);
        if (declareSubject) {
            holding.addField("source", FieldType.ENTITY, FieldCardinality.SINGLE)
                    .mapping().productionKind(FieldProductionKind.STATEMENT_SUBJECT);
        }
        holding.addField("position", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().productionKind(FieldProductionKind.STATEMENT_OBJECT);
        model.addClass(holding);
        return model;
    }

    private static WikidataDynamicObject holding(String id, boolean holder, boolean position) {
        WikidataDynamicObject record = new WikidataDynamicObject(id, id);
        record.type("OfficeHolding");
        if (holder) {
            WikidataDynamicObject person = new WikidataDynamicObject("Q9", "Holder");
            person.type("Person");
            record.put("source", person);
        }
        if (position) {
            WikidataDynamicObject office = new WikidataDynamicObject("Q30461", "president");
            office.type("Position");
            record.put("position", office);
        }
        return record;
    }
}
