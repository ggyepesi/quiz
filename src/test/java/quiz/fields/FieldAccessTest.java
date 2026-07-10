package quiz.fields;

import org.junit.jupiter.api.Test;
import quiz.transform.DynamicQuizable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * getPath is tolerant over arbitrary domains: `name`/`qid` come from the Quizable
 * contract (not a raw field), a collection intermediate is descended, and a missing
 * field yields null instead of throwing.
 */
class FieldAccessTest {

    @Test void nameAndIdComeFromTheQuizableContract() {
        DynamicQuizable q = new DynamicQuizable("Q1", "Alice");
        q.type("Person");
        // `name`/`qid` aren't in the property map — resolve via the contract.
        assertEquals("Alice", FieldAccess.getPath(q, "name"));
        assertEquals("Q1", FieldAccess.getPath(q, "qid"));
    }

    @Test void descendsThroughACollectionIntermediate() {
        DynamicQuizable en = new DynamicQuizable("L1", "English");
        DynamicQuizable fr = new DynamicQuizable("L2", "French");
        DynamicQuizable country = new DynamicQuizable("C1", "Wonderland");
        country.put("languages", List.of(en, fr));

        // languages is a list — descend into a representative element.
        assertEquals("English", FieldAccess.getPath(country, "languages.name"));
    }

    @Test void missingFieldIsNullNotAnException() {
        DynamicQuizable q = new DynamicQuizable("Q1", "Alice");
        assertNull(FieldAccess.getPath(q, "nope"));
        assertNull(FieldAccess.getPath(q, "nope.deeper"));
    }
}
