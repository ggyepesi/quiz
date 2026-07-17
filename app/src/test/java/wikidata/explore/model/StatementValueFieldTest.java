package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statement VALUE role is explicit — the non-qualifier field on the statement
 * PID — with no "first non-qualifier field" guess (see refactor step b).
 */
class StatementValueFieldTest {

    @Test
    void theValueIsTheNonQualifierFieldOnTheStatementPid() {
        // A Nomination reify class like the Oscars model: category is the value
        // (mapped to P1411, the statement PID); year/forWork are qualifiers.
        GeneratedClassModel n = new GeneratedClassModel("Nomination");
        n.statementSourceClass("OscarNominations");
        n.instanceMapping().propertyPid("P1411");     // statementSource PID
        GeneratedFieldModel category =
                n.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        category.mapping().propertyPid("P1411");       // value field on the statement PID
        GeneratedFieldModel year =
                n.addField("year", FieldType.DATE, FieldCardinality.SINGLE);
        year.mapping().qualifierPid("P585");           // qualifier

        assertEquals("category", StatementFieldSemantics.statementValueFieldName(n));
        assertTrue(StatementFieldSemantics.isStatementValueField(n, category));
        assertFalse(StatementFieldSemantics.isStatementValueField(n, year),
                "a qualifier is not the value");
    }

    @Test
    void noGuessWhenNoFieldIsOnTheStatementPid() {
        GeneratedClassModel n = new GeneratedClassModel("Nomination");
        n.statementSourceClass("OscarNominations");
        n.instanceMapping().propertyPid("P1411");
        // a non-qualifier field, but NOT mapped to P1411 — the old code would have
        // guessed this as the value; now it's not the value.
        n.addField("note", FieldType.STRING, FieldCardinality.SINGLE);
        n.addField("year", FieldType.DATE, FieldCardinality.SINGLE)
                .mapping().qualifierPid("P585");

        assertEquals("", StatementFieldSemantics.statementValueFieldName(n),
                "no field on the statement PID → no value (no first-field guess)");
    }

    @Test
    void nonReifyClassHasNoValueField() {
        GeneratedClassModel plain = new GeneratedClassModel("OscarNominations");
        plain.addField("target", FieldType.ENTITY, FieldCardinality.COLLECTION);
        assertEquals("", StatementFieldSemantics.statementValueFieldName(plain));
    }
}
