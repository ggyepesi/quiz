package wikidata.explore.transform;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A saved field obeys the same configured type boundary as its generated Java field. */
class EntityFieldTypeConstraintsTest {

    @Test void anUnrelatedQidIsRemovedFromAPersonFieldButASubclassIsKept() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedClassModel historicalPerson = new GeneratedClassModel("HistoricalPerson");
        historicalPerson.baseClassName("Person");
        GeneratedClassModel position = new GeneratedClassModel("Position");
        GeneratedClassModel positionWithHolders =
                new GeneratedClassModel("PositionWithHolders");
        positionWithHolders.baseClassName("Position");
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.addField("predecessor", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Person");
        holding.addField("successor", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Person");
        project.rootClass(holding);
        project.addClass(person);
        project.addClass(historicalPerson);
        project.addClass(position);
        project.addClass(positionWithHolders);

        WikidataDynamicObject incompatible = object("Q641589", "PositionWithHolders");
        WikidataDynamicObject compatible = object("Q378404", "HistoricalPerson");
        WikidataDynamicObject office = object("Q1$holding", "OfficeHolding");
        office.put("predecessor", incompatible);
        office.put("successor", compatible);

        assertEquals(1, EntityFieldTypeConstraints.apply(
                project, List.of(office, incompatible, compatible), null));
        assertNull(office.get("predecessor"));
        assertSame(compatible, office.get("successor"));
        assertEquals(java.util.Set.of("PositionWithHolders"),
                incompatible.directClassNames(),
                "pruning the reference must not retype or delete the position itself");
    }

    @Test void anExplicitContextualRepresentationRemainsACompatibleValue() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        project.rootClass(nomination);
        project.addClass(nominee);
        project.addClass(person);
        project.representationClasses(nominee, List.of("Person"));

        WikidataDynamicObject represented = object("Q42", "Person");
        WikidataDynamicObject record = object("Q1$nomination", "Nomination");
        record.put("nominee", represented);

        assertEquals(0, EntityFieldTypeConstraints.apply(
                project, List.of(record, represented), null));
        assertSame(represented, record.get("nominee"));
    }

    private static WikidataDynamicObject object(String id, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(id, id);
        value.type(type);
        value.typeKey(type);
        return value;
    }
}
