package wikidata.explore.advisor;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.StatementClassSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveClassExplanationsTest {

    @Test void aStatementUsesCompiledRolesAndNamesItsQualifier() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        StatementClassSource statement = new StatementClassSource("Person", "P39");
        statement.propertyLabel("position held");
        holding.statementSource(statement);

        var person = holding.addField("person", FieldType.ENTITY, FieldCardinality.SINGLE);
        person.entityClassName("Person");
        person.mapping().productionKind(FieldProductionKind.STATEMENT_SUBJECT);
        var position = holding.addField(
                "position", FieldType.ENTITY, FieldCardinality.SINGLE);
        position.entityClassName("Position");
        position.mapping().propertyPid("P39");
        position.mapping().propertyLabel("position held");
        var start = holding.addField("startDate", FieldType.DATE, FieldCardinality.SINGLE);
        start.mapping().qualifierPid("P580");
        start.mapping().qualifierLabel("start time");
        holding.canonical().keyFields().addAll(List.of("person", "position", "startDate"));

        project.addClass(holding);
        project.addClass(new GeneratedClassModel("Person"));
        project.addClass(new GeneratedClassModel("Position"));
        project.rootClass(holding);

        EffectiveClassExplanation explanation =
                EffectiveClassExplanations.explain(project, holding);

        assertEquals(EffectiveClassExplanation.Part.SUBJECT,
                explanation.fields().stream().filter(f -> f.name().equals("person"))
                        .findFirst().orElseThrow().part());
        assertEquals(EffectiveClassExplanation.Part.VALUE,
                explanation.fields().stream().filter(f -> f.name().equals("position"))
                        .findFirst().orElseThrow().part());
        assertEquals(EffectiveClassExplanation.Part.DISTINGUISHING,
                explanation.fields().stream().filter(f -> f.name().equals("startDate"))
                        .findFirst().orElseThrow().part());
        assertTrue(explanation.fields().stream().filter(f -> f.name().equals("startDate"))
                .findFirst().orElseThrow().filledBy().contains("start time (P580)"));

        EffectiveFieldExplanation selected =
                EffectiveClassExplanations.explainField(project, holding, start);
        assertTrue(selected.source().contains("start time (P580)"), selected.source());
    }

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
        // Not "Person.spouse": this fixture's OfficeHolding.source also targets Person,
        // and a reference from ANOTHER class explains a population where a
        // self-reference only presupposes it. The old expectation recorded the bug.
        assertTrue(explanation.instances().contains("Derived from OfficeHolding.source"),
                explanation.instances());
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
