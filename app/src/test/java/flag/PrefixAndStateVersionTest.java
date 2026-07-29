package flag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PrefixAndStateVersionTest {

    @Test
    void versionIdentityKeepsVariantsButNormalizesEquivalentPrefixes() {
        PrefixAndState base =
                new PrefixAndState("Flag of ", "Dominica", "Dominica");
        PrefixAndState variant =
                new PrefixAndState(
                        "Flag of ", "Dominica", "Dominica (variant 6)");
        PrefixAndState equivalentPrefix =
                new PrefixAndState(
                        "Flag of the ", "Dominica", "Dominica");

        assertEquals("Dominica", variant.getState());
        assertNotEquals(base.getVersionIdentityKey(),
                variant.getVersionIdentityKey(),
                "a real flag version must not be deduplicated away");
        assertEquals(base.getVersionIdentityKey(),
                equivalentPrefix.getVersionIdentityKey(),
                "equivalent prefix spelling is still one image identity");
    }
}
