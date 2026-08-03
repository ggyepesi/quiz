package objectview.field;

import org.junit.jupiter.api.Test;
import quiz.transform.DynamicViewable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * getPath is tolerant over arbitrary domains: identity/display come from the Viewable
 * contract (not a raw field), a collection intermediate is descended, and a missing
 * field yields null instead of throwing.
 */
class FieldAccessTest {

    @Test void displayAndIdentityComeFromTheViewableContract() {
        DynamicViewable q = new DynamicViewable("Q1", "Alice");
        q.type("Person");
        assertEquals("Alice", FieldAccess.getPath(
                q, ViewableContractFieldSet.DISPLAY_KEY));
        assertEquals("Q1", FieldAccess.getPath(
                q, ViewableContractFieldSet.IDENTITY_KEY));
    }

    @Test void descendsThroughACollectionIntermediate() {
        DynamicViewable en = new DynamicViewable("L1", "English");
        DynamicViewable fr = new DynamicViewable("L2", "French");
        DynamicViewable country = new DynamicViewable("C1", "Wonderland");
        country.put("languages", List.of(en, fr));

        // languages is a list — descend into a representative element.
        assertEquals("English", FieldAccess.getPath(country,
                "languages." + ViewableContractFieldSet.DISPLAY_KEY));
    }

    @Test void missingFieldIsNullNotAnException() {
        DynamicViewable q = new DynamicViewable("Q1", "Alice");
        assertNull(FieldAccess.getPath(q, "nope"));
        assertNull(FieldAccess.getPath(q, "nope.deeper"));
    }

    @Test void resolvesAddressableTypeViewsAlongAPath() {
        // Extraction is a PATH, not a construct convention: `date.year` is the year
        // view of the FlexibleDate; `birthDate.monthDay` is the birthday view.
        DynamicViewable edition = new DynamicViewable("Q1", "41st Academy Awards");
        edition.put("date", new aux.FlexibleDate(1969, 4, 14));
        DynamicViewable nomination = new DynamicViewable("N1", "a nomination");
        nomination.put("edition", edition);
        assertEquals(1969,
                ((Number) FieldAccess.getPath(nomination, "edition.date.year")).intValue());

        DynamicViewable person = new DynamicViewable("P1", "Alice");
        person.put("birthDate", new aux.FlexibleDate(1980, 3, 15));
        assertEquals(java.time.MonthDay.of(3, 15),
                FieldAccess.getPath(person, "birthDate.monthDay"));
    }

    @Test void aViewBelowItsPrecisionIsNull() {
        DynamicViewable person = new DynamicViewable("P1", "Bob");
        person.put("birthDate", new aux.FlexibleDate(1980));   // year precision only
        assertEquals(1980,
                ((Number) FieldAccess.getPath(person, "birthDate.year")).intValue());
        assertNull(FieldAccess.getPath(person, "birthDate.month"));
        assertNull(FieldAccess.getPath(person, "birthDate.monthDay"));
    }
}
