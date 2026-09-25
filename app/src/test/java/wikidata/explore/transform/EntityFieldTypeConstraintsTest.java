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

    /**
     * An entity occupies as many roles as the fields that reached it, and one of them
     * becomes the carrier by a tie-break. Reading the carrier alone emptied the nominee
     * of 1180 Oscars nominations: 728 works that are their own nominee (declared
     * [ForWork, Nominee]) and 452 people a technical award names as the work (declared
     * [ForWork, Person], covered by Nominee -> Person). Both sort after ForWork.
     */
    @Test void aRoleTheEntityHoldsBesideItsCarrierIsStillAnAcceptedValue() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        project.rootClass(nomination);
        project.addClass(nominee);
        project.addClass(new GeneratedClassModel("ForWork"));
        project.addClass(person);
        project.representationClasses(nominee, List.of("Person"));

        WikidataDynamicObject workThatIsItsOwnNominee = object("Q184843", "ForWork");
        workThatIsItsOwnNominee.assignClass("Nominee");
        WikidataDynamicObject personNamedAsTheWork = object("Q104266", "ForWork");
        personNamedAsTheWork.assignClass("Person");
        WikidataDynamicObject work = object("Q1$nomination", "Nomination");
        work.put("nominee", workThatIsItsOwnNominee);
        WikidataDynamicObject honorary = object("Q2$nomination", "Nomination");
        honorary.put("nominee", personNamedAsTheWork);

        assertEquals(0, EntityFieldTypeConstraints.apply(project, List.of(
                work, honorary, workThatIsItsOwnNominee, personNamedAsTheWork), null));
        assertSame(workThatIsItsOwnNominee, work.get("nominee"),
                "the role is declared on the entity; only the carrier sorts elsewhere");
        assertSame(personNamedAsTheWork, honorary.get("nominee"),
                "and a representation rule answers for the role the entity does declare");
    }

    private static WikidataDynamicObject object(String id, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(id, id);
        value.type(type);
        value.typeKey(type);
        return value;
    }
}
