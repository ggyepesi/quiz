package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FieldSourceMappingTest {

    // Regression: copy() must carry the explicit reification policy.
    @Test void copyCarriesReifyOverrides() {
        FieldSourceMapping m = new FieldSourceMapping();
        m.missingQualifierPolicy(MissingQualifierPolicy.MISSING);
        m.roleKind(RoleKind.IDENTITY);

        FieldSourceMapping c = m.copy();

        assertEquals(MissingQualifierPolicy.MISSING, c.missingQualifierPolicy());
        assertEquals(RoleKind.IDENTITY, c.roleKind());
    }

    @Test void copyKeepsOptionalPolicyUnset() {
        FieldSourceMapping c = new FieldSourceMapping().copy();
        assertNull(c.missingQualifierPolicy());
    }
}
