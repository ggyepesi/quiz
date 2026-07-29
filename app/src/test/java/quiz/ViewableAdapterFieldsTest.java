package quiz;

import objectview.ViewableAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * getAllFields must not blow up on a JDK class in the hierarchy (e.g. a Viewable
 * that extends ArrayList) — setAccessible on java.util fields throws
 * InaccessibleObjectException under JPMS, so the walk stops at system classes.
 */
class ViewableAdapterFieldsTest {

    @Test void doesNotReflectIntoJdkClasses() {
        // Directly: a JDK collection class yields no fields, no exception.
        List<?> fields = ViewableAdapter.getAllFields(ArrayList.class);
        assertNotNull(fields);
        assertTrue(fields.isEmpty(), "should not reflect ArrayList's internals");
    }
}
