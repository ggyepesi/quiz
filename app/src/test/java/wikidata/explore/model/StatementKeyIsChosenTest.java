package wikidata.explore.model;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A statement class's key is chosen, never written for it.
 *
 * <p>Three mechanisms used to write one: class creation, the field editor whenever an
 * unrelated edit left the key equal to a fresh suggestion, and a "Re-derive identity"
 * button. So a class carried an identity nobody had chosen, and the question "what does
 * this key mean" had no answer. Nothing writes now — the editor OFFERS, and the modeller
 * accepts or configures something else.
 */
class StatementKeyIsChosenTest {

    private static GeneratedClassModel holding() {
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(new StatementClassSource("P39"));
        GeneratedFieldModel subject = holding.addField(
                "source", FieldType.ENTITY, FieldCardinality.SINGLE);
        subject.mapping().productionKind(FieldProductionKind.STATEMENT_SUBJECT);
        holding.addField("position", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().propertyPid("P39");
        holding.addField("startDate", FieldType.DATE, FieldCardinality.SINGLE)
                .mapping().qualifierPid("P580");
        return holding;
    }

    @Test void aProposalIsOfferedForAClassWithNoKey() {
        assertEquals(List.of("source", "position"),
                StatementIdentity.proposedKey(holding()),
                "the triple's own components — what the editor offers in one click");
    }

    @Test void nothingIsOfferedOnceAKeyExists() {
        GeneratedClassModel holding = holding();
        holding.canonical().keyFields().add("position");

        assertTrue(StatementIdentity.proposedKey(holding).isEmpty(),
                "a key with anything in it is a decision, so there is nothing to offer");
    }

    /**
     * Offering is not writing. This is the whole distinction: reading the proposal a
     * hundred times leaves the model exactly as it was.
     */
    @Test void askingForTheProposalDoesNotWriteIt() {
        GeneratedClassModel holding = holding();

        StatementIdentity.proposedKey(holding);
        StatementIdentity.structuralKey(holding);

        assertEquals(List.of(), holding.canonical().keyFields());
    }

    /** A domain that generates must have chosen; a model may leave it to the domain. */
    @Test void anUnchosenKeyStopsADomainButNotAModel() {
        GeneratedProjectModel domain = new GeneratedProjectModel();
        domain.projectKind(GeneratedProjectModel.ProjectKind.DOMAIN);
        domain.addClass(holding());
        domain.rootClass(domain.findClass("OfficeHolding"));

        assertTrue(GeneratedProjectModelValidator.validate(domain).errors().stream()
                        .anyMatch(problem -> problem.message().contains("No identity")),
                GeneratedProjectModelValidator.validate(domain).format());

        GeneratedProjectModel model = new GeneratedProjectModel();
        model.projectKind(GeneratedProjectModel.ProjectKind.MODEL);
        model.addClass(holding());
        model.rootClass(model.findClass("OfficeHolding"));

        assertFalse(GeneratedProjectModelValidator.validate(model).errors().stream()
                        .anyMatch(problem -> problem.message().contains("No identity")),
                "a model states shape; the grain belongs to whoever generates it");
    }
}
