package flag;

import org.junit.jupiter.api.Test;
import quiz.ViewableGroup;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyGroupStructureTest {

    @Test
    void buildsDenominationAndVariantHierarchyWithoutCitationBranches()
            throws Exception {
        Map<String, State> states = new TreeMap<>();
        states.put("United States", new State("United States"));
        states.put("Zimbabwe", new State("Zimbabwe"));
        String data = """
                United States\tUnited States dollar\t$\tUSD
                Zimbabwe\tUnited States dollar[F]\t$\tUSD
                Zimbabwe\tZimbabwean dollar\t$\tZWL
                """;
        ViewableGroup root = new ViewableGroup("All");

        DownloadFlagGroups.readCurrencyGroup(
                new BufferedReader(new StringReader(data)),
                "\t", root, states);

        ViewableGroup currencies = root.getChild("Currencies");
        ViewableGroup dollar = currencies.getChild("DO").getChild("dollar");
        ViewableGroup unitedStates = dollar.getChild("United States");

        assertNotNull(unitedStates);
        assertNull(currencies.getChild("United States"),
                "the variant is a leaf, not a top-level currency group");
        assertNull(currencies.getChild("DO").getChild("dollar[F]"),
                "a citation marker must not create another denomination");
        assertEquals(2, unitedStates.getMembers().size());
        assertTrue(unitedStates.contains("United States"));
        assertTrue(unitedStates.contains("Zimbabwe"));
        assertTrue(states.get("Zimbabwe").getGroups().containsKey(
                "All.Currencies.DO.dollar.United States"));
    }
}
