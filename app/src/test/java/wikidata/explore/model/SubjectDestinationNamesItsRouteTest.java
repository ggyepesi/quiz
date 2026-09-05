package wikidata.explore.model;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a statement's subject goes, over every route it may go by.
 *
 * <p>Three are authored — a declared subject field, a participants collection, and a
 * qualifier falling back to the subject — and the editor was told about one. So a class
 * settling its subject through participants was shown as "Not configured — a domain must
 * settle this before it can generate", of a domain that generates. The reader was then
 * left doubting something true: that the subject of ⟨laureate, P166, category⟩ is the
 * laureate.
 */
class SubjectDestinationNamesItsRouteTest {

    /** Nobel: the subject arrives through the participants collection, and is named. */
    @Test void aParticipantsCollectionIsWhereTheSubjectGoes() throws Exception {
        GeneratedProjectModel nobel = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/nobelprizes/nobelprizes.model.json"));

        StatementFieldSemantics.SubjectDestination subject =
                StatementFieldSemantics.subjectDestination(
                        nobel.findClass("LaureatesWithMotivation"));

        assertTrue(subject.bound(), "this model generates, so its subject has a home");
        assertEquals("laureates", subject.fieldName());
        assertEquals(StatementFieldSemantics.SubjectDestination.Route.PARTICIPANTS,
                subject.route());
        assertTrue(subject.route().phrase().contains("statement's own item"),
                subject.route().phrase());
    }

    /** And the editor's question and the validator's are now the same question. */
    @Test void theValidatorAndTheEditorAgree() throws Exception {
        GeneratedProjectModel nobel = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/nobelprizes/nobelprizes.model.json"));
        GeneratedClassModel statement = nobel.findClass("LaureatesWithMotivation");

        assertEquals(StatementFieldSemantics.hasStatementSubjectBinding(statement),
                StatementFieldSemantics.subjectDestination(statement).bound());
    }

    /** A statement class with no route at all says so, and that is a real state. */
    @Test void noRouteIsReportedAsNoRoute() {
        GeneratedClassModel statement = new GeneratedClassModel("Award");
        statement.statementSource(new StatementClassSource("P166", "award received"));
        statement.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);

        StatementFieldSemantics.SubjectDestination subject =
                StatementFieldSemantics.subjectDestination(statement);

        assertFalse(subject.bound());
        assertEquals("", subject.fieldName());
        assertEquals(StatementFieldSemantics.SubjectDestination.Route.NONE,
                subject.route());
    }

    /** A class that reifies nothing has no statement subject to place. */
    @Test void aPlainClassHasNoSubjectDestination() {
        assertFalse(StatementFieldSemantics
                .subjectDestination(new GeneratedClassModel("Person")).bound());
    }

    /** A route names a field and no route names none — the pair cannot disagree. */
    @Test void aRouteAndAFieldTravelTogether() {
        assertThrows(IllegalArgumentException.class,
                () -> new StatementFieldSemantics.SubjectDestination("laureates",
                        StatementFieldSemantics.SubjectDestination.Route.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new StatementFieldSemantics.SubjectDestination("",
                        StatementFieldSemantics.SubjectDestination.Route.PARTICIPANTS));
    }
}
