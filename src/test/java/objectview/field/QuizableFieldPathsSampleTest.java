package objectview.field;
import objectview.ViewableAdapter;
import objectview.field.QuizableFieldPaths;

import org.junit.jupiter.api.Test;
import quiz.transform.DynamicQuizable;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QuizableFieldPaths.collectFromSample enumerates a DYNAMIC object's fields from
 * the property map (not declared Java fields) and follows nested references — so
 * the nested/typed field model works over snapshot/transform domains.
 */
class QuizableFieldPathsSampleTest {

    @Test void enumeratesDynamicFieldsAndNestedReferencePaths() {
        DynamicQuizable category = new DynamicQuizable("Q1", "Best Picture");
        category.type("Category");
        category.put("edition", "1st");

        DynamicQuizable nomination = new DynamicQuizable("N1", "A Nomination");
        nomination.type("Nomination");
        nomination.put("year", 2000);
        nomination.put("category", category);   // a reference held in the map

        Set<String> paths = QuizableFieldPaths.collectFromSample(
                nomination, QuizableFieldPaths.ALL_FIELDS).stream()
                .map(p -> String.join(".", p.path()))
                .collect(Collectors.toSet());

        assertTrue(paths.contains("year"), paths.toString());          // dynamic scalar
        assertTrue(paths.contains("category.name"), paths.toString()); // reference name
        assertTrue(paths.contains("category.edition"), paths.toString()); // NESTED dynamic field
        assertTrue(paths.contains("name"), paths.toString());          // identity/display
    }
}
