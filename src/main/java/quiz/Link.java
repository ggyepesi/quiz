package quiz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code String} field whose value is a URL.
 *
 * QuizablePanel renders such a field as a clickable hyperlink — left-click
 * opens it in the browser, right-click copies it — instead of folding it
 * into the shared (drag-to-select) text block. Non-String or blank values
 * fall back to the default rendering.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Link {
}
