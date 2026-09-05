package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What kind a class is, is a decision — not a report on how far its configuration got.
 *
 * <p>It used to be stored for OWNED and AGGREGATE and recomputed for the other two:
 * "Statement" meant "has a statement property filled in". So a class could not BE a
 * statement class before it had a property, the kind selector snapped back the moment it
 * was changed, and the editor that picks the property was unreachable — you got there by
 * being a statement class, and you became one by having a property.
 */
class ClassKindIsStoredNotDerivedTest {

    /** The state that could not exist: declared, not yet configured. */
    @Test void aStatementClassWithNoPropertyYetIsStillAStatementClass() {
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");

        holding.statementSource(new StatementClassSource("", ""));

        assertEquals(ClassKind.STATEMENT, holding.classKind(),
                "the kind is the choice, and the choice was made");
        assertFalse(holding.reifiesStatements(),
                "and it cannot reify yet, which is a different question");
    }

    /** Taking the source away takes the kind with it. */
    @Test void aStatementClassWithoutASourceIsNotOne() {
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(new StatementClassSource("P39"));
        assertEquals(ClassKind.STATEMENT, holding.classKind());

        holding.statementSource(null);

        assertEquals(ClassKind.SOURCE, holding.classKind(),
                "a statement class with no statement source is a leftover, not a kind");
    }

    /** Each kind keeps only what that kind can have. */
    @Test void switchingKindClearsWhatTheNewKindCannotHave() {
        GeneratedClassModel prize = new GeneratedClassModel("NobelPrize");
        prize.aggregateSource(new AggregateClassSource("Award", "awards"));
        assertEquals(ClassKind.AGGREGATE, prize.classKind());

        prize.classKind(ClassKind.STATEMENT);

        assertNull(prize.aggregateSource(),
                "an aggregate source on a statement class is the previous kind's leftover");
    }

    /** The saved models say which kind they are, rather than leaving it to be inferred. */
    @Test void theShippedModelsStoreTheirStatementKind() throws Exception {
        for (String domain : List.of("nobelprizes", "oscarnominations", "history")) {
            GeneratedProjectModel project = new GeneratedProjectModelStore().load(
                    new File("../data/wikidata/" + domain + "/" + domain + ".model.json"));
            List<String> statementClasses = project.classes().stream()
                    .filter(clazz -> clazz != null && clazz.statementSource() != null)
                    .map(GeneratedClassModel::className)
                    .toList();
            assertFalse(statementClasses.isEmpty(), domain + " has a statement class");
            for (String name : statementClasses) {
                assertEquals(ClassKind.STATEMENT,
                        project.findClass(name).classKind(),
                        domain + "." + name + " loads as the kind it is");
            }
        }
    }

    /** A class that is nothing else is a Source class, which is a kind and not a default. */
    @Test void aPlainClassIsASourceClass() {
        assertEquals(ClassKind.SOURCE, new GeneratedClassModel("Person").classKind());
    }
}
