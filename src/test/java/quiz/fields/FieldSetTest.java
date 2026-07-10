package quiz.fields;

import org.junit.jupiter.api.Test;
import quiz.Quizable;
import quiz.QuizableAdapter;
import quiz.transform.ui.FieldKind;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam's promise: a reflected {@link Quizable} and a {@link WikidataDynamicObject}
 * expose the SAME field metadata + values through {@link FieldSet}, so machinery can
 * read one interface instead of branching on {@code instanceof DynamicFields}.
 */
class FieldSetTest {

    /** A hand-written (reflection) domain object. */
    static final class Film extends QuizableAdapter {
        String title = "Casino";
        int year = 1995;
        boolean won = true;
        Quizable director;                       // reference (null value, typed by field)
        List<Quizable> cast = new ArrayList<>(); // collection

        @Override public String getIdentifier() { return title; }
        @Override public String getDisplayName() { return title; }
    }

    private static Map<String, FieldRef> byName(FieldSet set) {
        Map<String, FieldRef> out = new LinkedHashMap<>();
        for (FieldRef f : set.fields()) {
            out.put(f.name(), f);
        }
        return out;
    }

    @Test void picksTheBackingImplementation() {
        assertInstanceOf(ReflectionFieldSet.class, FieldSet.of(new Film()));
        assertInstanceOf(DynamicFieldSet.class, FieldSet.of(new WikidataDynamicObject("Q1", "Casino")));
    }

    @Test void reflectionEnumeratesAndTypesFromDeclaredFields() {
        Map<String, FieldRef> f = byName(FieldSet.of(new Film()));

        assertEquals(FieldKind.TEXT, f.get("title").kind());
        assertEquals(FieldKind.ORDERED, f.get("year").kind());
        assertEquals(FieldKind.BOOLEAN, f.get("won").kind());
        assertTrue(f.get("director").reference(), "director is a reference");
        assertEquals(FieldKind.REFERENCE, f.get("director").kind());  // typed even with a null value
        assertTrue(f.get("cast").collection(), "cast is a collection");
        assertEquals(FieldKind.COLLECTION, f.get("cast").kind());
    }

    @Test void dynamicEnumeratesAndTypesFromValues() {
        WikidataDynamicObject wdo = new WikidataDynamicObject("Q1", "Casino");
        wdo.type("Film");
        wdo.put("year", 1995);
        wdo.put("won", true);
        wdo.put("director", new WikidataDynamicObject("Q2", "Scorsese"));
        // TWO cast members so the value is genuinely a collection. A SINGLE value is
        // ambiguous on the dynamic side (a 1-element collection looks like a scalar) —
        // reflection knows from the declared List type; the dynamic side needs the
        // schema (ProductSchema/FieldTypeSource) to be sure. That gap is the substance
        // of #87 — this is where the schema-backed type source will plug in.
        wdo.merge("cast", new WikidataDynamicObject("Q3", "De Niro"));
        wdo.merge("cast", new WikidataDynamicObject("Q4", "Joe Pesci"));

        Map<String, FieldRef> f = byName(FieldSet.of(wdo));

        assertEquals(FieldKind.ORDERED, f.get("year").kind());
        assertEquals(FieldKind.BOOLEAN, f.get("won").kind());
        assertTrue(f.get("director").reference(), "director resolves to an entity");
        assertEquals(FieldKind.REFERENCE, f.get("director").kind());
        assertTrue(f.get("cast").collection(), "cast is multi-valued");
        assertEquals(FieldKind.COLLECTION, f.get("cast").kind());
    }

    @Test void bothReadValuesTheSameWay() {
        Film film = new Film();
        assertEquals(1995, FieldSet.of(film).read("year"));
        assertEquals(true, FieldSet.of(film).read("won"));
        assertEquals("Casino", FieldSet.of(film).read("title"));

        WikidataDynamicObject wdo = new WikidataDynamicObject("Q1", "Casino");
        wdo.put("year", 1995);
        wdo.put("won", true);
        assertEquals(1995, FieldSet.of(wdo).read("year"));
        assertEquals(true, FieldSet.of(wdo).read("won"));
    }
}
