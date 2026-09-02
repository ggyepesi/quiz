package wikidata.explore.transform;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceResolutionReportTest {

    private static GeneratedProjectModel personCitizenship() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("NobelPrizes");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel citizenship = person.addField(
                "citizenship", FieldType.ENTITY, FieldCardinality.COLLECTION);
        citizenship.entityClassName("CountryOfCitizenship");
        model.rootClass(person);
        model.addClass(new GeneratedClassModel("CountryOfCitizenship"));
        return model;
    }

    private static WikidataDynamicObject object(String type, String id) {
        WikidataDynamicObject o = new WikidataDynamicObject(id, id);
        o.type(type);
        return o;
    }

    /**
     * The case that justified the check. Person.citizenship declares a class, every value
     * is a reference to a country, and no country was ever loaded — so the field is FULL
     * and resolves to nothing. Coverage cannot see this; it counts presence.
     */
    @Test void aReferenceToAnEntityTheDomainNeverLoadedIsReported() {
        GeneratedProjectModel model = personCitizenship();
        WikidataDynamicObject person = object("Person", "Q9960");
        person.dynamicFields().put("citizenship",
                List.of(object("", "Q30"), object("", "Q1206012")));

        ReferenceResolutionReport.Report report =
                ReferenceResolutionReport.check(model, List.of(person), null);

        assertFalse(report.clean());
        assertEquals(2, report.unresolvedValues());
        assertEquals(2, report.checkedValues());
        ReferenceResolutionReport.Unresolved only = report.unresolved().getFirst();
        assertEquals("Person", only.className());
        assertEquals("citizenship", only.fieldName());
        assertEquals("CountryOfCitizenship", only.declaredClass(),
                "the report names what the field promised, which is what makes it "
                        + "actionable");
        assertEquals(List.of("Q30", "Q1206012"), only.sampleIds());
    }

    @Test void aReferenceToSomethingTheDomainHoldsIsFine() {
        GeneratedProjectModel model = personCitizenship();
        WikidataDynamicObject person = object("Person", "Q9960");
        person.dynamicFields().put("citizenship", List.of(object("", "Q30")));
        WikidataDynamicObject country = object("CountryOfCitizenship", "Q30");

        ReferenceResolutionReport.Report report =
                ReferenceResolutionReport.check(model, List.of(person, country), null);

        assertTrue(report.clean());
        assertEquals(1, report.checkedValues());
    }

    /**
     * An owned part is stamped with its production site — {@code Name@Person.structuredName}
     * — while the field declares {@code Name}. Asking whether the id is held avoids having
     * an opinion about that, which is why the weaker question is the one asked.
     */
    @Test void anOwnedPartStampedWithItsProductionSiteResolves() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("NobelPrizes");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.addField("structuredName", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Name");
        model.rootClass(person);

        WikidataDynamicObject subject = object("Person", "Q9960");
        WikidataDynamicObject name = object("Name@Person.structuredName", "Q9960");
        subject.dynamicFields().put("structuredName", name);

        assertTrue(ReferenceResolutionReport.check(model, List.of(subject, name), null)
                .clean(), "an owned part shares its owner's qid and is present");
    }

    /**
     * A class standing for a union holds members stamped with the kind they actually are
     * — Oscars' nominee declares Nominee and holds Persons and works. Present is present.
     */
    @Test void aUnionMemberStampedWithItsOwnKindResolves() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("oscarnominations");
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        model.rootClass(nomination);

        WikidataDynamicObject nom = object("Nomination", "N1");
        WikidataDynamicObject actor = object("Person", "Q72717");
        nom.dynamicFields().put("nominee", actor);

        assertTrue(ReferenceResolutionReport.check(model, List.of(nom, actor), null)
                .clean(), "declared Nominee, stamped Person, and genuinely here");
    }

    @Test void aFieldWithNoDeclaredClassIsNotThisChecksBusiness() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("D");
        GeneratedClassModel c = new GeneratedClassModel("Thing");
        c.addField("related", FieldType.ENTITY, FieldCardinality.SINGLE);
        model.rootClass(c);

        WikidataDynamicObject thing = object("Thing", "T1");
        thing.dynamicFields().put("related", object("", "Q30"));

        ReferenceResolutionReport.Report report =
                ReferenceResolutionReport.check(model, List.of(thing), null);

        assertTrue(report.clean());
        assertEquals(0, report.checkedValues(),
                "an undeclared target promises nothing, so nothing is broken");
    }

    @Test void anEmptyPoolIsNotAFinding() {
        assertTrue(ReferenceResolutionReport.check(personCitizenship(), List.of(), null)
                .clean());
    }
}
