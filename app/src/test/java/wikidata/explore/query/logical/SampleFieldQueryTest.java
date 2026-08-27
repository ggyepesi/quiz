package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldSampleContext;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleFieldQueryTest {

    // A class with no declared population and no kind rule cannot be sampled. Saying
    // so is the whole point: the previous behaviour bound an empty QID and sent
    // "BIND(wd: AS ?root)" to WDQS, so the failure arrived as a remote syntax error
    // about a query the reader never wrote.
    @Test void aClassWithNoPopulationQidRefusesToSample() {
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel spouse = person.addField(
                "spouse", FieldType.ENTITY, FieldCardinality.AUTO);
        spouse.mapping().propertyPid("P26");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new SampleFieldQuery(
                        new FieldSampleContext(person, spouse, ""), 10).execute(null));

        assertTrue(failure.getMessage().contains("Person.spouse"),
                "the message must name the field the reader selected: "
                        + failure.getMessage());
    }

    // An evidence-derived kind carries its population on the kind rule rather than on
    // the class mapping, and sampling must accept it from there.
    @Test void anEvidenceDerivedKindSuppliesThePopulationInstead() {
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel spouse = person.addField(
                "spouse", FieldType.ENTITY, FieldCardinality.AUTO);
        spouse.mapping().propertyPid("P26");

        // Asserting which later failure occurs would pin an implementation detail;
        // what matters is that the population guard is satisfied and not reached.
        Exception thrown = assertThrows(Exception.class,
                () -> new SampleFieldQuery(
                        new FieldSampleContext(person, spouse, "Q5"), 10).execute(null));

        assertFalse(String.valueOf(thrown.getMessage()).contains("population QID"),
                "a kind-supplied QID must satisfy the population guard, but got: "
                        + thrown);
    }
}
