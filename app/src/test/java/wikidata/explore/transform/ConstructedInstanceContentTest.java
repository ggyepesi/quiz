package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConstructedInstanceContentTest {

    @Test void constructionMetadataDoesNotKeepAnEmptyInstanceAlive() {
        WikidataDynamicObject owner = object("owner");
        WikidataDynamicObject component = object("component");
        component.put("owner", owner);
        owner.put("component", component);
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(owner, component));

        List<WikidataDynamicObject> removed = ConstructedInstanceContent.prune(
                pool, candidates(component, List.of("date", "discoverers")));

        assertEquals(List.of(component), removed);
        assertEquals(List.of(owner), pool);
        assertNull(owner.get("component"), "the producing reference is cleared too");
    }

    @Test void aConfiguredPayloadValueKeepsTheConstructedInstance() {
        WikidataDynamicObject owner = object("owner");
        WikidataDynamicObject component = object("component");
        component.put("owner", owner);
        component.put("date", "1784");
        owner.put("component", component);
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(owner, component));

        List<WikidataDynamicObject> removed = ConstructedInstanceContent.prune(
                pool, candidates(component, List.of("date", "discoverers")));

        assertEquals(List.of(), removed);
        assertSame(component, owner.get("component"));
    }

    @Test void anEmptyConstructedCycleCannotKeepItselfAlive() {
        WikidataDynamicObject first = object("first");
        WikidataDynamicObject second = object("second");
        first.put("next", second);
        second.put("next", first);
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(first, second));
        Map<WikidataDynamicObject, Collection<String>> candidates =
                new IdentityHashMap<>();
        candidates.put(first, List.of("next"));
        candidates.put(second, List.of("next"));

        assertEquals(List.of(first, second),
                ConstructedInstanceContent.prune(pool, candidates));
        assertEquals(List.of(), pool);
    }

    @Test void terminalPayloadKeepsTheConstructedPathLeadingToIt() {
        WikidataDynamicObject first = object("first");
        WikidataDynamicObject second = object("second");
        first.put("next", second);
        second.put("value", 42);
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(first, second));
        Map<WikidataDynamicObject, Collection<String>> candidates =
                new IdentityHashMap<>();
        candidates.put(first, List.of("next"));
        candidates.put(second, List.of("value"));

        assertEquals(List.of(), ConstructedInstanceContent.prune(pool, candidates));
        assertEquals(List.of(first, second), pool);
    }

    private static Map<WikidataDynamicObject, Collection<String>> candidates(
            WikidataDynamicObject instance, Collection<String> fields) {
        Map<WikidataDynamicObject, Collection<String>> result =
                new IdentityHashMap<>();
        result.put(instance, fields);
        return result;
    }

    private static WikidataDynamicObject object(String id) {
        return new WikidataDynamicObject(id, id);
    }
}
