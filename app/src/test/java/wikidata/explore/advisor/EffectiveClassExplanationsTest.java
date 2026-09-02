package wikidata.explore.advisor;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveClassExplanationsTest {

    @Test void importedPersonExplainsShapePopulationAndEveryUseTogether() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("History");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.importedFrom("Person");
        person.addField("dateOfBirth", FieldType.DATE, FieldCardinality.SINGLE);
        var spouse = person.addField("spouse", FieldType.ENTITY, FieldCardinality.COLLECTION);
        spouse.entityClassName("Person");
        spouse.mapping().propertyPid("P26");
        spouse.mapping().propertyLabel("spouse");
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        var source = holding.addField("source", FieldType.ENTITY, FieldCardinality.SINGLE);
        source.entityClassName("Person");
        project.rootClass(person);
        project.addClass(holding);
        project.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));

        EffectiveClassExplanation explanation =
                EffectiveClassExplanations.explain(project, person);

        assertTrue(explanation.available(), explanation.unavailableReason());
        assertEquals("Imported from model 'Person'", explanation.declaration());
        assertTrue(explanation.instances().contains("Derived from Person.spouse"));
        assertTrue(explanation.instances().contains("represented as Person when P31 = Q5"));
        assertEquals(List.of("dateOfBirth", "spouse"),
                explanation.fields().stream().map(EffectiveClassExplanation.Field::name).toList());
        assertTrue(explanation.fields().stream()
                .allMatch(field -> field.origin().equals("model Person")));
        assertFalse(explanation.usesKnown(),
                "nothing computes the reverse index yet, and saying \"nothing uses "
                        + "this\" would report a finding never made");
        assertTrue(explanation.uses().isEmpty());

        EffectiveFieldExplanation field =
                EffectiveClassExplanations.explainField(project, person, spouse);
        assertTrue(field.available(), field.unavailableReason());
        assertEquals("Person", field.ownerClass());
        assertEquals("Entity/Object list", field.valueShape());
        assertTrue(field.source().contains("spouse (P26)"));
        assertEquals("Person", field.target());
    }
}
