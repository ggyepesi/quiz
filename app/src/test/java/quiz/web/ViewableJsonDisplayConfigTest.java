package quiz.web;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Reference;
import objectview.field.FieldSet;
import objectview.render.ReferenceRow;
import objectview.viewconfig.ViewConfigJsonIO.JsonConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Swing and web treat DISPLAY as the same configured caption field. */
class ViewableJsonDisplayConfigTest {

    @Test void anExplicitlySelectedDisplaySuppliesTheWebCaption() {
        Person person = new Person("Ada Lovelace");
        JsonConfig config = new JsonConfig();
        config.fields.put("name", new JsonConfig());

        assertTrue(ViewableJson.displaySelected(FieldSet.of(person), config));
    }

    @Test void anUntickedDisplayDoesNotLeakThroughTheWebCaption() {
        Person person = new Person("Ada Lovelace");
        JsonConfig config = new JsonConfig();
        config.allFields = false;
        config.fields.put("birthDate", new JsonConfig());

        assertFalse(ViewableJson.displaySelected(FieldSet.of(person), config));
    }

    @Test void aCaptionlessReferenceStillHasANavigationAffordance() {
        Captionless person = new Captionless();

        assertEquals(ReferenceRow.NAVIGATION_LABEL,
                ViewableJson.referenceCaption(person, null));
    }

    /** A nested reference's caption follows the owning field's child config, as on
     * the desktop card — not the target type's own config. */
    @Test void aNestedReferenceCaptionFollowsTheOwningFieldsConfig() {
        Film film = new Film(new Person("Ada Lovelace"), new Note("restored print"));

        JsonConfig displayUnticked = root("director", "birthDate");
        assertEquals(ReferenceRow.NAVIGATION_LABEL,
                field(ViewableJson.of(film, displayUnticked), "director").ref().name());

        JsonConfig displayTicked = root("director", "name");
        assertEquals("Ada Lovelace",
                field(ViewableJson.of(film, displayTicked), "director").ref().name());
    }

    /** Only an entity chip navigates. A value object expands its embedded content,
     * so with DISPLAY unticked it carries no caption rather than an "Open" link. */
    @Test void aValueObjectWithoutDisplayHasNoNavigationLabel() {
        Film film = new Film(new Person("Ada Lovelace"), new Note("restored print"));

        ViewableView view = ViewableJson.of(film, root("note", "text"));

        assertEquals("", field(view, "note").ref().name());
    }

    private static JsonConfig root(String objectField, String childField) {
        JsonConfig child = new JsonConfig();
        child.fields.put(childField, new JsonConfig());
        JsonConfig root = new JsonConfig();
        root.fields.put(objectField, child);
        return root;
    }

    private static ViewableView.Field field(ViewableView view, String name) {
        return view.fields().stream().filter(f -> name.equals(f.name()))
                .findFirst().orElseThrow(() -> new AssertionError(name + " not rendered"));
    }

    private static final class Film extends ViewableAdapter {
        @DisplayField private final String title = "Film";
        @SuppressWarnings("unused") @Reference private final Person director;
        @SuppressWarnings("unused") @Reference private final Note note;
        private Film(Person director, Note note) {
            this.director = director;
            this.note = note;
        }
        @Override public String getIdentifier() { return title; }
        @Override public String getDisplayName() { return title; }
    }

    private static final class Note extends ViewableAdapter implements quiz.ValueObject {
        @DisplayField private final String label = "Note";
        @SuppressWarnings("unused") private final String text;
        private Note(String text) { this.text = text; }
        @Override public String getIdentifier() { return label; }
        @Override public String getDisplayName() { return label; }
    }

    private static final class Person extends ViewableAdapter {
        @DisplayField private final String name;
        @SuppressWarnings("unused") private final String birthDate = "1815-12-10";

        private Person(String name) { this.name = name; }
        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }

    private static final class Captionless extends ViewableAdapter {
        @Override public String getIdentifier() { return "person"; }
        @Override public String getDisplayName() { return ""; }
        @Override public String typeName() { return "Person"; }
    }
}
