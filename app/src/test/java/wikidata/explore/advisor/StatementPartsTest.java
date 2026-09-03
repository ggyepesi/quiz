package wikidata.explore.advisor;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.advisor.EffectiveClassExplanation.Part;
import wikidata.explore.model.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A reified statement is one fact with things said about it.
 *
 * <p>Listed flat, OfficeHolding's six fields look like six peers. Two of them ARE the
 * statement — a person, position held, a position — some of the rest are what tells one
 * holding from another that looks the same, and the remainder merely describe it. The
 * distinction is why 179 records exist over 173 distinct person/position pairs: six
 * people held the same office twice, and only the dates separate those records.
 */
class StatementPartsTest {

    private static GeneratedProjectModel history() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("History");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().sourceQid("Q5");
        person.instanceMapping().propertyPid("P31");
        project.rootClass(person);
        // A discovering statement class needs a bounded value domain, or the domain
        // does not compile — the same rule that stops an unbounded membership scan.
        // Seeded, as History's Position is: Wikidata has no clean "instance of
        // position" membership, so the population is anchored by seeds and grown
        // along P279. A seeded value class is one of the bounds the rule accepts.
        GeneratedClassModel positionClass = new GeneratedClassModel("Position");
        positionClass.instanceMapping().propertyPid("P31");
        positionClass.seedQids().add("Q6412254");
        project.addClass(positionClass);

        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        StatementClassSource source = new StatementClassSource("P39");
        source.propertyLabel("position held");
        holding.statementSource(source);

        GeneratedFieldModel subject = holding.addField(
                "source", FieldType.ENTITY, FieldCardinality.SINGLE);
        subject.entityClassName("Person");
        subject.mapping().productionKind(FieldProductionKind.STATEMENT_SUBJECT);
        GeneratedFieldModel position = holding.addField(
                "position", FieldType.ENTITY, FieldCardinality.SINGLE);
        position.entityClassName("Position");
        position.mapping().propertyPid("P39");
        holding.addField("startDate", FieldType.DATE, FieldCardinality.SINGLE)
                .mapping().qualifierPid("P580");
        holding.addField("endDate", FieldType.DATE, FieldCardinality.SINGLE)
                .mapping().qualifierPid("P582");
        holding.addField("predecessor", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().qualifierPid("P1365");

        CanonicalSpec canonical = new CanonicalSpec();
        canonical.keyFields().addAll(
                List.of("source", "position", "startDate", "endDate"));
        canonical.duplicatePolicy(CanonicalSpec.DuplicatePolicy.KEEP_ONE);
        holding.canonical(canonical);
        project.addClass(holding);
        return project;
    }

    private static List<String> names(EffectiveClassExplanation e, Part part) {
        return e.fields(part).stream().map(EffectiveClassExplanation.Field::name).toList();
    }

    @Test void theStatementIsItsSubjectAndItsValue() {
        var project = history();
        var explanation = EffectiveClassExplanations.explain(
                project, project.findClass("OfficeHolding"));

        assertTrue(explanation.available(), explanation.unavailableReason());
        assertEquals(List.of("source"), names(explanation, Part.SUBJECT),
                "the entity the fact is about, filled from the statement itself");
        assertEquals(List.of("position"), names(explanation, Part.VALUE),
                "the field carrying the statement's own property is its value");
    }

    /** Only the key qualifiers separate two holdings; the rest merely describe one. */
    @Test void aQualifierInTheKeyDistinguishesAndTheRestDescribe() {
        var project = history();
        var explanation = EffectiveClassExplanations.explain(
                project, project.findClass("OfficeHolding"));

        assertEquals(List.of("startDate", "endDate"),
                names(explanation, Part.DISTINGUISHING));
        assertEquals(List.of("predecessor"), names(explanation, Part.DESCRIBING),
                "whom you replaced does not make it a different holding");
    }

    @Test void theKeyAndWhatHappensOnACollisionAreStated() {
        var project = history();
        var explanation = EffectiveClassExplanations.explain(
                project, project.findClass("OfficeHolding"));

        assertEquals("source + position + startDate + endDate; "
                + "two records with the same key keep one", explanation.identity());
    }

    /**
     * The saved History model marks no subject: OfficeHolding.source carries production
     * kind AUTO, as every model built through the UI does. The fixture above DOES mark
     * it, so asking the stored kind alone passed here while the real model dropped its
     * subject into "said about it" with nothing filling it — the same trap the reify
     * tests hit by hand-building roles the saved data never contains. This is the
     * unmarked shape.
     */
    @Test void anUnmarkedSubjectIsRejectedRatherThanInferred() {
        var project = history();
        var holding = project.findClass("OfficeHolding");
        holding.fields().stream()
                .filter(f -> f.name().equals("source")).findFirst().orElseThrow()
                .mapping().productionKind(FieldProductionKind.AUTO);

        var explanation = EffectiveClassExplanations.explain(project, holding);

        assertFalse(explanation.available());
        assertTrue(explanation.unavailableReason().contains("explicitly expose its subject"),
                explanation.unavailableReason());
    }

    /** An ordinary class has no parts, and is not made to look as though it does. */
    @Test void aClassThatIsNotAStatementHasNoParts() {
        var project = history();
        var explanation = EffectiveClassExplanations.explain(
                project, project.findClass("Person"));

        assertTrue(explanation.available(), explanation.unavailableReason());
        assertFalse(explanation.hasParts(),
                "an unavailable explanation also has no parts, so availability is "
                        + "asserted first or this passes for the wrong reason");
        assertEquals("", explanation.identity());
    }
}
