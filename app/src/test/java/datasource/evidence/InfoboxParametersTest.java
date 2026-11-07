package datasource.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #462: {@link InfoboxParameters.Key} owns the {@code Template.parameter} and
 * {@code Rendered.<label>} grammar, yet two discovery readers listed a page's values by
 * concatenating the key strings themselves. {@link InfoboxParameters#values()} is the one
 * enumeration, and it is the exact inverse of {@link InfoboxParameters#valueOf}.
 */
class InfoboxParametersTest {

    @Test void everyListedKeyReadsBackItsValue() {
        InfoboxParameters infobox = InfoboxParameters.fromEnglishWikipedia("Infobox deity",
                Map.of("symbol", "Ostrich feather", "consort", "Thoth"),
                Map.of("Symbol", "Ostrich feather", "Cult center", "Hermopolis"),
                "Maat", "42");

        Map<InfoboxParameters.Key, String> values = infobox.values();

        assertEquals(4, values.size());
        values.forEach((key, value) -> assertEquals(value, infobox.valueOf(key), key.toString()));
        assertTrue(values.containsKey(InfoboxParameters.Key.parse("Infobox deity.consort")));
        assertTrue(values.containsKey(InfoboxParameters.Key.parse("Rendered.Cult center")));
    }

    @Test void aRenderedOnlyInfoboxListsOnlyRenderedRows() {
        InfoboxParameters infobox = InfoboxParameters.fromEnglishWikipedia("", Map.of(),
                Map.of("Group", "group 8"), "Iron", "7");

        assertEquals(List.of("Rendered.Group"),
                infobox.values().keySet().stream().map(Object::toString).toList());
    }
}
