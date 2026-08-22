package flag;

import org.junit.jupiter.api.Test;
import quiz.group.ViewableGroup;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyGroupStructureTest {

    @Test
    void loadsCurrencyValuesWithoutCreatingNavigationGroups()
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
                "\t", states);

        assertNull(root.getChild("Currencies"));
        assertEquals(java.util.Set.of("dollar"),
                states.get("United States").getCurrencies());
        assertEquals(java.util.Set.of("dollar"),
                states.get("Zimbabwe").getCurrencies());
        assertTrue(states.values().stream()
                .allMatch(state -> state.getGroups().isEmpty()));
    }
}
