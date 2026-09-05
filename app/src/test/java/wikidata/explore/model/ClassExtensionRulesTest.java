package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassExtensionRulesTest {

    @Test void theOwnedRestrictionIsTheSameRuleTheEditorAndValidatorCanAsk() {
        GeneratedClassModel owned = new GeneratedClassModel("Name");
        owned.classKind(ClassKind.OWNED);
        GeneratedClassModel otherOwned = new GeneratedClassModel("OtherName");
        otherOwned.classKind(ClassKind.OWNED);
        GeneratedClassModel source = new GeneratedClassModel("Person");

        assertTrue(ClassExtensionRules.mayExtend(owned, otherOwned));
        assertFalse(ClassExtensionRules.mayExtend(owned, source));
        assertFalse(ClassExtensionRules.mayExtend(owned, owned));
        assertTrue(ClassExtensionRules.mayExtend(source, owned));
    }
}
